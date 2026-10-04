package com.evops.aqua.wq.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建水质采样计划：覆盖若干池塘，按所选规则版本（缺省取最新已发布版本）排期。
 */
public class PlanCreateRequest {

    @NotBlank(message = "计划编号不能为空")
    private String planNo;
    @NotBlank(message = "计划名称不能为空")
    private String planName;
    /** 不传则取最新已发布的指标规则版本 */
    private Long ruleVersionId;
    @NotNull(message = "计划开始日期不能为空")
    private LocalDate startDate;
    @NotNull(message = "计划结束日期不能为空")
    private LocalDate endDate;
    @NotEmpty(message = "至少选择一个养殖池")
    private List<Long> pondIds;
    private String remark;

    public String getPlanNo() { return planNo; }
    public void setPlanNo(String planNo) { this.planNo = planNo; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public Long getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(Long ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public List<Long> getPondIds() { return pondIds; }
    public void setPondIds(List<Long> pondIds) { this.pondIds = pondIds; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
