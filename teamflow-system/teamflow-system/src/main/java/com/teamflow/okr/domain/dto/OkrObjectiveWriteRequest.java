package com.teamflow.okr.domain.dto;

import java.math.BigDecimal;

/**
 * 目标（Objective）新增/修改请求。
 *
 * <p>层级 level：company 公司 / dept 部门 / personal 个人；dept 层级必须填写 deptId。
 * 承接人 ownerId 为空时只允许保存为 draft（M02 RULE R2）。</p>
 */
public class OkrObjectiveWriteRequest {

    private Long periodId;
    private Long parentId;
    private String objectiveTitle;
    private String objectiveDesc;
    private String level;
    private Long deptId;
    private Long ownerId;
    private BigDecimal weight;
    private String status;
    private Integer sort;
    private String remark;

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getObjectiveTitle() { return objectiveTitle; }
    public void setObjectiveTitle(String objectiveTitle) { this.objectiveTitle = objectiveTitle; }
    public String getObjectiveDesc() { return objectiveDesc; }
    public void setObjectiveDesc(String objectiveDesc) { this.objectiveDesc = objectiveDesc; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
