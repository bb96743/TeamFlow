package com.teamflow.project.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectMemberAddRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.service.ProjectMemberService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M14 项目成员接口（{@code /api/project/projects/{projectId}/members}）。
 *
 * <p>负责人由项目主数据 {@code owner_id} 唯一确定（随立项/编辑自动同步成员行）；
 * 成员接口只添加参与人/观察者，移出只写 {@code leave_time} 保留历史。</p>
 */
@RestController
@RequestMapping("/api/project/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMemberService service;

    public ProjectMemberController(ProjectMemberService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("project:member:list")
    public R<List<Map<String, Object>>> list(@PathVariable Long projectId) {
        return R.success(service.list(projectId));
    }

    @PostMapping
    @RequiresPermission("project:member:add")
    @OperLog(module = "项目成员", action = "添加")
    public R<ProjectIdResponse> add(@PathVariable Long projectId,
                                    @RequestBody ProjectMemberAddRequest request) {
        return R.success(service.add(projectId, request));
    }

    @DeleteMapping("/{memberId}")
    @RequiresPermission("project:member:remove")
    @OperLog(module = "项目成员", action = "移出")
    public R<Void> remove(@PathVariable Long projectId, @PathVariable Long memberId) {
        service.remove(projectId, memberId);
        return R.success(null);
    }
}
