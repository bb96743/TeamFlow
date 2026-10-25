package com.teamflow.team.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.team.domain.dto.TeamMemberAddRequest;
import com.teamflow.team.domain.dto.TeamMemberRoleRequest;
import com.teamflow.team.domain.vo.TeamIdResponse;
import com.teamflow.team.service.TeamMemberService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M04 团队成员接口（{@code /api/team/teams/{teamId}/members}）。
 *
 * <p>R3：移出成员只写 {@code leave_time} 不删行；{@code includeLeft=true} 可查历史行。
 * R6：加入/移出/角色变更均加 {@code @OperLog}。
 * 团队角色仅团队内分工，<b>绝不写 {@code sys_user_role}</b>（D-2026-10-26-08 结论二）。</p>
 */
@RestController
@RequestMapping("/api/team/teams/{teamId}/members")
public class TeamMemberController {

    private final TeamMemberService memberService;

    public TeamMemberController(TeamMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @RequiresPermission("team:member:list")
    public R<List<Map<String, Object>>> list(
            @PathVariable Long teamId,
            @RequestParam(required = false, defaultValue = "false") boolean includeLeft
    ) {
        return R.success(memberService.listVisible(teamId, includeLeft));
    }

    @PostMapping
    @RequiresPermission("team:member:add")
    @OperLog(module = "团队管理", action = "添加成员")
    public R<TeamIdResponse> add(@PathVariable Long teamId, @RequestBody TeamMemberAddRequest request) {
        return R.success(new TeamIdResponse(memberService.add(teamId, request)));
    }

    @PutMapping("/{memberId}/role")
    @RequiresPermission("team:member:role")
    @OperLog(module = "团队管理", action = "变更成员角色")
    public R<Void> changeRole(
            @PathVariable Long teamId,
            @PathVariable Long memberId,
            @RequestBody TeamMemberRoleRequest request
    ) {
        memberService.changeRole(teamId, memberId, request);
        return R.success(null);
    }

    @DeleteMapping("/{memberId}")
    @RequiresPermission("team:member:remove")
    @OperLog(module = "团队管理", action = "移出成员")
    public R<Void> leave(@PathVariable Long teamId, @PathVariable Long memberId) {
        memberService.leave(teamId, memberId);
        return R.success(null);
    }
}
