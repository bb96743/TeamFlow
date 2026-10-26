package com.teamflow.project.domain.dto;

/**
 * 里程碑新增 / 修改请求。
 *
 * <p>{@code krId} 为可空的「关联关键结果」引用：仅存 ID，完成度由上游任务服务按该引用实时统计，
 * 不在本模块表内保存任务或 KR 的任何标题/状态快照（R2/R3）。</p>
 */
public class ProjectMilestoneWriteRequest {

    private String milestoneName;
    private String milestoneDesc;
    private Long krId;
    private String planStartDate;
    private String planEndDate;
    private String actualStartDate;
    private String actualEndDate;
    private String status;
    private Integer sort;
    private String remark;

    public String getMilestoneName() { return milestoneName; }
    public void setMilestoneName(String milestoneName) { this.milestoneName = milestoneName; }
    public String getMilestoneDesc() { return milestoneDesc; }
    public void setMilestoneDesc(String milestoneDesc) { this.milestoneDesc = milestoneDesc; }
    public Long getKrId() { return krId; }
    public void setKrId(Long krId) { this.krId = krId; }
    public String getPlanStartDate() { return planStartDate; }
    public void setPlanStartDate(String planStartDate) { this.planStartDate = planStartDate; }
    public String getPlanEndDate() { return planEndDate; }
    public void setPlanEndDate(String planEndDate) { this.planEndDate = planEndDate; }
    public String getActualStartDate() { return actualStartDate; }
    public void setActualStartDate(String actualStartDate) { this.actualStartDate = actualStartDate; }
    public String getActualEndDate() { return actualEndDate; }
    public void setActualEndDate(String actualEndDate) { this.actualEndDate = actualEndDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
