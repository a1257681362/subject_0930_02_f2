package com.evops.aqua.wq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.service.PondService;
import com.evops.aqua.wq.dto.PlanCreateRequest;
import com.evops.aqua.wq.entity.WqPlanPond;
import com.evops.aqua.wq.entity.WqRuleVersion;
import com.evops.aqua.wq.entity.WqSamplingPlan;
import com.evops.aqua.wq.entity.WqSamplingTask;
import com.evops.aqua.wq.entity.WqStageRule;
import com.evops.aqua.wq.mapper.WqPlanPondMapper;
import com.evops.aqua.wq.mapper.WqSamplingPlanMapper;
import com.evops.aqua.wq.mapper.WqSamplingTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 水质采样计划：按池排期生成采样任务。
 * 排期锚点：有在养批次取投苗日（阶段天=相对投苗日），无批次取计划开始日；
 * 排期截止：计划结束日与批次计划出池日取较小值。
 */
@Service
public class WqSamplingPlanService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WqSamplingPlanMapper planMapper;
    private final WqPlanPondMapper planPondMapper;
    private final WqSamplingTaskMapper taskMapper;
    private final WqRuleVersionService ruleVersionService;
    private final PondService pondService;
    private final SeedlingBatchMapper batchMapper;
    private final WqEvidenceService evidenceService;

    public WqSamplingPlanService(WqSamplingPlanMapper planMapper,
                                 WqPlanPondMapper planPondMapper,
                                 WqSamplingTaskMapper taskMapper,
                                 WqRuleVersionService ruleVersionService,
                                 PondService pondService,
                                 SeedlingBatchMapper batchMapper,
                                 WqEvidenceService evidenceService) {
        this.planMapper = planMapper;
        this.planPondMapper = planPondMapper;
        this.taskMapper = taskMapper;
        this.ruleVersionService = ruleVersionService;
        this.pondService = pondService;
        this.batchMapper = batchMapper;
        this.evidenceService = evidenceService;
    }

    /**
     * 创建计划并按池一次性排期生成全部 PENDING 任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public WqSamplingPlan createPlan(PlanCreateRequest request) {
        Long duplicated = planMapper.selectCount(
                new QueryWrapper<WqSamplingPlan>().eq("plan_no", request.getPlanNo()));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("采样计划编号已存在：" + request.getPlanNo());
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("计划结束日期不能早于开始日期");
        }
        WqRuleVersion version = request.getRuleVersionId() == null
                ? ruleVersionService.latestPublished()
                : ruleVersionService.getOrThrow(request.getRuleVersionId());
        if (!WqRuleVersion.STATUS_PUBLISHED.equals(version.getStatus())) {
            throw new IllegalArgumentException("规则版本未发布，不能用于创建计划：" + version.getVersionNo());
        }

        WqSamplingPlan plan = new WqSamplingPlan();
        plan.setPlanNo(request.getPlanNo());
        plan.setPlanName(request.getPlanName());
        plan.setRuleVersionId(version.getId());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setStatus(WqSamplingPlan.STATUS_ACTIVE);
        plan.setRemark(request.getRemark());
        planMapper.insert(plan);

        int taskTotal = 0;
        Map<Long, Long> dedupPond = new LinkedHashMap<>();
        for (Long pondId : request.getPondIds()) {
            dedupPond.putIfAbsent(pondId, pondId);
        }
        for (Long pondId : dedupPond.keySet()) {
            Pond pond = pondService.getOrThrow(pondId);
            pondService.requireActive(pond);
            SeedlingBatch batch = currentStockedBatch(pondId);

            WqPlanPond planPond = new WqPlanPond();
            planPond.setPlanId(plan.getId());
            planPond.setPondId(pondId);
            planPond.setBatchId(batch == null ? null : batch.getId());
            planPondMapper.insert(planPond);

            taskTotal += schedulePond(plan, pondId,
                    batch == null ? null : batch.getId(),
                    anchorDate(plan, batch), version.getId());
        }

        evidenceService.record("WQ_PLAN", plan.getId(), "CREATE",
                "创建采样计划 " + plan.getPlanNo() + "，按池生成采样任务 " + taskTotal + " 条，规则版本 "
                        + version.getVersionNo(), null, request);
        return plan;
    }

    /**
     * 应用新指标规则版本：只重指/重排 PENDING（未开始）任务，已开始（草稿/提交/审核）任务不动。
     */
    @Transactional(rollbackFor = Exception.class)
    public WqSamplingPlan applyVersion(Long planId, Long newVersionId) {
        WqSamplingPlan plan = getOrThrow(planId);
        if (!WqSamplingPlan.STATUS_ACTIVE.equals(plan.getStatus())) {
            throw new IllegalArgumentException("计划已关闭，不能切换规则版本：" + plan.getPlanNo());
        }
        WqRuleVersion newVersion = ruleVersionService.getOrThrow(newVersionId);
        if (!WqRuleVersion.STATUS_PUBLISHED.equals(newVersion.getStatus())) {
            throw new IllegalArgumentException("规则版本未发布：" + newVersion.getVersionNo());
        }
        if (newVersion.getId().equals(plan.getRuleVersionId())) {
            throw new IllegalArgumentException("计划当前已使用该规则版本：" + newVersion.getVersionNo());
        }

        Long oldVersionId = plan.getRuleVersionId();
        int inserted = 0;
        int repointed = 0;
        int removed = 0;

        List<WqPlanPond> planPonds = planPondMapper.selectList(
                new QueryWrapper<WqPlanPond>().eq("plan_id", planId));
        for (WqPlanPond pp : planPonds) {
            SeedlingBatch batch = pp.getBatchId() == null ? null : batchMapper.selectById(pp.getBatchId());
            LocalDate anchor = anchorDate(plan, batch);
            Map<LocalDate, WqStageRule> desired = desiredSlots(plan, anchor, batch, newVersion.getId());

            List<WqSamplingTask> pending = taskMapper.selectList(new QueryWrapper<WqSamplingTask>()
                    .eq("plan_id", planId)
                    .eq("pond_id", pp.getPondId())
                    .eq("status", WqSamplingTask.STATUS_PENDING));
            Map<LocalDate, WqSamplingTask> pendingByDate = new HashMap<>();
            for (WqSamplingTask task : pending) {
                pendingByDate.put(task.getPlannedDate(), task);
            }

            for (Map.Entry<LocalDate, WqStageRule> entry : desired.entrySet()) {
                LocalDate date = entry.getKey();
                WqStageRule stage = entry.getValue();
                WqSamplingTask exists = pendingByDate.remove(date);
                if (exists == null) {
                    // 该日期可能被已开始任务占用——唯一槽位约束下跳过
                    Long occupied = taskMapper.selectCount(new QueryWrapper<WqSamplingTask>()
                            .eq("plan_id", planId).eq("pond_id", pp.getPondId())
                            .eq("planned_date", date));
                    if (occupied != null && occupied > 0) {
                        continue;
                    }
                    insertTask(plan, pp.getPondId(), pp.getBatchId(), date, stage, newVersion.getId());
                    inserted++;
                } else {
                    exists.setRuleVersionId(newVersion.getId());
                    exists.setStageCode(stage.getStageCode());
                    exists.setStageName(stage.getStageName());
                    taskMapper.updateById(exists);
                    repointed++;
                }
            }
            // 新版本频率下不再需要的未开始任务：无任何采样数据，可删除重排
            for (WqSamplingTask obsolete : pendingByDate.values()) {
                taskMapper.deleteById(obsolete.getId());
                removed++;
            }
        }

        plan.setRuleVersionId(newVersion.getId());
        planMapper.updateById(plan);

        evidenceService.record("WQ_PLAN", plan.getId(), "APPLY_RULE_VERSION",
                "规则版本 " + oldVersionId + " → " + newVersion.getVersionNo()
                        + "；仅作用于未开始任务：重指 " + repointed + " 条、新增 " + inserted
                        + " 条、移除 " + removed + " 条；已开始任务保持原版本不变",
                oldVersionId, newVersionId);
        return plan;
    }

    public WqSamplingPlan getOrThrow(Long id) {
        WqSamplingPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new IllegalArgumentException("采样计划不存在，id=" + id);
        }
        return plan;
    }

    public List<WqSamplingPlan> list() {
        return planMapper.selectList(new QueryWrapper<WqSamplingPlan>().orderByDesc("id"));
    }

    public List<WqSamplingTask> listTasks(Long planId, Long pondId, String status) {
        QueryWrapper<WqSamplingTask> wrapper = new QueryWrapper<>();
        wrapper.eq(planId != null, "plan_id", planId);
        wrapper.eq(pondId != null, "pond_id", pondId);
        wrapper.eq(status != null && !status.isEmpty(), "status", status);
        wrapper.orderByAsc("planned_date", "pond_id");
        return taskMapper.selectList(wrapper);
    }

    // ------------------------------------------------------------
    // 排期计算
    // ------------------------------------------------------------

    /**
     * 按池排期，返回生成的任务条数。
     */
    private int schedulePond(WqSamplingPlan plan, Long pondId, Long batchId,
                             LocalDate anchor, Long versionId) {
        Map<LocalDate, WqStageRule> slots = desiredSlots(plan, anchor,
                batchId == null ? null : batchMapper.selectById(batchId), versionId);
        int count = 0;
        for (Map.Entry<LocalDate, WqStageRule> entry : slots.entrySet()) {
            insertTask(plan, pondId, batchId, entry.getKey(), entry.getValue(), versionId);
            count++;
        }
        return count;
    }

    /**
     * 计算某池在某规则版本下"日期 → 阶段"的期望排期。
     */
    private Map<LocalDate, WqStageRule> desiredSlots(WqSamplingPlan plan, LocalDate anchor,
                                                      SeedlingBatch batch, Long versionId) {
        LocalDate endBoundary = plan.getEndDate();
        if (batch != null && batch.getPlannedHarvestDate() != null
                && batch.getPlannedHarvestDate().isBefore(endBoundary)) {
            endBoundary = batch.getPlannedHarvestDate();
        }
        int maxDay = (int) java.time.temporal.ChronoUnit.DAYS.between(anchor, endBoundary);

        Map<LocalDate, WqStageRule> slots = new LinkedHashMap<>();
        List<WqStageRule> stages = ruleVersionService.listStages(versionId);
        for (WqStageRule stage : stages) {
            int day = stage.getStartDay();
            int to = Math.min(stage.getEndDay(), maxDay);
            while (day <= to) {
                LocalDate date = anchor.plusDays(day);
                if (!date.isBefore(plan.getStartDate()) && !date.isAfter(endBoundary)) {
                    slots.put(date, stage);
                }
                day += stage.getIntervalDays();
            }
        }
        return slots;
    }

    private void insertTask(WqSamplingPlan plan, Long pondId, Long batchId,
                            LocalDate date, WqStageRule stage, Long versionId) {
        WqSamplingTask task = new WqSamplingTask();
        task.setTaskNo("WQ-" + plan.getId() + "-" + pondId + "-" + date.format(NO_DATE));
        task.setPlanId(plan.getId());
        task.setPondId(pondId);
        task.setBatchId(batchId);
        task.setStageCode(stage.getStageCode());
        task.setStageName(stage.getStageName());
        task.setRuleVersionId(versionId);
        task.setPlannedDate(date);
        task.setStatus(WqSamplingTask.STATUS_PENDING);
        taskMapper.insert(task);
    }

    private LocalDate anchorDate(WqSamplingPlan plan, SeedlingBatch batch) {
        if (batch != null && batch.getStockDate() != null) {
            return batch.getStockDate();
        }
        return plan.getStartDate();
    }

    private SeedlingBatch currentStockedBatch(Long pondId) {
        List<SeedlingBatch> batches = batchMapper.selectList(new QueryWrapper<SeedlingBatch>()
                .eq("pond_id", pondId)
                .eq("status", SeedlingBatch.STATUS_STOCKED)
                .orderByDesc("stock_date")
                .orderByDesc("id")
                .last("LIMIT 1"));
        return batches.isEmpty() ? null : batches.get(0);
    }
}
