package com.teamflow.project.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectRiskStatusRequest;
import com.teamflow.project.domain.dto.ProjectRiskWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.service.ProjectRiskService;
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
 * M14 项目风险登记接口（{@code /api/project/projects/{projectId}/risks}，需求 2.5.3）。
 *
 * <p>状态流转由集中状态机校验，每次变更写风险状态留痕；非法流转返回 400。</p>
 */
@RestController
@RequestMapping("/api/project/projects")
public class ProjectRiskController {

    private final ProjectRiskService service;

    public ProjectRiskController(ProjectRiskService service) {
        this.service = service;
    }

    /** 风险状态机定义（唯一来源导出，供前端渲染可流转状态）。 */
    @GetMapping("/risk-status-machine")
    @RequiresPermission("project:risk:list")
    public R<Map<String, Object>> statusMachine() {
        return R.success(service.statusMachine());
    }

    @GetMapping("/{projectId}/risks")
    @RequiresPermission("project:risk:list")
    public R<PageResult<Map<String, Object>>> list(
            @PathVariable Long projectId,
            PageQuery query,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(projectId, query, status));
    }

    @GetMapping("/{projectId}/risks/{riskId}")
    @RequiresPermission("project:risk:list")
    public R<Map<String, Object>> detail(@PathVariable Long projectId, @PathVariable Long riskId) {
        return R.success(service.detail(projectId, riskId));
    }

    @PostMapping("/{projectId}/risks")
    @RequiresPermission("project:risk:add")
    @OperLog(module = "项目风险", action = "登记风险")
    public R<ProjectIdResponse> create(@PathVariable Long projectId,
                                       @RequestBody ProjectRiskWriteRequest request) {
        return R.success(service.create(projectId, request));
    }

    @PutMapping("/{projectId}/risks/{riskId}")
    @RequiresPermission("project:risk:edit")
    @OperLog(module = "项目风险", action = "编辑风险")
    public R<Void> update(@PathVariable Long projectId, @PathVariable Long riskId,
                          @RequestBody ProjectRiskWriteRequest request) {
        service.update(projectId, riskId, request);
        return R.success(null);
    }

    @PutMapping("/{projectId}/risks/{riskId}/status")
    @RequiresPermission("project:risk:status")
    @OperLog(module = "项目风险", action = "风险状态流转")
    public R<Map<String, Object>> changeStatus(@PathVariable Long projectId, @PathVariable Long riskId,
                                               @RequestBody ProjectRiskStatusRequest request) {
        return R.success(service.changeStatus(projectId, riskId, request));
    }

    @GetMapping("/{projectId}/risks/{riskId}/logs")
    @RequiresPermission("project:risk:list")
    public R<List<Map<String, Object>>> logs(@PathVariable Long projectId, @PathVariable Long riskId) {
        return R.success(service.logs(projectId, riskId));
    }

    @DeleteMapping("/{projectId}/risks/{riskId}")
    @RequiresPermission("project:risk:remove")
    @OperLog(module = "项目风险", action = "删除风险")
    public R<Void> delete(@PathVariable Long projectId, @PathVariable Long riskId) {
        service.delete(projectId, riskId);
        return R.success(null);
    }
}
