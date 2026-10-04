package com.evops.aqua.wq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 池塘健康曲线点：只有主管审核通过的任务数据才会进入曲线。
 * 每个任务每个指标一个点（uk_wq_curve_point）；补录审核通过后以补录值更新该点，
 * 但 t_wq_measurement 中的测量原值不动——曲线上可追溯最新值来源。
 */
@TableName("t_wq_health_curve_point")
public class WqHealthCurvePoint extends BaseEntity {

    /** 首次审核通过写入 */
    public static final String SOURCE_ORIGINAL = "ORIGINAL";
    /** 补录审核通过后更新 */
    public static final String SOURCE_SUPPLEMENT = "SUPPLEMENT";

    private Long pondId;
    private Long batchId;
    private Long taskId;
    private String metricCode;
    private String metricName;
    private String unit;
    private LocalDate curveDate;
    private BigDecimal metricValue;
    private String source;
    private Long measurementId;

    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public LocalDate getCurveDate() { return curveDate; }
    public void setCurveDate(LocalDate curveDate) { this.curveDate = curveDate; }
    public BigDecimal getMetricValue() { return metricValue; }
    public void setMetricValue(BigDecimal metricValue) { this.metricValue = metricValue; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getMeasurementId() { return measurementId; }
    public void setMeasurementId(Long measurementId) { this.measurementId = measurementId; }
}
