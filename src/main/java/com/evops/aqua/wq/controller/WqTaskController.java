package com.evops.aqua.wq.controller;

import com.evops.aqua.wq.dto.ReviewRequest;
import com.evops.aqua.wq.dto.SupplementRequest;
import com.evops.aqua.wq.dto.TaskMeasurementRequest;
import com.evops.aqua.wq.entity.WqChangeEvidence;
import com.evops.aqua.wq.entity.WqMeasurement;
import com.evops.aqua.wq.entity.WqSamplingTask;
import com.evops.aqua.wq.service.WqSamplingTaskService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 采样任务执行：暂存草稿、提交（缺测必须说明原因）、主管审核、缺测补录（仅追加）。
 * 写接口支持 Idempotency-Key 请求头防重复提交。
 */
@RestController
@RequestMapping("/api/aqua/wq/tasks")
public class WqTaskController {

    private final WqSamplingTaskService taskService;

    public WqTaskController(WqSamplingTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<List<WqSamplingTask>> list(@RequestParam(required = false) Long planId,
                                                  @RequestParam(required = false) Long pondId,
                                                  @RequestParam(required = false) String status) {
        return ApiResponse.ok(taskService.listTasks(planId, pondId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(taskService.taskDetail(id));
    }

    /** 采样员暂存草稿：不校验必测项与缺测原因，可反复修改 */
    @PostMapping("/{id}/draft")
    public ApiResponse<WqSamplingTask> saveDraft(@PathVariable Long id,
                                                 @Validated @RequestBody TaskMeasurementRequest request) {
        return ApiResponse.ok(taskService.saveDraft(id, request));
    }

    /** 正式提交：必测项必须全覆盖，缺测项必须说明原因 */
    @PostMapping("/{id}/submit")
    public ApiResponse<WqSamplingTask> submit(@PathVariable Long id,
                                              @Validated @RequestBody TaskMeasurementRequest request) {
        return ApiResponse.ok(taskService.submit(id, request));
    }

    /** 主管审核：通过后数据才进入池塘健康曲线 */
    @PostMapping("/{id}/review")
    public ApiResponse<WqSamplingTask> review(@PathVariable Long id,
                                              @Validated @RequestBody ReviewRequest request) {
        return ApiResponse.ok(taskService.review(id, Boolean.TRUE.equals(request.getApproved()),
                request.getComment()));
    }

    /** 缺测/修正补录：只追加 SUPPLEMENT 行，不覆盖原测值，需主管单独审核 */
    @PostMapping("/{id}/supplements")
    public ApiResponse<WqMeasurement> supplement(@PathVariable Long id,
                                                 @Validated @RequestBody SupplementRequest request) {
        return ApiResponse.ok(taskService.supplement(id, request));
    }

    /** 任务证据链：草稿修改、提交、审核、补录、曲线写入全程留痕 */
    @GetMapping("/{id}/evidence")
    public ApiResponse<List<WqChangeEvidence>> evidence(@PathVariable Long id) {
        return ApiResponse.ok(taskService.taskEvidence(id));
    }
}
