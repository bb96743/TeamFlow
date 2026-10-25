package com.teamflow.team.domain.dto;

/**
 * 团队新增 / 编辑请求（{@code /api/team/teams}）。
 *
 * <p>{@code ownerId} 为团队负责人（只读引用 {@code sys_user}）；新增时若提供会自动写一条 {@code OWNER} 成员行。
 * {@code deptId} 只读引用 {@code sys_dept}，不复制部门名。{@code status} 仅允许 active / archived；
 * 解散走删除接口（逻辑删除 + status=dissolved）。</p>
 */
public class TeamWriteRequest {

    private String teamName;
    private String teamCode;
    private String teamDesc;
    private Long deptId;
    private Long ownerId;
    private String status;
    private Integer sort;
    private String remark;

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getTeamCode() { return teamCode; }
    public void setTeamCode(String teamCode) { this.teamCode = teamCode; }
    public String getTeamDesc() { return teamDesc; }
    public void setTeamDesc(String teamDesc) { this.teamDesc = teamDesc; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
