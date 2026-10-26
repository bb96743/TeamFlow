package com.teamflow.project.domain.dto;

/**
 * 项目立项 / 修改请求（L0 2.5.1）。
 *
 * <p><b>R1 项目不得悬空</b>：{@code objectiveId} 与 {@code noObjectiveReason} 至少填一项；
 * 关联目标只存 ID 引用，不复制目标标题/进度字段。</p>
 */
public class ProjectWriteRequest {

    private String projectCode;
    private String projectName;
    private String projectDesc;
    private Long objectiveId;
    private String noObjectiveReason;
    private Long ownerId;
    private Long deptId;
    private String startDate;
    private String endDate;
    private String priority;
    private String remark;

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getProjectDesc() { return projectDesc; }
    public void setProjectDesc(String projectDesc) { this.projectDesc = projectDesc; }
    public Long getObjectiveId() { return objectiveId; }
    public void setObjectiveId(Long objectiveId) { this.objectiveId = objectiveId; }
    public String getNoObjectiveReason() { return noObjectiveReason; }
    public void setNoObjectiveReason(String noObjectiveReason) { this.noObjectiveReason = noObjectiveReason; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
