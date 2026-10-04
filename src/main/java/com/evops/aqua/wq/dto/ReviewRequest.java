package com.evops.aqua.wq.dto;

import javax.validation.constraints.NotNull;

/**
 * 主管审核请求：通过后数据才进入池塘健康曲线。
 */
public class ReviewRequest {

    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    private String comment;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
