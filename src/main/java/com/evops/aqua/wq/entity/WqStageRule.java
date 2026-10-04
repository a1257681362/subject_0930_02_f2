package com.evops.aqua.wq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 阶段采样频率规则：相对投苗日的天区间 [startDay, endDay]，每 intervalDays 天采样一次。
 */
@TableName("t_wq_stage_rule")
public class WqStageRule extends BaseEntity {

    private Long versionId;
    private String stageCode;
    private String stageName;
    private Integer startDay;
    private Integer endDay;
    private Integer intervalDays;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
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
}
