package com.teamflow.okr.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.okr.domain.dto.OkrPeriodStatusRequest;
import com.teamflow.okr.domain.dto.OkrPeriodWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.service.OkrPeriodService;
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
 * M02 目标周期接口（{@code /api/okr/periods}），需求 1.1.1。
 *
 * <p>每个接口均有 {@code @RequiresPermission}；写接口加 {@code @OperLog}；
 * 列表与详情在 Service 层显式加 {@code @DataScope}（G-2 未修复前防止静默越权）。</p>
 */
@RestController
@RequestMapping("/api/okr/periods")
public class OkrPeriodController {

    private final OkrPeriodService service;

    public OkrPeriodController(OkrPeriodService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("okr:period:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, keyword, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("okr:period:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("okr:period:add")
    @OperLog(module = "目标周期", action = "新增")
    public R<OkrIdResponse> create(@RequestBody OkrPeriodWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("okr:period:edit")
    @OperLog(module = "目标周期", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody OkrPeriodWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @PutMapping("/{id}/status")
    @RequiresPermission("okr:period:edit")
    @OperLog(module = "目标周期", action = "状态变更")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody OkrPeriodStatusRequest request) {
        service.updateStatus(id, request.getStatus());
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("okr:period:remove")
    @OperLog(module = "目标周期", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
