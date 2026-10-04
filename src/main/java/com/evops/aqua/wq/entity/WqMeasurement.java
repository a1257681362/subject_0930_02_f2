package com.evops.aqua.wq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水质测量值。
 * ORIGINAL：采样员首次录入（草稿暂存或提交），提交后冻结，任何修改只能另起 SUPPLEMENT 行；
 * SUPPLEMENT：补录，只追加不覆盖原值；经主管审核通过后才更新到健康曲线。
 * 缺测：value 为空时 missingReason 必填（提交时强校验）。
 */
@TableName("t_wq_measurement")
public class WqMeasurement extends BaseEntity {

    /** 原测：提交后冻结 */
    public static final String TYPE_ORIGINAL = "ORIGINAL";
    /** 补录：仅追加 */
    public static final String TYPE_SUPPLEMENT = "SUPPLEMENT";

    /** 草稿 */
    public static final String REVIEW_DRAFT = "DRAFT";
    /** 已提交待审核 */
    public static final String REVIEW_SUBMITTED = "SUBMITTED";
    /** 审核通过 */
    public static final String REVIEW_APPROVED = "APPROVED";
    /** 审核驳回 */
    public static final String REVIEW_REJECTED = "REJECTED";
    /** 补录待审核 */
    public static final String REVIEW_PENDING = "PENDING";

    private Long taskId;
    private String metricCode;
    private String metricName;
    private String unit;
    private BigDecimal metricValue;
    private String missingReason;
    private String recordType;
    private Long supersedesId;
    private String reviewStatus;
    private String supplementReason;
    private String reviewComment;
    private LocalDateTime measuredAt;
    private LocalDateTime reviewedAt;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getMetricValue() { return metricValue; }
    public void setMetricValue(BigDecimal metricValue) { this.metricValue = metricValue; }
    public String getMissingReason() { return missingReason; }
    public void setMissingReason(String missingReason) { this.missingReason = missingReason; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }
    public Long getSupersedesId() { return supersedesId; }
    public void setSupersedesId(Long supersedesId) { this.supersedesId = supersedesId; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getSupplementReason() { return supplementReason; }
    public void setSupplementReason(String supplementReason) { this.supplementReason = supplementReason; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
    public void setMeasuredAt(LocalDateTime measuredAt) { this.measuredAt = measuredAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
