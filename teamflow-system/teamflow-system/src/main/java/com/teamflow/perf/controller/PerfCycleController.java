package com.teamflow.perf.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfCycleWriteRequest;
import com.teamflow.perf.domain.dto.PerfStatusRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.service.PerfCycleService;
import com.teamflow.perf.status.PerfCycleStatusMachine;
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
 * M05 绩效周期接口（{@code /api/perf/cycles}）：周期 CRUD 与状态机流转（R5：锁定不可逆）。
 */
@RestController
@RequestMapping("/api/perf/cycles")
public class PerfCycleController {

    private final PerfCycleService service;

    public PerfCycleController(PerfCycleService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("perf:cycle:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, status));
    }

    @GetMapping("/status-machine")
    @RequiresPermission("perf:cycle:list")
    public R<Map<String, Object>> statusMachine() {
        return R.success(PerfCycleStatusMachine.definition());
    }

    @GetMapping("/{id}")
    @RequiresPermission("perf:cycle:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("perf:cycle:add")
    @OperLog(module = "绩效周期", action = "新增")
    public R<PerfIdResponse> create(@RequestBody PerfCycleWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("perf:cycle:edit")
    @OperLog(module = "绩效周期", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody PerfCycleWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @PutMapping("/{id}/status")
    @RequiresPermission("perf:cycle:status")
    @OperLog(module = "绩效周期", action = "状态流转")
    public R<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody PerfStatusRequest request) {
        return R.success(service.updateStatus(id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("perf:cycle:remove")
    @OperLog(module = "绩效周期", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
