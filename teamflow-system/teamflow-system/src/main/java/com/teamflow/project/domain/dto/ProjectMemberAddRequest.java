package com.teamflow.project.domain.dto;

/**
 * 添加项目成员请求（负责人/参与人/观察者；项目内角色不映射系统权限）。
 */
public class ProjectMemberAddRequest {

    private Long userId;
    private String memberRole;
    private String remark;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getMemberRole() { return memberRole; }
    public void setMemberRole(String memberRole) { this.memberRole = memberRole; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
