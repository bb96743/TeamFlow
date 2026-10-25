package com.teamflow.team.domain.dto;

/**
 * 添加成员请求（{@code POST /api/team/teams/{teamId}/members}）。
 *
 * <p>只存 {@code user_id} + 团队内属性（R1：成员是引用不是副本）；已移出（{@code leave_time} 有值）的成员
 * 再次添加时复活同一行（重置 {@code join_time}、清空 {@code leave_time}）。</p>
 */
public class TeamMemberAddRequest {

    private Long userId;
    private String teamRole;
    private String remark;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTeamRole() { return teamRole; }
    public void setTeamRole(String teamRole) { this.teamRole = teamRole; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
