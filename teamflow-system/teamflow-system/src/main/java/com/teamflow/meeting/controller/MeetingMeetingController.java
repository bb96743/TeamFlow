package com.teamflow.meeting.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.meeting.domain.dto.MeetingCancelRequest;
import com.teamflow.meeting.domain.dto.MeetingCreateRequest;
import com.teamflow.meeting.domain.dto.MeetingMinutesRequest;
import com.teamflow.meeting.domain.dto.MeetingParticipantsRequest;
import com.teamflow.meeting.domain.dto.MeetingRespondRequest;
import com.teamflow.meeting.domain.dto.MeetingScheduleRequest;
import com.teamflow.meeting.domain.dto.MeetingUpdateRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.service.MeetingMeetingService;
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
 * M06 会议接口（{@code /api/meeting/meetings}）：议程、参会人、改期、取消、结束与纪要归档。
 *
 * <p>M3-C 核心：`POST /meetings/{id}/minutes` 把纪要正文归档到 M07 知识页面，本模块只保存 pageId；
 * `POST /meetings/{id}/end` 经 M10 投递主持人「撰写会议纪要」待办。</p>
 */
@RestController
@RequestMapping("/api/meeting/meetings")
public class MeetingMeetingController {

    private final MeetingMeetingService service;

    public MeetingMeetingController(MeetingMeetingService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("meeting:meeting:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromTime,
            @RequestParam(required = false) String toTime
    ) {
        return R.success(service.list(query, keyword, status, fromTime, toTime));
    }

    @GetMapping("/{id}")
    @RequiresPermission("meeting:meeting:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("meeting:meeting:add")
    @OperLog(module = "会议管理", action = "新增会议")
    public R<MeetingIdResponse> create(@RequestBody MeetingCreateRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "修改会议")
    public R<Void> update(@PathVariable Long id, @RequestBody MeetingUpdateRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    /** 改期：重新校验会议室冲突，释放旧预定并通知参会人。 */
    @PutMapping("/{id}/schedule")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "会议改期")
    public R<Map<String, Object>> reschedule(@PathVariable Long id, @RequestBody MeetingScheduleRequest request) {
        return R.success(service.reschedule(id, request));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "取消会议")
    public R<Void> cancel(@PathVariable Long id, @RequestBody(required = false) MeetingCancelRequest request) {
        service.cancel(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("meeting:meeting:remove")
    @OperLog(module = "会议管理", action = "删除会议")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    /** 会议结束：生成主持人「撰写会议纪要」待办（经 M10 统一投递）。 */
    @PostMapping("/{id}/end")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "结束会议")
    public R<Map<String, Object>> end(@PathVariable Long id) {
        return R.success(service.end(id));
    }

    /** 纪要归档：正文写入 M07 知识页面，本模块只保存 pageId。 */
    @PostMapping("/{id}/minutes")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "归档会议纪要")
    public R<Map<String, Object>> archiveMinutes(@PathVariable Long id, @RequestBody MeetingMinutesRequest request) {
        return R.success(service.archiveMinutes(id, request));
    }

    @PostMapping("/{id}/participants")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "新增参会人")
    public R<Void> addParticipants(@PathVariable Long id, @RequestBody MeetingParticipantsRequest request) {
        service.addParticipants(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}/participants/{userId}")
    @RequiresPermission("meeting:meeting:edit")
    @OperLog(module = "会议管理", action = "移除参会人")
    public R<Void> removeParticipant(@PathVariable Long id, @PathVariable Long userId) {
        service.removeParticipant(id, userId);
        return R.success(null);
    }

    /** 参会答复：accepted 接受 / declined 拒绝。 */
    @PostMapping("/{id}/respond")
    @RequiresPermission("meeting:meeting:list")
    public R<Void> respond(@PathVariable Long id, @RequestBody MeetingRespondRequest request) {
        service.respond(id, request);
        return R.success(null);
    }
}
