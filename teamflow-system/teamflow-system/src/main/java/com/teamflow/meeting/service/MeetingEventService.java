package com.teamflow.meeting.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.meeting.domain.dto.MeetingEventWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.mapper.MeetingEventMapper;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M06 日程事件服务：个人/团队日程的增删改查，以及会议创建/改期/取消时的日程同步。
 *
 * <p>会议同步生成的日程（{@code source_type=meeting}）不允许手工修改或删除，
 * 必须通过会议的改期/取消操作同步，避免会议与日程不一致。</p>
 */
@Service
public class MeetingEventService extends MeetingServiceSupport {

    private static final Set<String> EVENT_TYPES = Set.of("personal", "team");
    private static final int LIST_LIMIT_MAX = 100;

    private final MeetingEventMapper eventMapper;

    public MeetingEventService(MeetingPlatformMapper platformMapper, MeetingEventMapper eventMapper) {
        super(platformMapper);
        this.eventMapper = eventMapper;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String eventType,
                                                String fromTime, String toTime) {
        Page page = pageOf(query);
        DataScopeCriteria scope = DataScopeContext.current();
        String fromDb = fromTime == null || fromTime.isBlank() ? null : dbDateTime(parseDateTime(fromTime, "查询开始时间"));
        String toDb = toTime == null || toTime.isBlank() ? null : dbDateTime(parseDateTime(toTime, "查询结束时间"));
        long total = eventMapper.countEvents(trimToNull(keyword), trimToNull(eventType), fromDb, toDb, scope);
        List<Map<String, Object>> rows = eventMapper.selectEvents(
                trimToNull(keyword), trimToNull(eventType), fromDb, toDb, scope,
                page.offset(), Math.min(page.pageSize(), LIST_LIMIT_MAX));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> event = requireEvent(id);
        requireReadable(event, DataScopeContext.current());
        return event;
    }

    @Transactional
    public MeetingIdResponse create(MeetingEventWriteRequest request) {
        Long ownerId = requireCurrentUserId();
        String title = required(request == null ? null : request.getEventTitle(), "日程标题不能为空");
        String eventType = defaultString(request.getEventType(), "personal");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new BusinessException("日程类型只能为 personal 或 team");
        }
        LocalDateTime start = parseDateTime(request.getStartTime(), "日程开始时间");
        LocalDateTime end = parseDateTime(request.getEndTime(), "日程结束时间");
        validateTimeRange(start, end);
        Long deptId = request.getDeptId();
        if (deptId == null) {
            deptId = platformMapper.selectDeptId(ownerId);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventTitle", title);
        row.put("eventType", eventType);
        row.put("ownerId", ownerId);
        row.put("deptId", deptId);
        row.put("meetingId", null);
        row.put("startTime", dbDateTime(start));
        row.put("endTime", dbDateTime(end));
        row.put("allDay", Boolean.TRUE.equals(request.getAllDay()) ? 1 : 0);
        row.put("location", trimToNull(request.getLocation()));
        row.put("description", trimToNull(request.getDescription()));
        row.put("remindMinutes", request.getRemindMinutes() == null ? 15 : Math.max(0, request.getRemindMinutes()));
        row.put("status", "active");
        row.put("sourceType", "manual");
        row.put("remark", trimToNull(request.getRemark()));
        eventMapper.insertEvent(row, currentUsername());
        return new MeetingIdResponse(numberAsLong(row.get("id")));
    }

    @DataScope
    @Transactional
    public void update(Long id, MeetingEventWriteRequest request) {
        Map<String, Object> existing = requireEvent(id);
        requireWritable(existing, DataScopeContext.current());
        String title = request == null || request.getEventTitle() == null
                ? String.valueOf(existing.get("eventTitle"))
                : required(request.getEventTitle(), "日程标题不能为空");
        LocalDateTime start = request == null || request.getStartTime() == null
                ? parseDbDateTime(existing.get("startTime"), "日程开始时间")
                : parseDateTime(request.getStartTime(), "日程开始时间");
        LocalDateTime end = request == null || request.getEndTime() == null
                ? parseDbDateTime(existing.get("endTime"), "日程结束时间")
                : parseDateTime(request.getEndTime(), "日程结束时间");
        validateTimeRange(start, end);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventTitle", title);
        row.put("eventType", request == null || request.getEventType() == null
                ? String.valueOf(existing.get("eventType")) : defaultString(request.getEventType(), "personal"));
        row.put("deptId", request == null || request.getDeptId() == null
                ? existing.get("deptId") : request.getDeptId());
        row.put("startTime", dbDateTime(start));
        row.put("endTime", dbDateTime(end));
        row.put("allDay", request == null || request.getAllDay() == null
                ? intOf(existing.get("allDay")) : (Boolean.TRUE.equals(request.getAllDay()) ? 1 : 0));
        row.put("location", request == null || request.getLocation() == null
                ? existing.get("location") : trimToNull(request.getLocation()));
        row.put("description", request == null || request.getDescription() == null
                ? existing.get("description") : trimToNull(request.getDescription()));
        row.put("remindMinutes", request == null || request.getRemindMinutes() == null
                ? intOf(existing.get("remindMinutes")) : Math.max(0, request.getRemindMinutes()));
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        eventMapper.updateEvent(id, row, currentUsername());
    }

    @DataScope
    @Transactional
    public void cancel(Long id) {
        Map<String, Object> existing = requireEvent(id);
        requireWritable(existing, DataScopeContext.current());
        eventMapper.cancelEvent(id, currentUsername());
    }

    // ------------------------------------------------------------------
    // 会议日程同步（由会议服务在同一事务内调用）
    // ------------------------------------------------------------------

    /** 为会议参会人生成/补齐会议日程（已存在则不重复插入）。 */
    @Transactional
    public void syncForMeeting(Long meetingId, String title, LocalDateTime start, LocalDateTime end,
                               String location, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : Set.copyOf(userIds)) {
            if (userId == null) {
                continue;
            }
            Map<String, Object> existing = eventMapper.selectMeetingEvent(meetingId, userId);
            if (existing != null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventTitle", title);
            row.put("eventType", "meeting");
            row.put("ownerId", userId);
            row.put("deptId", null);
            row.put("meetingId", meetingId);
            row.put("startTime", dbDateTime(start));
            row.put("endTime", dbDateTime(end));
            row.put("allDay", 0);
            row.put("location", trimToNull(location));
            row.put("description", null);
            row.put("remindMinutes", 15);
            row.put("status", "active");
            row.put("sourceType", "meeting");
            row.put("remark", null);
            eventMapper.insertEvent(row, currentUsername());
        }
    }

    /** 会议改期：同步全部参会人的会议日程时间。 */
    @Transactional
    public void rescheduleForMeeting(Long meetingId, String title, LocalDateTime start, LocalDateTime end) {
        eventMapper.updateMeetingEventTimes(meetingId, title, dbDateTime(start), dbDateTime(end), currentUsername());
    }

    /** 会议取消：把该会议生成的全部日程置为已取消。 */
    @Transactional
    public void cancelForMeeting(Long meetingId) {
        eventMapper.cancelByMeeting(meetingId, currentUsername());
    }

    /** 参会人被移出会议：取消其个人会议日程。 */
    @Transactional
    public void cancelForParticipant(Long meetingId, Long userId) {
        eventMapper.cancelParticipantEvent(meetingId, userId, currentUsername());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> requireEvent(Long id) {
        if (id == null) {
            throw new BusinessException("日程 ID 不能为空");
        }
        Map<String, Object> event = eventMapper.selectEventById(id);
        if (event == null) {
            throw new BusinessException("日程不存在");
        }
        return event;
    }

    private void requireWritable(Map<String, Object> event, DataScopeCriteria scope) {
        if ("meeting".equals(String.valueOf(event.get("sourceType")))) {
            throw new BusinessException("该日程由会议同步生成，请通过会议改期或取消操作同步");
        }
        Long ownerId = numberAsLong(event.get("ownerId"));
        if (!scope.isAllScope() && (ownerId == null || !ownerId.equals(scope.getUserId()))) {
            throw new AccessDeniedException("无权修改他人的个人日程");
        }
    }

    private void requireReadable(Map<String, Object> event, DataScopeCriteria scope) {
        if (scope.isAllScope()) {
            return;
        }
        Long ownerId = numberAsLong(event.get("ownerId"));
        if (ownerId != null && ownerId.equals(scope.getUserId())) {
            return;
        }
        Long deptId = numberAsLong(event.get("deptId"));
        if (deptId != null && scope.getDeptIds().contains(deptId)) {
            return;
        }
        throw new AccessDeniedException("无权查看该日程");
    }
}
