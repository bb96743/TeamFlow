package com.teamflow.team.domain.dto;

/**
 * 设置团队负责人请求（{@code PUT /api/team/teams/{teamId}/owner}）。
 *
 * <p>负责人 = 团队内 {@code OWNER} 角色 + {@code team_team.owner_id} 同步（只用于团队内分工，
 * **不写 {@code sys_user_role}**，D-2026-10-26-08 结论二）。设置后原 OWNER 降为 MEMBER。</p>
 */
public class TeamOwnerRequest {

    private Long userId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
