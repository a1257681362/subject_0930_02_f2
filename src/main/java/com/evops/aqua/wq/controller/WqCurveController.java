package com.evops.aqua.wq.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.dto.ReviewRequest;
import com.evops.aqua.wq.entity.WqChangeEvidence;
import com.evops.aqua.wq.entity.WqHealthCurvePoint;
import com.evops.aqua.wq.entity.WqMeasurement;
import com.evops.aqua.wq.mapper.WqHealthCurvePointMapper;
import com.evops.aqua.wq.service.WqEvidenceService;
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

/**
 * 池塘健康曲线：只有主管审核通过的数据才可查；曲线点按池按指标排序。
 */
@RestController
@RequestMapping("/api/aqua/wq")
public class WqCurveController {

    private final WqHealthCurvePointMapper curvePointMapper;
    private final WqEvidenceService evidenceService;
    private final WqSamplingTaskService taskService;

    public WqCurveController(WqHealthCurvePointMapper curvePointMapper,
                             WqEvidenceService evidenceService,
                             WqSamplingTaskService taskService) {
        this.curvePointMapper = curvePointMapper;
        this.evidenceService = evidenceService;
        this.taskService = taskService;
    }

    /** 补录主管审核：通过后补录值才更新到健康曲线（测量原值不动） */
    @PostMapping("/supplements/{supplementId}/review")
    public ApiResponse<WqMeasurement> reviewSupplement(@PathVariable Long supplementId,
                                                       @Validated @RequestBody ReviewRequest request) {
        return ApiResponse.ok(taskService.reviewSupplement(supplementId,
                Boolean.TRUE.equals(request.getApproved()), request.getComment()));
    }

    @GetMapping("/ponds/{pondId}/curve")
    public ApiResponse<List<WqHealthCurvePoint>> curve(@PathVariable Long pondId,
                                                       @RequestParam(required = false) String metricCode) {
        QueryWrapper<WqHealthCurvePoint> wrapper = new QueryWrapper<WqHealthCurvePoint>()
                .eq("pond_id", pondId)
                .eq(metricCode != null && !metricCode.isEmpty(), "metric_code", metricCode)
                .orderByAsc("curve_date", "metric_code");
        return ApiResponse.ok(curvePointMapper.selectList(wrapper));
    }

    /** 全量证据链查询（可按业务类型过滤：WQ_TASK / WQ_MEASUREMENT / WQ_CURVE / WQ_PLAN / WQ_RULE_VERSION） */
    @GetMapping("/evidence")
    public ApiResponse<List<WqChangeEvidence>> evidence(@RequestParam(required = false) String bizType,
                                                        @RequestParam(required = false) Long bizId) {
        return ApiResponse.ok(evidenceService.list(bizType, bizId));
    }
}
