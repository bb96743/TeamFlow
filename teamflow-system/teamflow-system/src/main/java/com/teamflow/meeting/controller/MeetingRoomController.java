package com.teamflow.meeting.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.meeting.domain.dto.MeetingRoomWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.service.MeetingRoomService;
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
 * M06 会议室接口（{@code /api/meeting/rooms}）：资源档案与可用性查询（含冲突说明）。
 */
@RestController
@RequestMapping("/api/meeting/rooms")
public class MeetingRoomController {

    private final MeetingRoomService service;

    public MeetingRoomController(MeetingRoomService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("meeting:room:list")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minCapacity
    ) {
        return R.success(service.list(keyword, status, minCapacity));
    }

    /** 区间可用性：返回该时段每个会议室的 available / conflictCount / conflictNote。 */
    @GetMapping("/availability")
    @RequiresPermission("meeting:room:list")
    public R<List<Map<String, Object>>> availability(
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.availability(startTime, endTime, keyword, minCapacity, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("meeting:room:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("meeting:room:add")
    @OperLog(module = "会议室管理", action = "新增会议室")
    public R<MeetingIdResponse> create(@RequestBody MeetingRoomWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("meeting:room:edit")
    @OperLog(module = "会议室管理", action = "修改会议室")
    public R<Void> update(@PathVariable Long id, @RequestBody MeetingRoomWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("meeting:room:remove")
    @OperLog(module = "会议室管理", action = "删除会议室")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
