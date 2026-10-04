package com.evops.aqua.wq.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 发布新指标规则版本：分养殖阶段配置采样频率与指标范围。
 */
public class RuleVersionPublishRequest {

    @NotBlank(message = "规则版本号不能为空")
    private String versionNo;

    private String remark;

    @NotEmpty(message = "至少配置一个养殖阶段")
    @Valid
    private List<StageRuleDto> stages;

    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<StageRuleDto> getStages() { return stages; }
    public void setStages(List<StageRuleDto> stages) { this.stages = stages; }

    public static class StageRuleDto {
        @NotBlank(message = "阶段编码不能为空")
        private String stageCode;
        @NotBlank(message = "阶段名称不能为空")
        private String stageName;
        @NotNull(message = "起始天不能为空")
        private Integer startDay;
        @NotNull(message = "结束天不能为空")
        private Integer endDay;
        @NotNull(message = "采样间隔天数不能为空")
        private Integer intervalDays;

        @NotEmpty(message = "阶段至少配置一个指标")
        @Valid
        private List<MetricRuleDto> metrics;

        public String getStageCode() { return stageCode; }
        public void setStageCode(String stageCode) { this.stageCode = stageCode; }
        public String getStageName() { return stageName; }
        public void setStageName(String stageName) { this.stageName = stageName; }
        public Integer getStartDay() { return startDay; }
        public void setStartDay(Integer startDay) { this.startDay = startDay; }
        public Integer getEndDay() { return endDay; }
        public void setEndDay(Integer endDay) { this.endDay = endDay; }
        public Integer getIntervalDays() { return intervalDays; }
        public void setIntervalDays(Integer intervalDays) { this.intervalDays = intervalDays; }
        public List<MetricRuleDto> getMetrics() { return metrics; }
        public void setMetrics(List<MetricRuleDto> metrics) { this.metrics = metrics; }
    }

    public static class MetricRuleDto {
        @NotBlank(message = "指标编码不能为空")
        private String metricCode;
        @NotBlank(message = "指标名称不能为空")
        private String metricName;
        private String unit;
        private java.math.BigDecimal minValue;
        private java.math.BigDecimal maxValue;
        /** 是否必测，缺省为必测 */
        private Boolean required = Boolean.TRUE;

        public String getMetricCode() { return metricCode; }
        public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
        public String getMetricName() { return metricName; }
        public void setMetricName(String metricName) { this.metricName = metricName; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public java.math.BigDecimal getMinValue() { return minValue; }
        public void setMinValue(java.math.BigDecimal minValue) { this.minValue = minValue; }
        public java.math.BigDecimal getMaxValue() { return maxValue; }
        public void setMaxValue(java.math.BigDecimal maxValue) { this.maxValue = maxValue; }
        public Boolean getRequired() { return required; }
        public void setRequired(Boolean required) { this.required = required; }
    }
}
