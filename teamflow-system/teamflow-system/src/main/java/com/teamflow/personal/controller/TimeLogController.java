package com.teamflow.personal.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.personal.domain.dto.TimeLogWriteRequest;
import com.teamflow.personal.domain.vo.PersonalIdResponse;
import com.teamflow.personal.service.TimeLogService;
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
 * M10 工时接口（{@code /api/personal/time-logs}），需求 2.3.2。
 *
 * <p>R6：工时以任务为粒度，{@code taskId} 必填；按任务汇总走 {@code /summary}（实时聚合，不落汇总表）。</p>
 */
@RestController
@RequestMapping("/api/personal/time-logs")
public class TimeLogController {

    private final TimeLogService timeLogService;

    public TimeLogController(TimeLogService timeLogService) {
        this.timeLogService = timeLogService;
    }

    @GetMapping
    @RequiresPermission("personal:time:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate
    ) {
        return R.success(timeLogService.list(query, taskId, fromDate, toDate));
    }

    /** 按任务汇总工时（taskId / totalMinutes / logCount）。 */
    @GetMapping("/summary")
    @RequiresPermission("personal:time:list")
    public R<List<Map<String, Object>>> summary(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate
    ) {
        return R.success(timeLogService.summary(fromDate, toDate));
    }

    @PostMapping
    @RequiresPermission("personal:time:add")
    @OperLog(module = "工时记录", action = "填报")
    public R<PersonalIdResponse> create(@RequestBody TimeLogWriteRequest request) {
        return R.success(new PersonalIdResponse(timeLogService.create(request)));
    }

    @PutMapping("/{id}")
    @RequiresPermission("personal:time:edit")
    @OperLog(module = "工时记录", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody TimeLogWriteRequest request) {
        timeLogService.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("personal:time:edit")
    @OperLog(module = "工时记录", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        timeLogService.delete(id);
        return R.success(null);
    }
}
