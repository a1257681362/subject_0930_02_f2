package com.evops.aqua.wq.dto;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 单个指标的测量录入：有值填 value；缺测时 value 留空并在提交时给出 missingReason。
 */
public class MeasurementInput {

    @NotBlank(message = "指标编码不能为空")
    private String metricCode;
    private BigDecimal value;
    private String missingReason;

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getMissingReason() { return missingReason; }
    public void setMissingReason(String missingReason) { this.missingReason = missingReason; }
}
