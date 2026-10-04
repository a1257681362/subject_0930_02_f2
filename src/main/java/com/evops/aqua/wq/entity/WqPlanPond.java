package com.evops.aqua.wq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 计划-池塘明细：记录排期时该池对应的在养批次（可能为空，空时以计划开始日为排期锚点）。
 */
@TableName("t_wq_plan_pond")
public class WqPlanPond extends BaseEntity {

    private Long planId;
    private Long pondId;
    private Long batchId;

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
}
