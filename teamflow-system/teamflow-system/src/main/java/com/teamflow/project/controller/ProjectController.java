package com.teamflow.project.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectStatusRequest;
import com.teamflow.project.domain.dto.ProjectWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.service.ProjectService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M14 项目接口（{@code /api/project/projects}，需求 2.5.1 项目立项）。
 *
 * <p>项目只存 {@code objective_id} 引用；详情中的目标标题/进度经 M02 服务实时读取，
 * 里程碑完成度经 M03 服务实时汇总（均不落本模块快照）。</p>
 */
@RestController
@RequestMapping("/api/project/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("project:project:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long deptId
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, status, deptId));
    }

    @GetMapping("/status-machine")
    @RequiresPermission("project:project:list")
    public R<Map<String, Object>> statusMachine() {
        return R.success(service.statusMachine());
    }

    @GetMapping("/{id}")
    @RequiresPermission("project:project:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("project:project:add")
    @OperLog(module = "项目立项", action = "新增")
    public R<ProjectIdResponse> create(@RequestBody ProjectWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("project:project:edit")
    @OperLog(module = "项目立项", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody ProjectWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("project:project:remove")
    @OperLog(module = "项目立项", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    /** 状态流转：draft → active → closed → archived（archived 终态，不可逆）。 */
    @PutMapping("/{id}/status")
    @RequiresPermission("project:project:status")
    @OperLog(module = "项目立项", action = "状态流转")
    public R<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody ProjectStatusRequest request) {
        return R.success(service.updateStatus(id, request.getStatus()));
    }
}
