package com.teamflow.team.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.team.domain.dto.TeamOwnerRequest;
import com.teamflow.team.domain.dto.TeamWriteRequest;
import com.teamflow.team.domain.vo.TeamIdResponse;
import com.teamflow.team.service.TeamService;
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
 * M04 团队接口（{@code /api/team/teams}）。
 *
 * <p>横切约定：写接口加 {@code @OperLog}；团队列表/详情加 {@code @DataScope}（G-2 默认拒绝）；
 * 团队角色（OWNER/MEMBER/VIEWER）只用于团队内分工，**不影响任何系统权限**（D-2026-10-26-08）。</p>
 */
@RestController
@RequestMapping("/api/team/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @RequiresPermission("team:team:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine
    ) {
        return R.success(teamService.list(query, keyword, status, mine));
    }

    /**
     * 团队下拉选项：**已登录即可**（无 {@code team:*} 权限码），供其他模块引用团队维度。
     * 只返回启用团队的最小字段（id / teamName / teamCode）。
     */
    @GetMapping("/options")
    public R<List<Map<String, Object>>> options() {
        return R.success(teamService.options());
    }

    @GetMapping("/{id}")
    @RequiresPermission("team:team:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(teamService.detail(id));
    }

    @PostMapping
    @RequiresPermission("team:team:add")
    @OperLog(module = "团队管理", action = "新增团队")
    public R<TeamIdResponse> create(@RequestBody TeamWriteRequest request) {
        return R.success(teamService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("team:team:edit")
    @OperLog(module = "团队管理", action = "修改团队")
    public R<Void> update(@PathVariable Long id, @RequestBody TeamWriteRequest request) {
        teamService.update(id, request);
        return R.success(null);
    }

    /** 设置负责人：团队内 OWNER 分工（不写系统角色），需团队编辑权限。 */
    @PutMapping("/{id}/owner")
    @RequiresPermission("team:team:edit")
    @OperLog(module = "团队管理", action = "设置负责人")
    public R<TeamIdResponse> setOwner(@PathVariable Long id, @RequestBody TeamOwnerRequest request) {
        Long memberId = teamService.setOwner(id, request == null ? null : request.getUserId());
        return R.success(new TeamIdResponse(memberId));
    }

    /** 解散团队：逻辑删除 + 保留成员历史；团队内要求 OWNER，系统侧要求 {@code team:team:remove}。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("team:team:remove")
    @OperLog(module = "团队管理", action = "解散团队")
    public R<Void> dissolve(@PathVariable Long id) {
        teamService.dissolve(id);
        return R.success(null);
    }
}
