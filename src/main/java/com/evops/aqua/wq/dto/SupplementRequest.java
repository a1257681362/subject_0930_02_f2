package com.evops.aqua.wq.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 缺测补录：只能追加新的 SUPPLEMENT 记录，服务端不提供任何覆盖原值的入口。
 */
public class SupplementRequest {

    @NotBlank(message = "指标编码不能为空")
    private String metricCode;

    @NotNull(message = "补录必须填写补测数值")
    private BigDecimal value;

    @NotBlank(message = "补录必须说明补录原因")
    private String supplementReason;

    private LocalDateTime measuredAt;

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getSupplementReason() { return supplementReason; }
    public void setSupplementReason(String supplementReason) { this.supplementReason = supplementReason; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
    public void setMeasuredAt(LocalDateTime measuredAt) { this.measuredAt = measuredAt; }
}
