package com.evops.aqua.wq.controller;

import com.evops.aqua.wq.dto.PlanCreateRequest;
import com.evops.aqua.wq.entity.WqPlanPond;
import com.evops.aqua.wq.entity.WqSamplingPlan;
import com.evops.aqua.wq.entity.WqSamplingTask;
import com.evops.aqua.wq.mapper.WqPlanPondMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.service.WqSamplingPlanService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 水质采样计划：创建即按池按阶段频率排期生成采样任务；
 * 可切换规则版本，新版本只作用于未开始任务。
 * 写接口支持 Idempotency-Key 请求头防重复提交。
 */
@RestController
@RequestMapping("/api/aqua/wq/plans")
public class WqPlanController {

    private final WqSamplingPlanService planService;
    private final WqPlanPondMapper planPondMapper;

    public WqPlanController(WqSamplingPlanService planService, WqPlanPondMapper planPondMapper) {
        this.planService = planService;
        this.planPondMapper = planPondMapper;
    }

    @PostMapping
    public ApiResponse<WqSamplingPlan> create(@Validated @RequestBody PlanCreateRequest request) {
        return ApiResponse.ok(planService.createPlan(request));
    }

    @GetMapping
    public ApiResponse<List<WqSamplingPlan>> list() {
        return ApiResponse.ok(planService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        WqSamplingPlan plan = planService.getOrThrow(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("plan", plan);
        detail.put("ponds", planPondMapper.selectList(
                new QueryWrapper<WqPlanPond>().eq("plan_id", id).orderByAsc("id")));
        detail.put("tasks", planService.listTasks(id, null, null));
        return ApiResponse.ok(detail);
    }

    @GetMapping("/{id}/tasks")
    public ApiResponse<List<WqSamplingTask>> tasks(@PathVariable Long id,
                                                   @RequestParam(required = false) Long pondId,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.ok(planService.listTasks(id, pondId, status));
    }

    /**
     * 切换指标规则版本：仅重指/重排未开始（PENDING）任务。
     */
    @PostMapping("/{id}/apply-version")
    public ApiResponse<WqSamplingPlan> applyVersion(@PathVariable Long id,
                                                    @RequestParam Long ruleVersionId) {
        return ApiResponse.ok(planService.applyVersion(id, ruleVersionId));
    }
}
