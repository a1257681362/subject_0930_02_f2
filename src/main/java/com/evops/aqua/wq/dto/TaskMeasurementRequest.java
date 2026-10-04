package com.evops.aqua.wq.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采样任务暂存草稿 / 提交共用的请求体。
 * 草稿允许只填部分指标且不写缺测原因；提交时服务端强校验必测项与缺测原因。
 */
public class TaskMeasurementRequest {

    private LocalDateTime sampledAt;
    private String remark;

    @NotEmpty(message = "测量数据不能为空")
    @Valid
    private List<MeasurementInput> measurements;

    public LocalDateTime getSampledAt() { return sampledAt; }
    public void setSampledAt(LocalDateTime sampledAt) { this.sampledAt = sampledAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<MeasurementInput> getMeasurements() { return measurements; }
    public void setMeasurements(List<MeasurementInput> measurements) { this.measurements = measurements; }
}
