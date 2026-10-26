package com.teamflow.meeting.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.meeting.domain.dto.MeetingBookingWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.service.MeetingBookingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M06 会议室预定接口（{@code /api/meeting/bookings}）。
 *
 * <p>冲突检测在服务端同一事务内完成（R1）：同一会议室同一时段重复预定返回 400 + 可读冲突提示。</p>
 */
@RestController
@RequestMapping("/api/meeting/bookings")
public class MeetingBookingController {

    private final MeetingBookingService service;

    public MeetingBookingController(MeetingBookingService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("meeting:booking:list")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromTime,
            @RequestParam(required = false) String toTime
    ) {
        return R.success(service.list(roomId, status, fromTime, toTime));
    }

    @PostMapping
    @RequiresPermission("meeting:booking:add")
    @OperLog(module = "会议室预定", action = "新增预定")
    public R<MeetingIdResponse> create(@RequestBody MeetingBookingWriteRequest request) {
        return R.success(service.create(request));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("meeting:booking:cancel")
    @OperLog(module = "会议室预定", action = "取消预定")
    public R<Void> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        service.cancel(id, reason == null ? null : String.valueOf(reason));
        return R.success(null);
    }
}
