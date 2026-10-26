package com.teamflow.project.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectTaskLinkRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.service.ProjectTaskLinkService;
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
 * M14 甘特/时间线与项目-任务关联接口（{@code /api/project/projects/{projectsId}/gantt|tasks}）。
 *
 * <p>任务事实经任务模块只读端口实时读取，本模块只维护关联关系；不直连任务表、不复制任务字段。</p>
 */
@RestController
@RequestMapping("/api/project/projects")
public class ProjectGanttController {

    private final ProjectTaskLinkService service;

    public ProjectGanttController(ProjectTaskLinkService service) {
        this.service = service;
    }

    @GetMapping("/{projectId}/gantt")
    @RequiresPermission("project:gantt:list")
    public R<Map<String, Object>> gantt(@PathVariable Long projectId) {
        return R.success(service.gantt(projectId));
    }

    @GetMapping("/{projectId}/tasks")
    @RequiresPermission("project:tasklink:list")
    public R<List<Map<String, Object>>> listLinks(@PathVariable Long projectId) {
        return R.success(service.listLinks(projectId));
    }

    @PostMapping("/{projectId}/tasks")
    @RequiresPermission("project:tasklink:add")
    @OperLog(module = "项目甘特", action = "关联任务")
    public R<ProjectIdResponse> addLink(@PathVariable Long projectId,
                                        @RequestBody ProjectTaskLinkRequest request) {
        return R.success(service.addLink(projectId, request));
    }

    @DeleteMapping("/{projectId}/tasks/{linkId}")
    @RequiresPermission("project:tasklink:remove")
    @OperLog(module = "项目甘特", action = "取消关联任务")
    public R<Void> removeLink(@PathVariable Long projectId, @PathVariable Long linkId) {
        service.removeLink(projectId, linkId);
        return R.success(null);
    }
}
