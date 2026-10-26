package com.teamflow.meeting.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;
import com.teamflow.knowledge.service.KnowledgeArchiveService;
import com.teamflow.meeting.domain.dto.MeetingCancelRequest;
import com.teamflow.meeting.domain.dto.MeetingCreateRequest;
import com.teamflow.meeting.domain.dto.MeetingMinutesRequest;
import com.teamflow.meeting.domain.dto.MeetingParticipantsRequest;
import com.teamflow.meeting.domain.dto.MeetingRespondRequest;
import com.teamflow.meeting.domain.dto.MeetingScheduleRequest;
import com.teamflow.meeting.domain.dto.MeetingUpdateRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.mapper.MeetingMeetingMapper;
import com.teamflow.meeting.mapper.MeetingParticipantMapper;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M06 会议服务：会议创建/改期/取消/结束、参会人、纪要归档（M3-C 核心）。
 *
 * <p><b>三条契约</b>（L0 §9.1 D-2026-10-26-11）：</p>
 * <ol>
 *   <li>纪要归档：会议结束后调用 M07 的 {@link KnowledgeArchiveService#archive} 创建知识页面，
 *       {@code meeting_meeting} 只保存返回的 {@code minutes_page_id}，**不复制纪要正文**；</li>
 *   <li>纪要待办：结束时只经 {@link MeetingTodoPushService}（M10 统一投递服务）给主持人生成待办，
 *       不直写待办数据；</li>
 *   <li>通知：邀请/改期/取消只经 {@link MeetingNotifyService}（M09 统一通知入口），不自建消息表。</li>
 * </ol>
 *
 * <p>会议室占用与冲突检测由 {@link MeetingBookingService} 在同一事务内完成，冲突则整个创建/改期回滚。</p>
 */
@Service
public class MeetingMeetingService extends MeetingServiceSupport {

    private static final Logger log = LoggerFactory.getLogger(MeetingMeetingService.class);
    private static final Set<String> RESPONSES = Set.of("accepted", "declined");
    private static final Set<String> PARTICIPANT_ROLES = Set.of("required", "optional");
    private static final int LIST_LIMIT_MAX = 100;

    private final MeetingMeetingMapper meetingMapper;
    private final MeetingParticipantMapper participantMapper;
    private final MeetingBookingService bookingService;
    private final MeetingEventService eventService;
    private final MeetingTemplateService templateService;
    private final MeetingNotifyService notifyService;
    private final MeetingTodoPushService todoPushService;
    private final KnowledgeArchiveService archiveService;

    public MeetingMeetingService(
            MeetingPlatformMapper platformMapper,
            MeetingMeetingMapper meetingMapper,
            MeetingParticipantMapper participantMapper,
            MeetingBookingService bookingService,
            MeetingEventService eventService,
            MeetingTemplateService templateService,
            MeetingNotifyService notifyService,
            MeetingTodoPushService todoPushService,
            KnowledgeArchiveService archiveService
    ) {
        super(platformMapper);
        this.meetingMapper = meetingMapper;
        this.participantMapper = participantMapper;
        this.bookingService = bookingService;
        this.eventService = eventService;
        this.templateService = templateService;
        this.notifyService = notifyService;
        this.todoPushService = todoPushService;
        this.archiveService = archiveService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status,
                                                String fromTime, String toTime) {
        Page page = pageOf(query);
        DataScopeCriteria scope = DataScopeContext.current();
        String fromDb = fromTime == null || fromTime.isBlank() ? null : dbDateTime(parseDateTime(fromTime, "查询开始时间"));
        String toDb = toTime == null || toTime.isBlank() ? null : dbDateTime(parseDateTime(toTime, "查询结束时间"));
        long total = meetingMapper.countMeetings(trimToNull(keyword), trimToNull(status), fromDb, toDb, scope);
        List<Map<String, Object>> rows = meetingMapper.selectMeetings(
                trimToNull(keyword), trimToNull(status), fromDb, toDb, scope,
                page.offset(), Math.min(page.pageSize(), LIST_LIMIT_MAX));
        rows.forEach(row -> row.put("participantCount", participantMapper.countParticipants(numberAsLong(row.get("id")))));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> meeting = requireMeeting(id);
        requireVisible(meeting, DataScopeContext.current());
        meeting.put("participants", participantMapper.selectParticipants(id));
        return meeting;
    }

    // ------------------------------------------------------------------
    // 创建 / 修改 / 改期 / 取消 / 结束
    // ------------------------------------------------------------------

    @Transactional
    public MeetingIdResponse create(MeetingCreateRequest request) {
        Long organizerId = requireCurrentUserId();
        String title = required(request == null ? null : request.getMeetingTitle(), "会议标题不能为空");
        LocalDateTime start = parseDateTime(request.getStartTime(), "会议开始时间");
        LocalDateTime end = parseDateTime(request.getEndTime(), "会议结束时间");
        validateTimeRange(start, end);
        Long templateId = request.getTemplateId();
        Map<String, Object> template = templateService.requireTemplate(templateId);
        String agenda = trimToNull(request.getAgenda());
        if (agenda == null && template != null) {
            agenda = trimToNull(String.valueOf(template.get("agendaTemplate")));
        }
        Long roomId = request.getRoomId();
        String location = trimToNull(request.getLocation());
        String meetingLink = trimToNull(request.getMeetingLink());
        Set<Long> participantIds = normalizeParticipantIds(request.getParticipantIds(), organizerId);
        ensureParticipantsFree(new ArrayList<>(participantIds), start, end, null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meetingTitle", title);
        row.put("templateId", templateId);
        row.put("roomId", null);
        row.put("bookingId", null);
        row.put("organizerId", organizerId);
        row.put("startTime", dbDateTime(start));
        row.put("endTime", dbDateTime(end));
        row.put("location", location);
        row.put("meetingLink", meetingLink);
        row.put("agenda", agenda);
        row.put("status", "draft");
        row.put("remark", trimToNull(request.getRemark()));
        meetingMapper.insertMeeting(row, currentUsername());
        Long meetingId = numberAsLong(row.get("id"));

        Long bookingId = null;
        if (roomId != null) {
            bookingId = bookingService.createForMeeting(meetingId, roomId, title, organizerId, start, end);
        }
        meetingMapper.confirmMeeting(meetingId, roomId, bookingId, currentUsername());

        insertParticipants(meetingId, organizerId, participantIds, "required");
        eventService.syncForMeeting(meetingId, title, start, end, location, new ArrayList<>(participantIds));
        List<Long> receivers = new ArrayList<>(participantIds);
        receivers.remove(organizerId);
        notifyService.notifyInvite(meetingId, title, start, end, location, meetingLink, receivers);
        todoPushService.pushAttendTodos(meetingId, title, start, receivers);
        return new MeetingIdResponse(meetingId);
    }

    @Transactional
    public void update(Long id, MeetingUpdateRequest request) {
        Map<String, Object> existing = requireMeeting(id);
        requireEditable(existing, "已结束或已取消的会议不能修改");
        Long templateId = request == null || request.getTemplateId() == null
                ? numberAsLong(existing.get("templateId")) : request.getTemplateId();
        templateService.requireTemplate(templateId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meetingTitle", request == null || request.getMeetingTitle() == null
                ? String.valueOf(existing.get("meetingTitle"))
                : required(request.getMeetingTitle(), "会议标题不能为空"));
        row.put("templateId", templateId);
        row.put("location", request == null || request.getLocation() == null
                ? existing.get("location") : trimToNull(request.getLocation()));
        row.put("meetingLink", request == null || request.getMeetingLink() == null
                ? existing.get("meetingLink") : trimToNull(request.getMeetingLink()));
        row.put("agenda", request == null || request.getAgenda() == null
                ? existing.get("agenda") : trimToNull(request.getAgenda()));
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        meetingMapper.updateMeeting(id, row, currentUsername());
    }

    @Transactional
    public Map<String, Object> reschedule(Long id, MeetingScheduleRequest request) {
        Map<String, Object> existing = requireMeeting(id);
        requireEditable(existing, "已结束或已取消的会议不能改期");
        String status = String.valueOf(existing.get("status"));
        if ("ongoing".equals(status)) {
            throw new BusinessException("会议已开始，不能改期");
        }
        LocalDateTime start = parseDateTime(request == null ? null : request.getStartTime(), "会议开始时间");
        LocalDateTime end = parseDateTime(request.getEndTime(), "会议结束时间");
        validateTimeRange(start, end);
        Long currentRoomId = numberAsLong(existing.get("roomId"));
        boolean onlineOnly = request.getOnlineOnly() != null && request.getOnlineOnly();
        Long newRoomId = onlineOnly ? null : (request.getRoomId() == null ? currentRoomId : request.getRoomId());
        Long oldBookingId = numberAsLong(existing.get("bookingId"));
        if (oldBookingId != null) {
            bookingService.releaseBooking(oldBookingId, "会议改期释放原会议室");
        }
        ensureParticipantsFree(participantIds(id), start, end, id);
        Long newBookingId = null;
        if (newRoomId != null) {
            newBookingId = bookingService.createForMeeting(
                    id, newRoomId, String.valueOf(existing.get("meetingTitle")),
                    numberAsLong(existing.get("organizerId")), start, end);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("startTime", dbDateTime(start));
        row.put("endTime", dbDateTime(end));
        row.put("roomId", newRoomId);
        row.put("bookingId", newBookingId);
        meetingMapper.rescheduleMeeting(id, row, currentUsername());
        eventService.rescheduleForMeeting(id, String.valueOf(existing.get("meetingTitle")), start, end);
        List<Long> receivers = participantIds(id);
        receivers.remove(numberAsLong(existing.get("organizerId")));
        notifyService.notifyReschedule(id, String.valueOf(existing.get("meetingTitle")), start, end,
                existing.get("location") == null ? null : String.valueOf(existing.get("location")),
                existing.get("meetingLink") == null ? null : String.valueOf(existing.get("meetingLink")),
                receivers);
        Map<String, Object> refreshed = requireMeeting(id);
        refreshed.put("participants", participantMapper.selectParticipants(id));
        return refreshed;
    }

    @Transactional
    public void cancel(Long id, MeetingCancelRequest request) {
        Map<String, Object> existing = requireMeeting(id);
        String status = String.valueOf(existing.get("status"));
        if ("ended".equals(status)) {
            throw new BusinessException("已结束的会议不能取消");
        }
        if ("cancelled".equals(status)) {
            throw new BusinessException("会议已取消");
        }
        String reason = request == null ? null : trimToNull(request.getCancelReason());
        if (meetingMapper.cancelMeeting(id, defaultString(reason, "未填写原因"), currentUsername()) == 0) {
            throw new BusinessException("会议状态已变化，请刷新后重试");
        }
        bookingService.releaseForMeeting(id, "会议取消释放会议室");
        eventService.cancelForMeeting(id);
        List<Long> receivers = participantIds(id);
        receivers.remove(numberAsLong(existing.get("organizerId")));
        notifyService.notifyCancel(id, String.valueOf(existing.get("meetingTitle")), reason, receivers);
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requireMeeting(id);
        String status = String.valueOf(existing.get("status"));
        if (!"draft".equals(status) && !"cancelled".equals(status)) {
            throw new BusinessException("请先取消会议再删除");
        }
        meetingMapper.softDeleteMeeting(id, currentUsername());
    }

    /** 会议结束（R4）：生成主持人「撰写会议纪要」待办，只经 M10 投递。 */
    @Transactional
    public Map<String, Object> end(Long id) {
        Map<String, Object> existing = requireMeeting(id);
        String status = String.valueOf(existing.get("status"));
        if ("cancelled".equals(status)) {
            throw new BusinessException("已取消的会议不能结束");
        }
        meetingMapper.endMeeting(id, currentUsername());
        // 会议结束后不再需要占用会议室：释放该会议的有效预定（R5：取消/结束都应释放资源）
        bookingService.releaseForMeeting(id, "会议结束释放会议室");
        Long organizerId = numberAsLong(existing.get("organizerId"));
        LocalDateTime endTime = parseDbDateTime(existing.get("endTime"), "会议结束时间");
        Long todoId = todoPushService.pushMinutesTodo(
                id, organizerId, String.valueOf(existing.get("meetingTitle")), endTime);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meetingId", id);
        result.put("status", "ended");
        result.put("minutesTodoId", todoId);
        result.put("organizerId", organizerId);
        return result;
    }

    /**
     * 纪要归档（M3-C 核心）：正文写入 M07 知识页面，本模块只保存 `minutes_page_id`。
     *
     * @return pageId / created / versionNo（供前端跳转知识页面）
     */
    @Transactional
    public Map<String, Object> archiveMinutes(Long id, MeetingMinutesRequest request) {
        Map<String, Object> meeting = requireMeeting(id);
        String status = String.valueOf(meeting.get("status"));
        if (!"ended".equals(status)) {
            throw new BusinessException("会议结束后才能归档纪要（当前状态：" + status + "）");
        }
        String content = required(request == null ? null : request.getContent(), "纪要正文不能为空");
        String title = "会议纪要：" + meeting.get("meetingTitle");
        KnowledgeArchiveResult archived = archiveService.archive(new KnowledgeArchiveRequest(
                "M06",
                "meeting_minutes",
                id,
                null,
                null,
                title,
                content,
                request.getTags(),
                trimToNull(request.getSummary()),
                requireCurrentUserId(),
                currentUsername(),
                true));
        meetingMapper.updateMinutesPage(id, archived.pageId(), currentUsername());
        List<Long> receivers = participantIds(id);
        receivers.remove(numberAsLong(meeting.get("organizerId")));
        notifyService.notifyMinutesArchived(id, String.valueOf(meeting.get("meetingTitle")), archived.pageId(), receivers);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meetingId", id);
        result.put("minutesPageId", archived.pageId());
        result.put("created", archived.created());
        result.put("versionNo", archived.versionNo());
        result.put("pageType", archived.pageType());
        return result;
    }

    // ------------------------------------------------------------------
    // 参会人
    // ------------------------------------------------------------------

    @Transactional
    public void addParticipants(Long id, MeetingParticipantsRequest request) {
        Map<String, Object> meeting = requireMeeting(id);
        requireEditable(meeting, "已结束或已取消的会议不能加参会人");
        String role = defaultString(request == null ? null : request.getParticipantRole(), "required");
        if (!PARTICIPANT_ROLES.contains(role)) {
            throw new BusinessException("参会角色只能为 required 或 optional");
        }
        Set<Long> newUsers = normalizeParticipantIds(request == null ? null : request.getUserIds(), null);
        if (newUsers.isEmpty()) {
            throw new BusinessException("请选择要新增的参会人");
        }
        ensureParticipantsFree(new ArrayList<>(newUsers), parseDbDateTime(meeting.get("startTime"), "会议开始时间"),
                parseDbDateTime(meeting.get("endTime"), "会议结束时间"), id);
        Set<Long> existing = new LinkedHashSet<>(participantIds(id));
        for (Long userId : newUsers) {
            if (existing.contains(userId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meetingId", id);
            row.put("userId", userId);
            row.put("participantRole", role);
            row.put("responseStatus", "pending");
            row.put("remark", null);
            participantMapper.insertParticipant(row, currentUsername());
        }
        LocalDateTime start = parseDbDateTime(meeting.get("startTime"), "会议开始时间");
        LocalDateTime end = parseDbDateTime(meeting.get("endTime"), "会议结束时间");
        eventService.syncForMeeting(id, String.valueOf(meeting.get("meetingTitle")), start, end,
                meeting.get("location") == null ? null : String.valueOf(meeting.get("location")),
                new ArrayList<>(newUsers));
        List<Long> receivers = new ArrayList<>(newUsers);
        receivers.remove(numberAsLong(meeting.get("organizerId")));
        notifyService.notifyInvite(id, String.valueOf(meeting.get("meetingTitle")), start, end,
                meeting.get("location") == null ? null : String.valueOf(meeting.get("location")),
                meeting.get("meetingLink") == null ? null : String.valueOf(meeting.get("meetingLink")),
                receivers);
        todoPushService.pushAttendTodos(id, String.valueOf(meeting.get("meetingTitle")), start, receivers);
    }

    @Transactional
    public void removeParticipant(Long id, Long userId) {
        Map<String, Object> meeting = requireMeeting(id);
        requireEditable(meeting, "已结束或已取消的会议不能移除参会人");
        if (userId == null) {
            throw new BusinessException("参会人 ID 不能为空");
        }
        if (userId.equals(numberAsLong(meeting.get("organizerId")))) {
            throw new BusinessException("不能移除会议主持人");
        }
        if (participantMapper.softDeleteParticipant(id, userId, currentUsername()) == 0) {
            throw new BusinessException("该用户不是本会议参会人");
        }
        eventService.cancelForParticipant(id, userId);
    }

    @Transactional
    public void respond(Long id, MeetingRespondRequest request) {
        Map<String, Object> meeting = requireMeeting(id);
        Long userId = requireCurrentUserId();
        String response = request == null ? null : trimToNull(request.getResponseStatus());
        if (response == null || !RESPONSES.contains(response)) {
            throw new BusinessException("答复只能为 accepted 或 declined");
        }
        if (participantMapper.selectParticipant(id, userId) == null) {
            throw new BusinessException("你不是本会议参会人");
        }
        participantMapper.updateResponse(id, userId, response, currentUsername());
        if ("declined".equals(response)) {
            eventService.cancelForParticipant(id, userId);
        }
        log.info("参会答复：meetingId={} userId={} response={}", id, userId, response);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    Map<String, Object> requireMeeting(Long id) {
        if (id == null) {
            throw new BusinessException("会议 ID 不能为空");
        }
        Map<String, Object> meeting = meetingMapper.selectMeetingById(id);
        if (meeting == null) {
            throw new BusinessException("会议不存在");
        }
        return meeting;
    }

    private void requireEditable(Map<String, Object> meeting, String message) {
        String status = String.valueOf(meeting.get("status"));
        if ("ended".equals(status) || "cancelled".equals(status)) {
            throw new BusinessException(message);
        }
    }

    private void requireVisible(Map<String, Object> meeting, DataScopeCriteria scope) {
        if (scope.isAllScope()) {
            return;
        }
        Long organizerId = numberAsLong(meeting.get("organizerId"));
        if (organizerId != null && organizerId.equals(scope.getUserId())) {
            return;
        }
        if (scope.getUserId() != null && participantIds(numberAsLong(meeting.get("id"))).contains(scope.getUserId())) {
            return;
        }
        throw new AccessDeniedException("无权查看该会议");
    }

    /** R1 参会人时段校验：同一参会人在该时段已有其它未取消会议则拒绝，并给出可读提示。 */
    private void ensureParticipantsFree(List<Long> userIds, LocalDateTime start, LocalDateTime end, Long excludeMeetingId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Map<String, Object> conflict = meetingMapper.selectParticipantConflict(
                userIds, dbDateTime(start), dbDateTime(end), excludeMeetingId);
        if (conflict != null) {
            throw new BusinessException("参会人「" + conflict.get("nickname") + "」在 "
                    + conflict.get("startTime") + " ~ " + conflict.get("endTime")
                    + " 已有会议「" + conflict.get("meetingTitle") + "」，与本次 "
                    + displayDateTime(start) + " ~ " + displayDateTime(end)
                    + " 时间冲突，请调整时间或改为可选参会");
        }
    }
    private List<Long> participantIds(Long meetingId) {
        List<Long> ids = participantMapper.listParticipantIds(meetingId);
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    private Set<Long> normalizeParticipantIds(List<Long> userIds, Long organizerId) {
        Set<Long> result = new LinkedHashSet<>();
        if (organizerId != null) {
            result.add(organizerId);
        }
        if (userIds == null) {
            return result;
        }
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            if (platformMapper.countActiveUser(userId) == 0) {
                throw new BusinessException("参会人不存在或已停用：" + userId);
            }
            result.add(userId);
        }
        return result;
    }

    private void insertParticipants(Long meetingId, Long organizerId, Set<Long> participantIds, String role) {
        for (Long userId : participantIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meetingId", meetingId);
            row.put("userId", userId);
            row.put("participantRole", userId.equals(organizerId) ? "host" : role);
            row.put("responseStatus", userId.equals(organizerId) ? "accepted" : "pending");
            row.put("remark", null);
            participantMapper.insertParticipant(row, currentUsername());
        }
    }
}
