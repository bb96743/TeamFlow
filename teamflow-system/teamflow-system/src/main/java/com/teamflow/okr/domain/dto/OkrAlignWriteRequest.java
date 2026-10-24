package com.teamflow.okr.domain.dto;

/**
 * 目标对齐关系新增请求：子目标 → 父目标（单向，M02 RULE R6）。
 */
public class OkrAlignWriteRequest {

    private Long parentObjectiveId;
    private String alignType;
    private String remark;

    public Long getParentObjectiveId() { return parentObjectiveId; }
    public void setParentObjectiveId(Long parentObjectiveId) { this.parentObjectiveId = parentObjectiveId; }
    public String getAlignType() { return alignType; }
    public void setAlignType(String alignType) { this.alignType = alignType; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
