package com.teamflow.perf.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfTemplateWriteRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.service.PerfTemplateService;
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
 * M05 指标模板接口（{@code /api/perf/templates}）：模板与指标项 CRUD、R6 权重校验。
 */
@RestController
@RequestMapping("/api/perf/templates")
public class PerfTemplateController {

    private final PerfTemplateService service;

    public PerfTemplateController(PerfTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("perf:template:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("perf:template:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("perf:template:add")
    @OperLog(module = "指标模板", action = "新增")
    public R<PerfIdResponse> create(@RequestBody PerfTemplateWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("perf:template:edit")
    @OperLog(module = "指标模板", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody PerfTemplateWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    /** 删除同样走编辑权限（M05 RULE §五 未单列 remove 权限码，避免权限码只增不复用）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("perf:template:edit")
    @OperLog(module = "指标模板", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
