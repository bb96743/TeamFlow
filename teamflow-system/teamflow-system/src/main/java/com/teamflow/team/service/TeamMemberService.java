package com.teamflow.team.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.task.service.TaskPerfQueryService;
import com.teamflow.team.domain.dto.TeamMemberAddRequest;
import com.teamflow.team.domain.dto.TeamMemberRoleRequest;
import com.teamflow.team.mapper.TeamMapper;
import com.teamflow.team.mapper.TeamMemberMapper;
import com.teamflow.team.mapper.TeamPlatformMapper;
import com.teamflow.team.role.TeamRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M04 团队成员服务：加入 / 移出 / 角色变更 / 移交负责人 / 成员负载视图。
 *
 * <p><b>跨模块边界（D-2026-10-26-08）</b>：成员负载（任务总数 / 进行中数）**只能**经 M03 的只读端口
 * {@link TaskPerfQueryService#memberTaskLoads(List)} 获取，本模块**不直连 {@code task_*}**；
 * 成员展示信息（昵称/部门名）按 {@code user_id} 实时关联 {@code sys_user}/{@code sys_dept}，**不落库缓存**。</p>
 *
 * <p><b>团队角色（D-08 结论二）</b>：{@code team_role} 只用于团队内分工与操作校验，
 * **不写 {@code sys_user_role}、不映射权限码**；同一团队只允许一个在册 {@code OWNER}（移交时自动降级原 OWNER）。</p>
 */
@Service
public class TeamMemberService extends TeamServiceSupport {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final TaskPerfQueryService taskPerfQueryService;

    public TeamMemberService(
            TeamPlatformMapper platformMapper,
            TeamMapper teamMapper,
            TeamMemberMapper memberMapper,
            TaskPerfQueryService taskPerfQueryService
    ) {
        super(platformMapper);
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.taskPerfQueryService = taskPerfQueryService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 成员列表（可见性校验 + 成员负载）。{@code includeLeft=true} 时包含已移出历史行。 */
    @DataScope
    public List<Map<String, Object>> listVisible(Long teamId, boolean includeLeft) {
        Map<String, Object> team = teamMapper.selectTeam(teamId, DataScopeContext.current());
        if (team == null) {
            throw new BusinessException("团队不存在或无权访问");
        }
        return buildRows(numberAsLong(team.get("id")), includeLeft);
    }

    /** 内部成员列表（调用方已完成团队可见性/存在性校验，供团队详情复用）。 */
    public List<Map<String, Object>> listForTeam(Long teamId, boolean includeLeft) {
        return buildRows(teamId, includeLeft);
    }

    /** 在册成员的用户 ID 集合（负载批量取数与详情汇总用）。 */
    public List<Long> activeMemberUserIds(Long teamId) {
        return memberMapper.selectActiveMemberUserIds(teamId);
    }

    /** 团队在册成员数。 */
    public long activeMemberCount(Long teamId) {
        return memberMapper.countMembers(teamId, false);
    }

    /** 团队成员负载汇总：在册人数 + 任务总数 + 进行中数（数据来自 M03 只读端口）。 */
    public Map<String, Object> loadSummary(Long teamId, List<Map<String, Object>> memberRows) {
        long taskTotal = 0;
        long taskInProgress = 0;
        for (Map<String, Object> row : memberRows) {
            taskTotal += numberAsLong(row.get("taskTotal")) == null ? 0 : numberAsLong(row.get("taskTotal"));
            taskInProgress += numberAsLong(row.get("taskInProgress")) == null
                    ? 0 : numberAsLong(row.get("taskInProgress"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("memberCount", memberRows.size());
        summary.put("taskTotal", taskTotal);
        summary.put("taskInProgress", taskInProgress);
        return summary;
    }

    // ------------------------------------------------------------------
    // 成员写操作
    // ------------------------------------------------------------------

    /**
     * 添加成员（R6 审计由 Controller 的 {@code @OperLog} 承担）。
     *
     * <p><b>L-2（V18 起）</b>：已移出成员再次添加时**新建一行**（而不是复活原行），
     * 上一段任职记录（{@code leave_time} 有值的历史行）完整保留，满足 R3「保留历史便于绩效追溯」。
     * 在册成员重复加入仍拒绝（{@code uk_team_member_active} 仅对在册行唯一）。</p>
     */
    @Transactional
    public Long add(Long teamId, TeamMemberAddRequest request) {
        Map<String, Object> team = requireTeam(teamId);
        Long userId = request == null ? null : request.getUserId();
        if (userId == null) {
            throw new BusinessException("成员用户 ID 不能为空");
        }
        if (platformMapper.countActiveUser(userId) == 0) {
            throw new BusinessException("用户不存在或已停用");
        }
        TeamRole role = TeamRole.of(request.getTeamRole());
        String operator = currentUsername();
        Map<String, Object> existing = memberMapper.selectByTeamAndUser(teamId, userId);
        Long memberId;
        if (existing == null) {
            memberId = insertMember(teamId, userId, role.name(), trimToNull(request.getRemark()), operator);
        } else if (existing.get("leaveTime") == null) {
            throw new BusinessException("该用户已在团队中（在册成员），不能重复加入");
        } else {
            // L-2：重新加入新建一行，保留上一段任职记录（不再 revive 覆盖）
            memberId = insertMember(teamId, userId, role.name(), trimToNull(request.getRemark()), operator);
        }
        if (role == TeamRole.OWNER) {
            applyOwnership(teamId, memberId, userId, operator);
        }
        return memberId;
    }

    /** 变更团队内角色（R6 需 @OperLog）：设为 OWNER 等价于移交负责人。 */
    @Transactional
    public void changeRole(Long teamId, Long memberId, TeamMemberRoleRequest request) {
        Map<String, Object> team = requireTeam(teamId);
        Map<String, Object> member = requireMember(teamId, memberId);
        if (member.get("leaveTime") != null) {
            throw new BusinessException("该成员已移出团队，不能变更角色（请先重新加入）");
        }
        TeamRole role = TeamRole.of(request == null ? null : request.getTeamRole());
        String operator = currentUsername();
        Long userId = numberAsLong(member.get("userId"));
        memberMapper.updateMemberRole(memberId, role.name(), operator);
        if (role == TeamRole.OWNER) {
            applyOwnership(teamId, memberId, userId, operator);
        } else if (TeamRole.OWNER.name().equals(member.get("teamRole"))) {
            // 原负责人被降级：团队负责人置空，等待重新指定（不静默转移给他人）
            teamMapper.updateOwner(numberAsLong(team.get("id")), null, operator);
        }
    }

    /** 移出成员（R3：只写 leave_time，不删行）。 */
    @Transactional
    public void leave(Long teamId, Long memberId) {
        Map<String, Object> team = requireTeam(teamId);
        Map<String, Object> member = requireMember(teamId, memberId);
        if (member.get("leaveTime") != null) {
            throw new BusinessException("该成员已移出团队，无需重复操作");
        }
        String operator = currentUsername();
        if (TeamRole.OWNER.name().equals(member.get("teamRole"))) {
            teamMapper.updateOwner(numberAsLong(team.get("id")), null, operator);
        }
        int updated = memberMapper.leaveMember(memberId, operator);
        if (updated == 0) {
            throw new BusinessException("成员状态已变化，请刷新后重试");
        }
    }

    /** 设置负责人（PUT /teams/{id}/owner）：非成员会先自动加入为 OWNER，原 OWNER 自动降为 MEMBER。 */
    @Transactional
    public Long setOwner(Long teamId, Long userId) {
        requireTeam(teamId);
        if (userId == null) {
            throw new BusinessException("负责人用户 ID 不能为空");
        }
        if (platformMapper.countActiveUser(userId) == 0) {
            throw new BusinessException("负责人不存在或已停用");
        }
        String operator = currentUsername();
        Map<String, Object> existing = memberMapper.selectByTeamAndUser(teamId, userId);
        Long memberId;
        if (existing == null) {
            memberId = insertMember(teamId, userId, TeamRole.OWNER.name(), "设置负责人时自动加入团队", operator);
        } else if (existing.get("leaveTime") != null) {
            // L-2：重新加入新建一行（保留上一段任职记录）
            memberId = insertMember(teamId, userId, TeamRole.OWNER.name(), "重新加入并设为负责人", operator);
        } else {
            memberId = numberAsLong(existing.get("id"));
            memberMapper.updateMemberRole(memberId, TeamRole.OWNER.name(), operator);
        }
        applyOwnership(teamId, memberId, userId, operator);
        return memberId;
    }

    /** 新增一条成员记录并返回主键（V18 后重新加入也走这里，历史行保留）。 */
    private Long insertMember(Long teamId, Long userId, String teamRole, String remark, String operator) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamId", teamId);
        row.put("userId", userId);
        row.put("teamRole", teamRole);
        row.put("remark", remark);
        memberMapper.insertMember(row, operator);
        return numberAsLong(row.get("id"));
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 移交负责人：降级其他在册 OWNER + 同步 team_team.owner_id（**不触碰 sys_user_role**）。 */
    private void applyOwnership(Long teamId, Long memberId, Long userId, String operator) {
        memberMapper.demoteOtherOwners(teamId, memberId, operator);
        memberMapper.updateMemberRole(memberId, TeamRole.OWNER.name(), operator);
        teamMapper.updateOwner(teamId, userId, operator);
    }

    private List<Map<String, Object>> buildRows(Long teamId, boolean includeLeft) {
        List<Map<String, Object>> rows = memberMapper.selectMembers(teamId, includeLeft);
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> userIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            userIds.add(numberAsLong(row.get("userId")));
        }
        Map<Long, Map<String, Object>> briefs = new LinkedHashMap<>();
        for (Map<String, Object> brief : platformMapper.selectUserBriefs(userIds.stream().distinct().toList())) {
            briefs.put(numberAsLong(brief.get("userId")), brief);
        }
        Map<Long, Map<String, Object>> loads = new LinkedHashMap<>();
        for (Map<String, Object> load : taskPerfQueryService.memberTaskLoads(userIds)) {
            loads.put(numberAsLong(load.get("assigneeId")), load);
        }
        for (Map<String, Object> row : rows) {
            Long userId = numberAsLong(row.get("userId"));
            Map<String, Object> brief = briefs.get(userId);
            row.put("username", brief == null ? null : brief.get("username"));
            row.put("nickname", brief == null ? null : brief.get("nickname"));
            row.put("deptId", brief == null ? null : brief.get("deptId"));
            row.put("deptName", brief == null ? null : brief.get("deptName"));
            String role = row.get("teamRole") == null ? null : String.valueOf(row.get("teamRole"));
            row.put("teamRoleLabel", TeamRole.of(role).label());
            Map<String, Object> load = loads.get(userId);
            row.put("taskTotal", load == null ? 0L : numberAsLong(load.get("taskTotal")));
            row.put("taskInProgress", load == null ? 0L : numberAsLong(load.get("taskInProgress")));
            row.put("active", row.get("leaveTime") == null);
        }
        return rows;
    }

    private Map<String, Object> requireTeam(Long teamId) {
        if (teamId == null) {
            throw new BusinessException("团队 ID 不能为空");
        }
        Map<String, Object> team = teamMapper.selectTeamById(teamId);
        if (team == null) {
            throw new BusinessException("团队不存在或已解散");
        }
        return team;
    }

    private Map<String, Object> requireMember(Long teamId, Long memberId) {
        Map<String, Object> member = memberMapper.selectMemberById(memberId);
        if (member == null || !teamId.equals(numberAsLong(member.get("teamId")))) {
            throw new BusinessException("团队成员不存在或不属于该团队");
        }
        return member;
    }
}
