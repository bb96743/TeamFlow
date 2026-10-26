package com.teamflow.project.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.project.domain.dto.ProjectRetroArchiveRequest;
import com.teamflow.project.service.ProjectRetroService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M14 项目复盘接口（{@code /api/project/projects/{projectId}/retro}，需求 2.5.4）。
 *
 * <p>复盘正文归档到 M07 知识模块，本模块只返回/保存 {@code retroPageId}；接口不接收也不存储正文副本。</p>
 */
@RestController
@RequestMapping("/api/project/projects")
public class ProjectRetroController {

    private final ProjectRetroService service;

    public ProjectRetroController(ProjectRetroService service) {
        this.service = service;
    }

    @GetMapping("/{projectId}/retro")
    @RequiresPermission("project:retro:list")
    public R<Map<String, Object>> retro(@PathVariable Long projectId) {
        return R.success(service.retro(projectId));
    }

    @GetMapping("/{projectId}/retro/template")
    @RequiresPermission("project:retro:list")
    public R<Map<String, Object>> template(@PathVariable Long projectId) {
        return R.success(service.template(projectId));
    }

    @PostMapping("/{projectId}/retro/archive")
    @RequiresPermission("project:retro:archive")
    @OperLog(module = "项目复盘", action = "归档复盘")
    public R<Map<String, Object>> archive(@PathVariable Long projectId,
                                          @RequestBody ProjectRetroArchiveRequest request) {
        return R.success(service.archive(projectId, request));
    }
}
