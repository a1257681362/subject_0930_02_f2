package com.evops.aqua.wq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.dto.MeasurementInput;
import com.evops.aqua.wq.dto.SupplementRequest;
import com.evops.aqua.wq.dto.TaskMeasurementRequest;
import com.evops.aqua.wq.entity.WqChangeEvidence;
import com.evops.aqua.wq.entity.WqHealthCurvePoint;
import com.evops.aqua.wq.entity.WqMeasurement;
import com.evops.aqua.wq.entity.WqMetricRule;
import com.evops.aqua.wq.entity.WqSamplingTask;
import com.evops.aqua.wq.mapper.WqHealthCurvePointMapper;
import com.evops.aqua.wq.mapper.WqMeasurementMapper;
import com.evops.aqua.wq.mapper.WqSamplingTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采样任务执行：草稿暂存 → 提交（缺测必须说明原因）→ 主管审核 → 健康曲线；
 * 审核通过后原测冻结，任何修正只能以 SUPPLEMENT 行追加补录。
 */
@Service
public class WqSamplingTaskService {

    private final WqSamplingTaskMapper taskMapper;
    private final WqMeasurementMapper measurementMapper;
    private final WqHealthCurvePointMapper curvePointMapper;
    private final WqRuleVersionService ruleVersionService;
    private final WqEvidenceService evidenceService;

    public WqSamplingTaskService(WqSamplingTaskMapper taskMapper,
                                 WqMeasurementMapper measurementMapper,
                                 WqHealthCurvePointMapper curvePointMapper,
                                 WqRuleVersionService ruleVersionService,
                                 WqEvidenceService evidenceService) {
        this.taskMapper = taskMapper;
        this.measurementMapper = measurementMapper;
        this.curvePointMapper = curvePointMapper;
        this.ruleVersionService = ruleVersionService;
        this.evidenceService = evidenceService;
    }

    // ------------------------------------------------------------
    // 草稿暂存
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public WqSamplingTask saveDraft(Long taskId, TaskMeasurementRequest request) {
        WqSamplingTask task = getOrThrow(taskId);
        requireEditable(task);

        Map<String, WqMetricRule> rules = rulesOf(task);
        Map<String, WqMeasurement> originals = originalMap(taskId);
        LocalDateTime sampledAt = request.getSampledAt() == null ? LocalDateTime.now() : request.getSampledAt();

        upsertOriginals(task, rules, originals, request.getMeasurements(), sampledAt,
                WqMeasurement.REVIEW_DRAFT);

        task.setStatus(WqSamplingTask.STATUS_DRAFT);
        task.setSampledAt(sampledAt);
        task.setRemark(request.getRemark());
        taskMapper.updateById(task);

        evidenceService.record("WQ_TASK", taskId, "SAVE_DRAFT",
                "采样草稿暂存，指标项 " + request.getMeasurements().size() + " 条（草稿不校验必测项与缺测原因）",
                null, request.getMeasurements());
        return task;
    }

    // ------------------------------------------------------------
    // 提交：必测项全覆盖，缺测必须说明原因
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public WqSamplingTask submit(Long taskId, TaskMeasurementRequest request) {
        WqSamplingTask task = getOrThrow(taskId);
        requireEditable(task);

        Map<String, WqMetricRule> rules = rulesOf(task);
        Map<String, WqMeasurement> originals = originalMap(taskId);
        LocalDateTime sampledAt = request.getSampledAt() == null ? LocalDateTime.now() : request.getSampledAt();

        upsertOriginals(task, rules, originals, request.getMeasurements(), sampledAt,
                WqMeasurement.REVIEW_SUBMITTED);

        // 重新取全量原测，校验必测项与缺测原因
        originals = originalMap(taskId);
        List<String> problems = new ArrayList<>();
        List<String> rangeAlerts = new ArrayList<>();
        for (WqMetricRule rule : rules.values()) {
            WqMeasurement m = originals.get(rule.getMetricCode());
            if (!Integer.valueOf(1).equals(rule.getRequired())) {
                continue;
            }
            if (m == null || (m.getMetricValue() == null && isBlank(m.getMissingReason()))) {
                problems.add(rule.getMetricName() + "(" + rule.getMetricCode() + ")");
                continue;
            }
            if (m.getMetricValue() != null && outOfRange(m.getMetricValue(), rule)) {
                rangeAlerts.add(rule.getMetricName() + "=" + m.getMetricValue()
                        + "，参考范围 " + rangeText(rule));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("提交被拒：以下必测指标未填报或缺测未说明原因："
                    + String.join("、", problems));
        }

        // 有值无原因、缺测有原因：统一置为已提交待审核
        for (WqMeasurement m : originals.values()) {
            if (!WqMeasurement.REVIEW_SUBMITTED.equals(m.getReviewStatus())) {
                m.setReviewStatus(WqMeasurement.REVIEW_SUBMITTED);
                measurementMapper.updateById(m);
            }
        }

        task.setStatus(WqSamplingTask.STATUS_SUBMITTED);
        task.setSampledAt(sampledAt);
        task.setSubmittedAt(LocalDateTime.now());
        task.setRemark(request.getRemark());
        taskMapper.updateById(task);

        String detail = "采样提交，指标项 " + originals.size() + " 条，缺测 "
                + countMissing(originals.values()) + " 项（均已说明原因）"
                + (rangeAlerts.isEmpty() ? "" : "；超参考范围提示：" + String.join("；", rangeAlerts));
        evidenceService.record("WQ_TASK", taskId, "SUBMIT", detail, null, request.getMeasurements());
        return task;
    }

    // ------------------------------------------------------------
    // 主管审核：通过后才写入池塘健康曲线
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public WqSamplingTask review(Long taskId, boolean approved, String comment) {
        WqSamplingTask task = getOrThrow(taskId);
        if (!WqSamplingTask.STATUS_SUBMITTED.equals(task.getStatus())) {
            throw new IllegalArgumentException("只有已提交待审核的任务才能审核，当前状态：" + task.getStatus());
        }

        List<WqMeasurement> originals = measurements(taskId, WqMeasurement.TYPE_ORIGINAL);
        LocalDateTime now = LocalDateTime.now();
        if (approved) {
            int curvePoints = 0;
            for (WqMeasurement m : originals) {
                m.setReviewStatus(WqMeasurement.REVIEW_APPROVED);
                m.setReviewComment(comment);
                m.setReviewedAt(now);
                measurementMapper.updateById(m);
                if (m.getMetricValue() != null) {
                    writeCurvePoint(task, m, WqHealthCurvePoint.SOURCE_ORIGINAL);
                    curvePoints++;
                }
            }
            task.setStatus(WqSamplingTask.STATUS_APPROVED);
            evidenceService.record("WQ_TASK", taskId, "APPROVE",
                    "主管审核通过，" + curvePoints + " 个有效指标点进入池塘健康曲线；缺测项不产生曲线点"
                            + (comment == null ? "" : "；审核意见：" + comment),
                    null, comment);
        } else {
            if (isBlank(comment)) {
                throw new IllegalArgumentException("驳回必须填写审核意见");
            }
            for (WqMeasurement m : originals) {
                m.setReviewStatus(WqMeasurement.REVIEW_REJECTED);
                m.setReviewComment(comment);
                m.setReviewedAt(now);
                measurementMapper.updateById(m);
            }
            task.setStatus(WqSamplingTask.STATUS_REJECTED);
            evidenceService.record("WQ_TASK", taskId, "REJECT",
                    "主管审核驳回：" + comment, null, comment);
        }
        task.setReviewedAt(now);
        task.setReviewComment(comment);
        taskMapper.updateById(task);
        return task;
    }

    // ------------------------------------------------------------
    // 缺测补录：审核通过后只允许追加，永不覆盖测量原值
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public WqMeasurement supplement(Long taskId, SupplementRequest request) {
        WqSamplingTask task = getOrThrow(taskId);
        if (!WqSamplingTask.STATUS_APPROVED.equals(task.getStatus())) {
            throw new IllegalArgumentException(
                    "补录只针对主管已审核通过的任务（审核前请在草稿中修改后重新提交），当前状态："
                            + task.getStatus());
        }
        Map<String, WqMetricRule> rules = rulesOf(task);
        WqMetricRule rule = rules.get(request.getMetricCode());
        if (rule == null) {
            throw new IllegalArgumentException("指标不在该任务的规则快照中：" + request.getMetricCode());
        }

        List<WqMeasurement> metricRows = measurementMapper.selectList(new QueryWrapper<WqMeasurement>()
                .eq("task_id", taskId)
                .eq("metric_code", request.getMetricCode())
                .orderByAsc("id"));
        if (metricRows.isEmpty()) {
            throw new IllegalArgumentException("该指标无原测记录，不能补录：" + request.getMetricCode());
        }
        for (WqMeasurement row : metricRows) {
            if (WqMeasurement.TYPE_SUPPLEMENT.equals(row.getRecordType())
                    && WqMeasurement.REVIEW_PENDING.equals(row.getReviewStatus())) {
                throw new IllegalArgumentException("该指标已有待审核的补录，请等主管审核后再追加："
                        + request.getMetricCode());
            }
        }

        WqMeasurement supplement = new WqMeasurement();
        supplement.setTaskId(taskId);
        supplement.setMetricCode(rule.getMetricCode());
        supplement.setMetricName(rule.getMetricName());
        supplement.setUnit(rule.getUnit());
        supplement.setMetricValue(request.getValue());
        supplement.setRecordType(WqMeasurement.TYPE_SUPPLEMENT);
        supplement.setSupersedesId(metricRows.get(metricRows.size() - 1).getId());
        supplement.setReviewStatus(WqMeasurement.REVIEW_PENDING);
        supplement.setSupplementReason(request.getSupplementReason());
        supplement.setMeasuredAt(request.getMeasuredAt() == null ? LocalDateTime.now() : request.getMeasuredAt());
        measurementMapper.insert(supplement);

        evidenceService.record("WQ_MEASUREMENT", supplement.getId(), "SUPPLEMENT_APPEND",
                "任务" + task.getTaskNo() + "指标" + rule.getMetricName()
                        + "追加补录（不覆盖原值），补录原因：" + request.getSupplementReason(),
                metricRows.get(0), supplement);
        return supplement;
    }

    @Transactional(rollbackFor = Exception.class)
    public WqMeasurement reviewSupplement(Long supplementId, boolean approved, String comment) {
        WqMeasurement supplement = measurementMapper.selectById(supplementId);
        if (supplement == null
                || !WqMeasurement.TYPE_SUPPLEMENT.equals(supplement.getRecordType())) {
            throw new IllegalArgumentException("补录记录不存在，id=" + supplementId);
        }
        if (!WqMeasurement.REVIEW_PENDING.equals(supplement.getReviewStatus())) {
            throw new IllegalArgumentException("该补录已审核，不能重复审核，当前状态："
                    + supplement.getReviewStatus());
        }
        WqSamplingTask task = getOrThrow(supplement.getTaskId());
        LocalDateTime now = LocalDateTime.now();

        if (approved) {
            supplement.setReviewStatus(WqMeasurement.REVIEW_APPROVED);
            supplement.setReviewComment(comment);
            supplement.setReviewedAt(now);
            measurementMapper.updateById(supplement);
            writeCurvePoint(task, supplement, WqHealthCurvePoint.SOURCE_SUPPLEMENT);
            evidenceService.record("WQ_MEASUREMENT", supplementId, "SUPPLEMENT_APPROVE",
                    "补录审核通过，曲线点更新为补录值；原测行保持不变"
                            + (comment == null ? "" : "；意见：" + comment),
                    null, supplement);
        } else {
            if (isBlank(comment)) {
                throw new IllegalArgumentException("驳回必须填写审核意见");
            }
            supplement.setReviewStatus(WqMeasurement.REVIEW_REJECTED);
            supplement.setReviewComment(comment);
            supplement.setReviewedAt(now);
            measurementMapper.updateById(supplement);
            evidenceService.record("WQ_MEASUREMENT", supplementId, "SUPPLEMENT_REJECT",
                    "补录审核驳回：" + comment + "；曲线保持原值", null, comment);
        }
        return supplement;
    }

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    public WqSamplingTask getOrThrow(Long id) {
        WqSamplingTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new IllegalArgumentException("采样任务不存在，id=" + id);
        }
        return task;
    }

    public List<WqSamplingTask> listTasks(Long planId, Long pondId, String status) {
        QueryWrapper<WqSamplingTask> wrapper = new QueryWrapper<>();
        wrapper.eq(planId != null, "plan_id", planId);
        wrapper.eq(pondId != null, "pond_id", pondId);
        wrapper.eq(status != null && !status.isEmpty(), "status", status);
        wrapper.orderByAsc("planned_date", "pond_id");
        return taskMapper.selectList(wrapper);
    }

    /**
     * 任务证据链：任务本身的动作 + 该任务下测量行（含补录）+ 曲线点的证据，按时间聚合。
     */
    public List<WqChangeEvidence> taskEvidence(Long taskId) {
        getOrThrow(taskId);
        List<WqChangeEvidence> all = new ArrayList<>(evidenceService.list("WQ_TASK", taskId));
        for (WqMeasurement m : measurementMapper.selectList(
                new QueryWrapper<WqMeasurement>().eq("task_id", taskId))) {
            all.addAll(evidenceService.list("WQ_MEASUREMENT", m.getId()));
        }
        for (WqHealthCurvePoint point : curvePointMapper.selectList(
                new QueryWrapper<WqHealthCurvePoint>().eq("task_id", taskId))) {
            all.addAll(evidenceService.list("WQ_CURVE", point.getId()));
        }
        all.sort(java.util.Comparator.comparing(WqChangeEvidence::getId));
        return all;
    }

    public Map<String, Object> taskDetail(Long taskId) {        WqSamplingTask task = getOrThrow(taskId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("task", task);
        detail.put("measurements", measurementMapper.selectList(new QueryWrapper<WqMeasurement>()
                .eq("task_id", taskId).orderByAsc("record_type", "id")));
        detail.put("metricRules", ruleVersionService.metricMapByStage(task.getRuleVersionId())
                .getOrDefault(task.getStageCode(), new ArrayList<>()));
        detail.put("curvePoints", curvePointMapper.selectList(new QueryWrapper<WqHealthCurvePoint>()
                .eq("task_id", taskId).orderByAsc("metric_code")));
        return detail;
    }

    // ------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------

    /**
     * 草稿/提交前的原测写入：已存在的原测行仅在 DRAFT/REJECTED 状态可就地更新；
     * SUBMITTED/APPROVED 状态冻结，任何修改只能走补录追加。
     */
    private void upsertOriginals(WqSamplingTask task,
                                 Map<String, WqMetricRule> rules,
                                 Map<String, WqMeasurement> originals,
                                 List<MeasurementInput> inputs,
                                 LocalDateTime sampledAt,
                                 String nextStatus) {
        for (MeasurementInput input : inputs) {
            WqMetricRule rule = rules.get(input.getMetricCode());
            if (rule == null) {
                throw new IllegalArgumentException(
                        "指标不在任务所属阶段[" + task.getStageName() + "]的规则快照中："
                                + input.getMetricCode());
            }
            WqMeasurement existing = originals.get(input.getMetricCode());
            if (existing == null) {
                WqMeasurement m = new WqMeasurement();
                m.setTaskId(task.getId());
                m.setMetricCode(rule.getMetricCode());
                m.setMetricName(rule.getMetricName());
                m.setUnit(rule.getUnit());
                m.setMetricValue(input.getValue());
                m.setMissingReason(input.getValue() == null ? normalize(input.getMissingReason()) : null);
                m.setRecordType(WqMeasurement.TYPE_ORIGINAL);
                m.setReviewStatus(nextStatus);
                m.setMeasuredAt(sampledAt);
                measurementMapper.insert(m);
                originals.put(input.getMetricCode(), m);
            } else {
                if (WqMeasurement.REVIEW_SUBMITTED.equals(existing.getReviewStatus())
                        || WqMeasurement.REVIEW_APPROVED.equals(existing.getReviewStatus())) {
                    throw new IllegalArgumentException(
                            "指标" + rule.getMetricName() + "已提交/审核，原测冻结，不能覆盖；如需修正请走补录追加");
                }
                evidenceService.record("WQ_MEASUREMENT", existing.getId(), "DRAFT_MODIFY",
                        "草稿阶段修改原测录入（提交审核后将冻结）", existing, input);
                existing.setMetricValue(input.getValue());
                existing.setMissingReason(input.getValue() == null ? normalize(input.getMissingReason()) : null);
                existing.setMeasuredAt(sampledAt);
                existing.setReviewStatus(nextStatus);
                measurementMapper.updateById(existing);
            }
        }
    }

    private void writeCurvePoint(WqSamplingTask task, WqMeasurement m, String source) {
        WqHealthCurvePoint existing = curvePointMapper.selectOne(new QueryWrapper<WqHealthCurvePoint>()
                .eq("task_id", task.getId())
                .eq("metric_code", m.getMetricCode()));
        if (existing == null) {
            WqHealthCurvePoint point = new WqHealthCurvePoint();
            point.setPondId(task.getPondId());
            point.setBatchId(task.getBatchId());
            point.setTaskId(task.getId());
            point.setMetricCode(m.getMetricCode());
            point.setMetricName(m.getMetricName());
            point.setUnit(m.getUnit());
            point.setCurveDate(task.getPlannedDate());
            point.setMetricValue(m.getMetricValue());
            point.setSource(source);
            point.setMeasurementId(m.getId());
            curvePointMapper.insert(point);
            evidenceService.record("WQ_CURVE", point.getId(), "CURVE_WRITE",
                    "审核通过，健康曲线新增点：池" + task.getPondId() + " " + task.getPlannedDate()
                            + " " + m.getMetricName() + "=" + m.getMetricValue(),
                    null, point);
        } else {
            WqHealthCurvePoint before = new WqHealthCurvePoint();
            org.springframework.beans.BeanUtils.copyProperties(existing, before);
            existing.setMetricValue(m.getMetricValue());
            existing.setSource(source);
            existing.setMeasurementId(m.getId());
            existing.setUnit(m.getUnit());
            curvePointMapper.updateById(existing);
            evidenceService.record("WQ_CURVE", existing.getId(), "CURVE_UPDATE",
                    "补录审核通过，健康曲线点更新为补录值（测量原值行不动）：" + m.getMetricName(),
                    before, existing);
        }
    }

    private void requireEditable(WqSamplingTask task) {
        String status = task.getStatus();
        if (WqSamplingTask.STATUS_SUBMITTED.equals(status)
                || WqSamplingTask.STATUS_APPROVED.equals(status)) {
            throw new IllegalArgumentException("任务已" + (WqSamplingTask.STATUS_APPROVED.equals(status)
                    ? "审核通过" : "提交") + "，不能再编辑草稿，当前状态：" + status);
        }
    }

    private Map<String, WqMetricRule> rulesOf(WqSamplingTask task) {
        Map<String, List<WqMetricRule>> map =
                ruleVersionService.metricMapByStage(task.getRuleVersionId());
        Map<String, WqMetricRule> byCode = new HashMap<>();
        for (WqMetricRule rule : map.getOrDefault(task.getStageCode(), new ArrayList<>())) {
            byCode.put(rule.getMetricCode(), rule);
        }
        return byCode;
    }

    private Map<String, WqMeasurement> originalMap(Long taskId) {
        Map<String, WqMeasurement> map = new LinkedHashMap<>();
        for (WqMeasurement m : measurements(taskId, WqMeasurement.TYPE_ORIGINAL)) {
            map.put(m.getMetricCode(), m);
        }
        return map;
    }

    private List<WqMeasurement> measurements(Long taskId, String recordType) {
        return measurementMapper.selectList(new QueryWrapper<WqMeasurement>()
                .eq("task_id", taskId)
                .eq("record_type", recordType)
                .orderByAsc("id"));
    }

    private long countMissing(java.util.Collection<WqMeasurement> rows) {
        return rows.stream().filter(m -> m.getMetricValue() == null).count();
    }

    private boolean outOfRange(java.math.BigDecimal value, WqMetricRule rule) {
        return (rule.getMinValue() != null && value.compareTo(rule.getMinValue()) < 0)
                || (rule.getMaxValue() != null && value.compareTo(rule.getMaxValue()) > 0);
    }

    private String rangeText(WqMetricRule rule) {
        return "[" + rule.getMinValue() + ", " + rule.getMaxValue() + "]"
                + (rule.getUnit() == null ? "" : rule.getUnit());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String normalize(String s) {
        return s == null ? null : s.trim();
    }
}
