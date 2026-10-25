package com.teamflow.perf.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfCycleIdRequest;
import com.teamflow.perf.service.PerfResultService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M05 绩效结果接口（{@code /api/perf/results}）：结果查询、下钻追溯、归档、锁定。
 *
 * <p>R5：归档（archived）后只读；锁定（locked）不可逆，锁定后修改只能走 {@code perf_appeal}。</p>
 */
@RestController
@RequestMapping("/api/perf/results")
public class PerfResultController {

    private final PerfResultService service;

    public PerfResultController(PerfResultService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("perf:result:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long cycleId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        return R.success(service.list(cycleId, new PageQuery(pageNum, pageSize, null, null)));
    }

    @GetMapping("/{id}")
    @RequiresPermission("perf:result:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    /** 可归集指标下钻：KR 清单 / 任务清单 / M02 人工校准量与原因。 */
    @GetMapping("/{id}/trace")
    @RequiresPermission("perf:result:list")
    public R<Map<String, Object>> trace(@PathVariable Long id) {
        return R.success(service.trace(id));
    }

    @PostMapping("/archive")
    @RequiresPermission("perf:result:archive")
    @OperLog(module = "绩效结果", action = "归档")
    public R<Map<String, Object>> archive(@RequestBody PerfCycleIdRequest request) {
        return R.success(service.archive(request));
    }

    @PostMapping("/lock")
    @RequiresPermission("perf:result:lock")
    @OperLog(module = "绩效结果", action = "锁定")
    public R<Map<String, Object>> lock(@RequestBody PerfCycleIdRequest request) {
        return R.success(service.lock(request));
    }
}
