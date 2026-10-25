package com.teamflow.team.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.team.domain.dto.TeamWriteRequest;
import com.teamflow.team.domain.vo.TeamIdResponse;
import com.teamflow.team.mapper.TeamMapper;
import com.teamflow.team.mapper.TeamMemberMapper;
import com.teamflow.team.mapper.TeamPlatformMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M04 团队服务：团队 CRUD、解散、负责人设置、团队选项、团队详情（含成员负载汇总）。
 *
 * <p><b>边界（M04 RULE §二）</b>：不建用户/部门表（只读引用 {@code sys_user}/{@code sys_dept}）；
 * 不做任务分配（任务归属 M03）；团队角色不映射系统角色/权限码（`D-2026-10-26-08`）。</p>
 *
 * <p><b>解散（R4）</b>：逻辑删除 + {@code status='dissolved'}，成员行全部保留（历史可追溯）；
 * 按 D-08 结论二，"仅团队 OWNER 可解散"是**团队内部分工校验**，与 {@code team:team:remove} 系统权限叠加生效。</p>
 */
@Service
public class TeamService extends TeamServiceSupport {

    private static final Set<String> EDITABLE_STATUSES = Set.of("active", "archived");

    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final TeamMemberService memberService;

    public TeamService(
            TeamPlatformMapper platformMapper,
            TeamMapper teamMapper,
            TeamMemberMapper memberMapper,
            TeamMemberService memberService
    ) {
        super(platformMapper);
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.memberService = memberService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @DataScope
    public PageResult<Map<String, Object>> list(
            PageQuery query, String keyword, String status, boolean mine) {
        Page page = pageOf(query);
        Long mineUserId = mine ? currentUserId() : null;
        long total = teamMapper.countTeams(
                trimToNull(keyword), trimToNull(status), mine, mineUserId, DataScopeContext.current());
        List<Map<String, Object>> rows = teamMapper.selectTeams(
                trimToNull(keyword), trimToNull(status), mine, mineUserId, DataScopeContext.current(),
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 团队下拉选项（供其他模块引用；已登录即可，无权限码）。 */
    public List<Map<String, Object>> options() {
        return teamMapper.selectOptions();
    }

    @DataScope
    public Map<String, Object> detail(Long teamId) {
        Map<String, Object> team = teamMapper.selectTeam(teamId, DataScopeContext.current());
        if (team == null) {
            throw new BusinessException("团队不存在或无权访问");
        }
        decorate(team);
        List<Map<String, Object>> members = memberService.listForTeam(teamId, false);
        team.put("members", members);
        team.put("loadSummary", memberService.loadSummary(teamId, members));
        return team;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    @Transactional
    public TeamIdResponse create(TeamWriteRequest request) {
        String name = required(request.getTeamName(), "团队名称不能为空");
        String code = trimToNull(request.getTeamCode());
        if (code != null && teamMapper.countByCode(code, null) > 0) {
            throw new BusinessException("团队编码已存在");
        }
        validateDept(request.getDeptId());
        String status = defaultString(request.getStatus(), "active");
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new BusinessException("团队状态只能为 active 或 archived（解散请使用删除接口）");
        }
        Long ownerId = request.getOwnerId();
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("负责人不存在或已停用");
        }
        String operator = currentUsername();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamName", name);
        row.put("teamCode", code);
        row.put("teamDesc", trimToNull(request.getTeamDesc()));
        row.put("deptId", request.getDeptId());
        row.put("ownerId", ownerId);
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        teamMapper.insertTeam(row, operator);
        Long teamId = numberAsLong(row.get("id"));
        if (ownerId != null) {
            // 负责人同时写一条 OWNER 成员行（团队内分工，不触碰系统角色）
            memberService.setOwner(teamId, ownerId);
        }
        return new TeamIdResponse(teamId);
    }

    @Transactional
    public void update(Long teamId, TeamWriteRequest request) {
        Map<String, Object> existing = requireTeam(teamId);
        String code = request.getTeamCode() == null
                ? (existing.get("teamCode") == null ? null : String.valueOf(existing.get("teamCode")))
                : trimToNull(request.getTeamCode());
        if (code != null && teamMapper.countByCode(code, teamId) > 0) {
            throw new BusinessException("团队编码已存在");
        }
        String status = request.getStatus() == null
                ? String.valueOf(existing.get("status")) : request.getStatus();
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new BusinessException("团队状态只能为 active 或 archived（解散请使用删除接口）");
        }
        if (request.getDeptId() != null) {
            validateDept(request.getDeptId());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamName", request.getTeamName() == null
                ? String.valueOf(existing.get("teamName"))
                : required(request.getTeamName(), "团队名称不能为空"));
        row.put("teamCode", code);
        row.put("teamDesc", request.getTeamDesc() == null ? existing.get("teamDesc") : trimToNull(request.getTeamDesc()));
        row.put("deptId", request.getDeptId() == null ? existing.get("deptId") : request.getDeptId());
        row.put("status", status);
        row.put("sort", request.getSort() == null ? numberAsLong(existing.get("sort")).intValue() : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        teamMapper.updateTeam(teamId, row, currentUsername());
    }

    /** 设置团队负责人（团队内 OWNER 分工，不涉及系统角色）。 */
    @Transactional
    public Long setOwner(Long teamId, Long userId) {
        return memberService.setOwner(teamId, userId);
    }

    /**
     * 解散团队（R4：逻辑删除 + 保留成员历史）。
     *
     * <p>团队内校验：仅 OWNER（{@code owner_id} 或当前在册 OWNER 角色）可解散；团队无负责人时放行，
     * 避免"无人负责即无法解散"的死锁。系统权限 {@code team:team:remove} 仍由 Controller 注解把关。</p>
     */
    @Transactional
    public void dissolve(Long teamId) {
        Map<String, Object> team = requireTeam(teamId);
        String operator = currentUsername();
        Long me = currentUserId();
        Long ownerId = numberAsLong(team.get("ownerId"));
        Map<String, Object> activeOwner = memberMapper.selectActiveOwner(teamId);
        Long activeOwnerUserId = activeOwner == null ? null : numberAsLong(activeOwner.get("userId"));
        boolean isOwner = (ownerId != null && ownerId.equals(me))
                || (activeOwnerUserId != null && activeOwnerUserId.equals(me));
        boolean noOwner = ownerId == null && activeOwnerUserId == null;
        if (!isOwner && !noOwner) {
            throw new BusinessException("仅团队 OWNER 可解散团队（团队内角色，与系统权限无关）");
        }
        int updated = teamMapper.dissolveTeam(teamId, operator);
        if (updated == 0) {
            throw new BusinessException("团队不存在或已解散");
        }
        // 成员行不动：保留历史归属与 join/leave 记录（R3/R4）
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    public Map<String, Object> requireTeam(Long teamId) {
        if (teamId == null) {
            throw new BusinessException("团队 ID 不能为空");
        }
        Map<String, Object> team = teamMapper.selectTeamById(teamId);
        if (team == null) {
            throw new BusinessException("团队不存在或已解散");
        }
        return team;
    }

    private void validateDept(Long deptId) {
        if (deptId != null && platformMapper.countDept(deptId) == 0) {
            throw new BusinessException("归属部门不存在");
        }
    }

    private void decorate(Map<String, Object> row) {
        String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
        row.put("statusLabel", teamStatusLabel(status));
    }
}
