package com.evops.aqua.wq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.dto.RuleVersionPublishRequest;
import com.evops.aqua.wq.entity.WqMetricRule;
import com.evops.aqua.wq.entity.WqRuleVersion;
import com.evops.aqua.wq.entity.WqStageRule;
import com.evops.aqua.wq.mapper.WqMetricRuleMapper;
import com.evops.aqua.wq.mapper.WqRuleVersionMapper;
import com.evops.aqua.wq.mapper.WqStageRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标规则版本：不同养殖阶段配置采样频率与指标范围。
 * 已发布版本不可改；需要调整时发布新版本，新版本只作用于未开始任务（在排期服务中处理）。
 */
@Service
public class WqRuleVersionService {

    private final WqRuleVersionMapper versionMapper;
    private final WqStageRuleMapper stageRuleMapper;
    private final WqMetricRuleMapper metricRuleMapper;
    private final WqEvidenceService evidenceService;

    public WqRuleVersionService(WqRuleVersionMapper versionMapper,
                                WqStageRuleMapper stageRuleMapper,
                                WqMetricRuleMapper metricRuleMapper,
                                WqEvidenceService evidenceService) {
        this.versionMapper = versionMapper;
        this.stageRuleMapper = stageRuleMapper;
        this.metricRuleMapper = metricRuleMapper;
        this.evidenceService = evidenceService;
    }

    /**
     * 发布新版本（整体提交：版本头 + 阶段频率 + 指标范围）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WqRuleVersion publish(RuleVersionPublishRequest request) {
        Long duplicated = versionMapper.selectCount(
                new QueryWrapper<WqRuleVersion>().eq("version_no", request.getVersionNo()));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("规则版本号已存在：" + request.getVersionNo());
        }
        validateStages(request);

        WqRuleVersion version = new WqRuleVersion();
        version.setVersionNo(request.getVersionNo());
        version.setStatus(WqRuleVersion.STATUS_PUBLISHED);
        version.setRemark(request.getRemark());
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(version);

        for (RuleVersionPublishRequest.StageRuleDto stageDto : request.getStages()) {
            WqStageRule stage = new WqStageRule();
            stage.setVersionId(version.getId());
            stage.setStageCode(stageDto.getStageCode());
            stage.setStageName(stageDto.getStageName());
            stage.setStartDay(stageDto.getStartDay());
            stage.setEndDay(stageDto.getEndDay());
            stage.setIntervalDays(stageDto.getIntervalDays());
            stageRuleMapper.insert(stage);

            Set<String> metricCodes = new HashSet<>();
            int sort = 1;
            for (RuleVersionPublishRequest.MetricRuleDto metricDto : stageDto.getMetrics()) {
                if (!metricCodes.add(metricDto.getMetricCode())) {
                    throw new IllegalArgumentException("阶段" + stageDto.getStageCode()
                            + "指标编码重复：" + metricDto.getMetricCode());
                }
                if (metricDto.getMinValue() != null && metricDto.getMaxValue() != null
                        && metricDto.getMinValue().compareTo(metricDto.getMaxValue()) > 0) {
                    throw new IllegalArgumentException("阶段" + stageDto.getStageCode() + "指标"
                            + metricDto.getMetricCode() + "范围下限不能大于上限");
                }
                WqMetricRule metric = new WqMetricRule();
                metric.setVersionId(version.getId());
                metric.setStageCode(stageDto.getStageCode());
                metric.setMetricCode(metricDto.getMetricCode());
                metric.setMetricName(metricDto.getMetricName());
                metric.setUnit(metricDto.getUnit());
                metric.setMinValue(metricDto.getMinValue());
                metric.setMaxValue(metricDto.getMaxValue());
                metric.setRequired(Boolean.FALSE.equals(metricDto.getRequired()) ? 0 : 1);
                metric.setSortNo(sort++);
                metricRuleMapper.insert(metric);
            }
        }

        evidenceService.record("WQ_RULE_VERSION", version.getId(), "PUBLISH",
                "发布指标规则版本 " + version.getVersionNo(), null, request);
        return version;
    }

    private void validateStages(RuleVersionPublishRequest request) {
        Set<String> stageCodes = new HashSet<>();
        for (RuleVersionPublishRequest.StageRuleDto stage : request.getStages()) {
            if (!stageCodes.add(stage.getStageCode())) {
                throw new IllegalArgumentException("阶段编码重复：" + stage.getStageCode());
            }
            if (stage.getStartDay() < 0 || stage.getEndDay() < stage.getStartDay()) {
                throw new IllegalArgumentException("阶段" + stage.getStageCode() + "天区间不合法："
                        + stage.getStartDay() + "-" + stage.getEndDay());
            }
            if (stage.getIntervalDays() <= 0) {
                throw new IllegalArgumentException("阶段" + stage.getStageCode() + "采样间隔必须大于0");
            }
        }
    }

    public WqRuleVersion getOrThrow(Long id) {
        WqRuleVersion version = versionMapper.selectById(id);
        if (version == null) {
            throw new IllegalArgumentException("指标规则版本不存在，id=" + id);
        }
        return version;
    }

    /**
     * 最新已发布版本（创建计划时缺省使用）。
     */
    public List<WqRuleVersion> listVersions() {
        return versionMapper.selectList(
                new QueryWrapper<WqRuleVersion>().orderByDesc("published_at").orderByDesc("id"));
    }

    public WqRuleVersion latestPublished() {
        List<WqRuleVersion> versions = versionMapper.selectList(
                new QueryWrapper<WqRuleVersion>()
                        .eq("status", WqRuleVersion.STATUS_PUBLISHED)
                        .orderByDesc("published_at")
                        .orderByDesc("id")
                        .last("LIMIT 1"));
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("系统中尚无已发布的指标规则版本");
        }
        return versions.get(0);
    }

    public List<WqStageRule> listStages(Long versionId) {
        return stageRuleMapper.selectList(
                new QueryWrapper<WqStageRule>().eq("version_id", versionId).orderByAsc("start_day"));
    }

    public List<WqMetricRule> listMetrics(Long versionId) {
        return metricRuleMapper.selectList(
                new QueryWrapper<WqMetricRule>().eq("version_id", versionId)
                        .orderByAsc("stage_code", "sort_no"));
    }

    /**
     * 取某版本各阶段指标规则，便于提交时校验必测项与范围。
     */
    public Map<String, List<WqMetricRule>> metricMapByStage(Long versionId) {
        Map<String, List<WqMetricRule>> result = new HashMap<>();
        for (WqMetricRule metric : listMetrics(versionId)) {
            result.computeIfAbsent(metric.getStageCode(), k -> new java.util.ArrayList<>()).add(metric);
        }
        return result;
    }
}
