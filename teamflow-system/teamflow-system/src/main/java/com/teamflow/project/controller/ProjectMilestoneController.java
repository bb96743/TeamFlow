package com.teamflow.project.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectMilestoneWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.service.ProjectMilestoneService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M14 里程碑接口（{@code /api/project/projects/{projectId}/milestones}，需求 2.5.1）。
 *
 * <p>完成度 = 里程碑所关联 KR 的顶层任务完成率，经 M03 服务实时汇总（**接口不接受手填完成度**，R3）；
 * 延期判定口径唯一（R7）。甘特/时间线视图属 M3（2.5.2），本期不做。</p>
 */
@RestController
@RequestMapping("/api/project/projects/{projectId}/milestones")
public class ProjectMilestoneController {

    private final ProjectMilestoneService service;

    public ProjectMilestoneController(ProjectMilestoneService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("project:milestone:list")
    public R<List<Map<String, Object>>> list(@PathVariable Long projectId) {
        return R.success(service.list(projectId));
    }

    @GetMapping("/{milestoneId}")
    @RequiresPermission("project:milestone:list")
    public R<Map<String, Object>> detail(@PathVariable Long projectId, @PathVariable Long milestoneId) {
        return R.success(service.detail(projectId, milestoneId));
    }

    @PostMapping
    @RequiresPermission("project:milestone:add")
    @OperLog(module = "项目里程碑", action = "新增")
    public R<ProjectIdResponse> create(@PathVariable Long projectId,
                                       @RequestBody ProjectMilestoneWriteRequest request) {
        return R.success(service.create(projectId, request));
    }

    @PutMapping("/{milestoneId}")
    @RequiresPermission("project:milestone:edit")
    @OperLog(module = "项目里程碑", action = "修改")
    public R<Map<String, Object>> update(@PathVariable Long projectId, @PathVariable Long milestoneId,
                                         @RequestBody ProjectMilestoneWriteRequest request) {
        return R.success(service.update(projectId, milestoneId, request));
    }

    @DeleteMapping("/{milestoneId}")
    @RequiresPermission("project:milestone:remove")
    @OperLog(module = "项目里程碑", action = "删除")
    public R<Void> delete(@PathVariable Long projectId, @PathVariable Long milestoneId) {
        service.delete(projectId, milestoneId);
        return R.success(null);
    }
}
