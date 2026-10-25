package com.teamflow.team.domain.dto;

/**
 * 变更成员团队角色请求（{@code PUT /api/team/teams/{teamId}/members/{memberId}/role}）。
 *
 * <p>R6：角色变更需 {@code @OperLog} 审计。角色只用于团队内分工（D-2026-10-26-08 结论二），
 * 不影响系统权限。设为 {@code OWNER} 等价于移交负责人（原 OWNER 降为 MEMBER，团队 owner_id 同步）。</p>
 */
public class TeamMemberRoleRequest {

    private String teamRole;

    public String getTeamRole() { return teamRole; }
    public void setTeamRole(String teamRole) { this.teamRole = teamRole; }
}
