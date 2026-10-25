package com.teamflow.perf.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfAppealHandleRequest;
import com.teamflow.perf.domain.dto.PerfAppealWriteRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.service.PerfAppealService;
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
 * M05 绩效申诉接口（{@code /api/perf/appeals}）：锁定后修改结果的唯一通道（R4/R5）。
 */
@RestController
@RequestMapping("/api/perf/appeals")
public class PerfAppealController {

    private final PerfAppealService service;

    public PerfAppealController(PerfAppealService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("perf:result:appeal")
    @OperLog(module = "绩效申诉", action = "提交申诉")
    public R<PerfIdResponse> create(@RequestBody PerfAppealWriteRequest request) {
        return R.success(service.create(request));
    }

    @GetMapping
    @RequiresPermission("perf:result:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long cycleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        return R.success(service.list(cycleId, status, new PageQuery(pageNum, pageSize, null, null)));
    }

    @GetMapping("/{id}")
    @RequiresPermission("perf:result:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PutMapping("/{id}/handle")
    @RequiresPermission("perf:result:appealHandle")
    @OperLog(module = "绩效申诉", action = "处理申诉")
    public R<Map<String, Object>> handle(@PathVariable Long id, @RequestBody PerfAppealHandleRequest request) {
        return R.success(service.handle(id, request));
    }
}
