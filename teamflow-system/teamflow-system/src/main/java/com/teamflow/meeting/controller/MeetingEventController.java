package com.teamflow.meeting.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.meeting.domain.dto.MeetingEventWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.service.MeetingEventService;
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
 * M06 日程接口（{@code /api/meeting/events}）。
 *
 * <p>个人/团队日程 CRUD；会议创建/改期/取消时由会议服务在同一事务内同步生成会议日程，
 * 会议日程不允许手工修改（页面提示走会议操作）。</p>
 */
@RestController
@RequestMapping("/api/meeting/events")
public class MeetingEventController {

    private final MeetingEventService service;

    public MeetingEventController(MeetingEventService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("meeting:event:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String fromTime,
            @RequestParam(required = false) String toTime
    ) {
        return R.success(service.list(query, keyword, eventType, fromTime, toTime));
    }

    @GetMapping("/{id}")
    @RequiresPermission("meeting:event:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("meeting:event:add")
    @OperLog(module = "日程管理", action = "新增日程")
    public R<MeetingIdResponse> create(@RequestBody MeetingEventWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("meeting:event:edit")
    @OperLog(module = "日程管理", action = "修改日程")
    public R<Void> update(@PathVariable Long id, @RequestBody MeetingEventWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("meeting:event:remove")
    @OperLog(module = "日程管理", action = "取消日程")
    public R<Void> cancel(@PathVariable Long id) {
        service.cancel(id);
        return R.success(null);
    }
}
