package com.evops.aqua.wq.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.dto.RuleVersionPublishRequest;
import com.evops.aqua.wq.entity.WqMetricRule;
import com.evops.aqua.wq.entity.WqRuleVersion;
import com.evops.aqua.wq.entity.WqStageRule;
import com.evops.aqua.wq.mapper.WqMetricRuleMapper;
import com.evops.aqua.wq.mapper.WqStageRuleMapper;
import com.evops.aqua.wq.service.WqRuleVersionService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标规则版本：分养殖阶段配置采样频率与指标范围。已发布版本不可修改，调整需发布新版本。
 */
@RestController
@RequestMapping("/api/aqua/wq/rules")
public class WqRuleController {

    private final WqRuleVersionService ruleVersionService;
    private final WqStageRuleMapper stageRuleMapper;
    private final WqMetricRuleMapper metricRuleMapper;

    public WqRuleController(WqRuleVersionService ruleVersionService,
                            WqStageRuleMapper stageRuleMapper,
                            WqMetricRuleMapper metricRuleMapper) {
        this.ruleVersionService = ruleVersionService;
        this.stageRuleMapper = stageRuleMapper;
        this.metricRuleMapper = metricRuleMapper;
    }

    @PostMapping("/versions")
    public ApiResponse<WqRuleVersion> publish(@Validated @RequestBody RuleVersionPublishRequest request) {
        return ApiResponse.ok(ruleVersionService.publish(request));
    }

    @GetMapping("/versions")
    public ApiResponse<List<WqRuleVersion>> list() {
        return ApiResponse.ok(ruleVersionService.listVersions());
    }

    @GetMapping("/versions/latest")
    public ApiResponse<WqRuleVersion> latest() {
        return ApiResponse.ok(ruleVersionService.latestPublished());
    }

    @GetMapping("/versions/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        WqRuleVersion version = ruleVersionService.getOrThrow(id);
        List<WqStageRule> stages = stageRuleMapper.selectList(
                new QueryWrapper<WqStageRule>().eq("version_id", id).orderByAsc("start_day"));
        List<WqMetricRule> metrics = metricRuleMapper.selectList(
                new QueryWrapper<WqMetricRule>().eq("version_id", id)
                        .orderByAsc("stage_code", "sort_no"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("stages", stages);
        detail.put("metrics", metrics);
        return ApiResponse.ok(detail);
    }
}
