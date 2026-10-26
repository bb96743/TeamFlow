package com.teamflow.meeting.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.meeting.domain.dto.MeetingBookingWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.mapper.MeetingBookingMapper;
import com.teamflow.meeting.mapper.MeetingMeetingMapper;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import com.teamflow.meeting.mapper.MeetingRoomMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M06 会议室预定服务：**服务端冲突检测**（R1）与预定幂等（R6）。
 *
 * <p>冲突判定（半开区间）：同一会议室下，已有有效预定的 {@code start < 新 end} 且
 * {@code end > 新 start} 即视为重叠；检测与写入在同一事务内完成，并先对会议室行加锁
 * （{@code SELECT ... FOR UPDATE}），把同一会议室的并发预定串行化。</p>
 *
 * <p>冲突拒绝时返回可读提示，包含会议室、占用时段、占用会议/预定人，便于用户直接调整时间或换会议室。</p>
 */
@Service
public class MeetingBookingService extends MeetingServiceSupport {

    private static final Set<String> BOOKING_STATUSES = Set.of("booked", "cancelled");

    private final MeetingBookingMapper bookingMapper;
    private final MeetingRoomMapper roomMapper;
    private final MeetingMeetingMapper meetingMapper;

    public MeetingBookingService(
            MeetingPlatformMapper platformMapper,
            MeetingBookingMapper bookingMapper,
            MeetingRoomMapper roomMapper,
            MeetingMeetingMapper meetingMapper
    ) {
        super(platformMapper);
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.meetingMapper = meetingMapper;
    }

    public List<Map<String, Object>> list(Long roomId, String status, String fromTime, String toTime) {
        String normalizedStatus = trimToNull(status);
        if (normalizedStatus != null && !BOOKING_STATUSES.contains(normalizedStatus)) {
            throw new BusinessException("预定状态只能为 booked 或 cancelled");
        }
        String fromDb = fromTime == null || fromTime.isBlank() ? null : dbDateTime(parseDateTime(fromTime, "查询开始时间"));
        String toDb = toTime == null || toTime.isBlank() ? null : dbDateTime(parseDateTime(toTime, "查询结束时间"));
        return bookingMapper.selectBookings(roomId, normalizedStatus, fromDb, toDb, 500);
    }

    @Transactional
    public MeetingIdResponse create(MeetingBookingWriteRequest request) {
        Long roomId = request == null ? null : request.getRoomId();
        if (roomId == null) {
            throw new BusinessException("会议室不能为空");
        }
        LocalDateTime start = parseDateTime(request.getStartTime(), "预定开始时间");
        LocalDateTime end = parseDateTime(request.getEndTime(), "预定结束时间");
        validateTimeRange(start, end);
        Long bookerId = request.getBookerId() == null ? requireCurrentUserId() : request.getBookerId();
        if (platformMapper.countActiveUser(bookerId) == 0) {
            throw new BusinessException("预定人不存在或已停用");
        }
        Long meetingId = request.getMeetingId();
        if (meetingId != null) {
            Map<String, Object> meeting = meetingMapper.selectMeetingById(meetingId);
            if (meeting == null) {
                throw new BusinessException("关联会议不存在");
            }
        }
        String title = defaultString(request.getBookingTitle(), "会议室预定");
        Long bookingId = insertBooking(roomId, meetingId, title, bookerId, start, end, trimToNull(request.getRemark()));
        return new MeetingIdResponse(bookingId);
    }

    /** 会议创建/改期时占用会议室（与会议写入同一事务，冲突则整体回滚）。 */
    @Transactional
    public Long createForMeeting(Long meetingId, Long roomId, String title, Long bookerId,
                                 LocalDateTime start, LocalDateTime end) {
        return insertBooking(roomId, meetingId, title, bookerId, start, end, null);
    }

    /** 释放某条预定（会议改期时释放旧预定）。 */
    @Transactional
    public void releaseBooking(Long bookingId, String reason) {
        if (bookingId == null) {
            return;
        }
        bookingMapper.cancelBooking(bookingId, reason, currentUsername());
    }

    /** 释放某会议占用的全部预定（会议取消时）。 */
    @Transactional
    public void releaseForMeeting(Long meetingId, String reason) {
        if (meetingId == null) {
            return;
        }
        bookingMapper.cancelByMeeting(meetingId, reason, currentUsername());
    }

    @Transactional
    public void cancel(Long id, String reason) {
        Map<String, Object> booking = requireBooking(id);
        if ("cancelled".equals(String.valueOf(booking.get("status")))) {
            throw new BusinessException("该预定已取消");
        }
        if (booking.get("meetingId") != null) {
            throw new BusinessException("该预定由会议占用，请通过「取消会议」释放会议室");
        }
        int updated = bookingMapper.cancelBooking(id, defaultString(reason, "手动取消预定"), currentUsername());
        if (updated == 0) {
            throw new BusinessException("预定不存在或已取消");
        }
    }

    /**
     * 校验会议室在指定区间可用；不可用时抛出含冲突明细的业务异常。
     */
    void ensureAvailable(Long roomId, LocalDateTime start, LocalDateTime end, Long excludeBookingId) {
        Map<String, Object> room = roomMapper.selectRoomById(roomId);
        if (room == null) {
            throw new BusinessException("会议室不存在");
        }
        if (!"available".equals(String.valueOf(room.get("status")))) {
            throw new BusinessException("会议室「" + room.get("roomName") + "」已停用，不能预定");
        }
        List<Map<String, Object>> conflicts = bookingMapper.selectConflicts(
                roomId, dbDateTime(start), dbDateTime(end), excludeBookingId);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(conflictMessage(room, start, end, conflicts));
        }
    }

    private Long insertBooking(Long roomId, Long meetingId, String title, Long bookerId,
                               LocalDateTime start, LocalDateTime end, String remark) {
        // 同一会议室并发预定串行化：先锁会议室行，再做区间冲突检测
        roomMapper.lockRoom(roomId);
        ensureAvailable(roomId, start, end, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roomId", roomId);
        row.put("meetingId", meetingId);
        row.put("bookingTitle", title);
        row.put("bookerId", bookerId);
        row.put("startTime", dbDateTime(start));
        row.put("endTime", dbDateTime(end));
        row.put("status", "booked");
        row.put("remark", remark);
        bookingMapper.insertBooking(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    /** 可读冲突提示：会议室 + 占用时段 + 占用会议/预定人。 */
    private String conflictMessage(Map<String, Object> room, LocalDateTime start, LocalDateTime end,
                                   List<Map<String, Object>> conflicts) {
        Map<String, Object> first = conflicts.get(0);
        StringBuilder message = new StringBuilder()
                .append("会议室「").append(room.get("roomName")).append("」在 ")
                .append(first.get("startTime")).append(" ~ ").append(first.get("endTime"))
                .append(" 已被占用");
        Object meetingTitle = first.get("meetingTitle");
        Object bookerName = first.get("bookerName");
        if (meetingTitle != null) {
            message.append("（会议：").append(meetingTitle);
            if (bookerName != null) {
                message.append("，预定人：").append(bookerName);
            }
            message.append("）");
        } else if (bookerName != null) {
            message.append("（预定人：").append(bookerName).append("）");
        }
        message.append("，与本次 ").append(displayDateTime(start)).append(" ~ ").append(displayDateTime(end))
                .append(" 冲突，请调整时间或更换会议室");
        if (conflicts.size() > 1) {
            message.append("（共 ").append(conflicts.size()).append(" 条预定冲突）");
        }
        return message.toString();
    }

    private Map<String, Object> requireBooking(Long id) {
        if (id == null) {
            throw new BusinessException("预定 ID 不能为空");
        }
        Map<String, Object> booking = bookingMapper.selectBookingById(id);
        if (booking == null) {
            throw new BusinessException("预定不存在");
        }
        return booking;
    }
}
