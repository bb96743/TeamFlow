package com.teamflow.meeting.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.meeting.domain.dto.MeetingRoomWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.mapper.MeetingBookingMapper;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import com.teamflow.meeting.mapper.MeetingRoomMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M06 会议室服务：资源档案、可用性查询（含冲突说明）。
 *
 * <p>冲突检测的权威实现在 {@link MeetingBookingService}；本服务的可用性查询复用同一
 * {@code selectConflicts} 口径，保证「列表看到的可用」与「预定时的判定」一致。</p>
 */
@Service
public class MeetingRoomService extends MeetingServiceSupport {

    private static final Set<String> ROOM_STATUSES = Set.of("available", "disabled");
    private static final Pattern ROOM_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$");

    private final MeetingRoomMapper roomMapper;
    private final MeetingBookingMapper bookingMapper;

    public MeetingRoomService(
            MeetingPlatformMapper platformMapper,
            MeetingRoomMapper roomMapper,
            MeetingBookingMapper bookingMapper
    ) {
        super(platformMapper);
        this.roomMapper = roomMapper;
        this.bookingMapper = bookingMapper;
    }

    public List<Map<String, Object>> list(String keyword, String status, Integer minCapacity) {
        return roomMapper.selectRooms(trimToNull(keyword), trimToNull(status), minCapacity);
    }

    public Map<String, Object> detail(Long id) {
        return requireRoom(id);
    }

    @Transactional
    public MeetingIdResponse create(MeetingRoomWriteRequest request) {
        String name = required(request == null ? null : request.getRoomName(), "会议室名称不能为空");
        String code = required(request.getRoomCode(), "会议室编码不能为空");
        if (!ROOM_CODE.matcher(code).matches()) {
            throw new BusinessException("会议室编码只能由字母、数字、下划线、短横线组成（2–32 位）");
        }
        if (roomMapper.countRoomCode(code, null) > 0) {
            throw new BusinessException("会议室编码已存在：" + code);
        }
        String status = defaultString(request.getStatus(), "available");
        if (!ROOM_STATUSES.contains(status)) {
            throw new BusinessException("会议室状态只能为 available 或 disabled");
        }
        int capacity = request.getCapacity() == null ? 0 : request.getCapacity();
        if (capacity < 0) {
            throw new BusinessException("会议室容纳人数不能为负数");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roomName", name);
        row.put("roomCode", code);
        row.put("location", trimToNull(request.getLocation()));
        row.put("capacity", capacity);
        row.put("equipment", trimToNull(request.getEquipment()));
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        roomMapper.insertRoom(row, currentUsername());
        return new MeetingIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, MeetingRoomWriteRequest request) {
        Map<String, Object> existing = requireRoom(id);
        String name = request == null || request.getRoomName() == null
                ? String.valueOf(existing.get("roomName"))
                : required(request.getRoomName(), "会议室名称不能为空");
        String status = request == null || request.getStatus() == null
                ? String.valueOf(existing.get("status"))
                : defaultString(request.getStatus(), "available");
        if (!ROOM_STATUSES.contains(status)) {
            throw new BusinessException("会议室状态只能为 available 或 disabled");
        }
        int capacity = request == null || request.getCapacity() == null
                ? intOf(existing.get("capacity")) : request.getCapacity();
        if (capacity < 0) {
            throw new BusinessException("会议室容纳人数不能为负数");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roomName", name);
        row.put("location", request == null || request.getLocation() == null
                ? existing.get("location") : trimToNull(request.getLocation()));
        row.put("capacity", capacity);
        row.put("equipment", request == null || request.getEquipment() == null
                ? existing.get("equipment") : trimToNull(request.getEquipment()));
        row.put("status", status);
        row.put("sort", request == null || request.getSort() == null
                ? intOf(existing.get("sort")) : request.getSort());
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        roomMapper.updateRoom(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        requireRoom(id);
        if (bookingMapper.countActiveBookings(id) > 0) {
            throw new BusinessException("会议室仍有未结束的预定，不能删除（可先取消预定或停用会议室）");
        }
        roomMapper.softDeleteRoom(id, currentUsername());
    }

    /**
     * 会议室可用性查询：返回区间内每个会议室的可用状态与冲突说明。
     *
     * @param startTime 开始时间（ISO-8601）
     * @param endTime   结束时间（ISO-8601）
     */
    public List<Map<String, Object>> availability(String startTime, String endTime, String keyword,
                                                  Integer minCapacity, String status) {
        LocalDateTime start = parseDateTime(startTime, "查询开始时间");
        LocalDateTime end = parseDateTime(endTime, "查询结束时间");
        validateTimeRange(start, end);
        String startDb = dbDateTime(start);
        String endDb = dbDateTime(end);
        List<Map<String, Object>> rooms = roomMapper.selectRooms(trimToNull(keyword), trimToNull(status), minCapacity);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> room : rooms) {
            Long roomId = numberAsLong(room.get("id"));
            List<Map<String, Object>> conflicts = bookingMapper.selectConflicts(roomId, startDb, endDb, null);
            Map<String, Object> item = new LinkedHashMap<>(room);
            boolean roomEnabled = "available".equals(String.valueOf(room.get("status")));
            item.put("available", roomEnabled && conflicts.isEmpty());
            item.put("conflictCount", conflicts.size());
            item.put("conflicts", conflicts);
            item.put("conflictNote", conflicts.isEmpty() ? null : conflictNote(conflicts));
            result.add(item);
        }
        return result;
    }

    private String conflictNote(List<Map<String, Object>> conflicts) {
        Map<String, Object> first = conflicts.get(0);
        String note = "已被占用：" + first.get("startTime") + " ~ " + first.get("endTime");
        Object meetingTitle = first.get("meetingTitle");
        if (meetingTitle != null) {
            note += "（会议：" + meetingTitle + "）";
        }
        if (conflicts.size() > 1) {
            note += "，另有 " + (conflicts.size() - 1) + " 条预定冲突";
        }
        return note;
    }

    Map<String, Object> requireRoom(Long id) {
        if (id == null) {
            throw new BusinessException("会议室 ID 不能为空");
        }
        Map<String, Object> room = roomMapper.selectRoomById(id);
        if (room == null) {
            throw new BusinessException("会议室不存在");
        }
        return room;
    }

    /** 批量取会议室展示信息（会议详情/消息用）。 */
    Map<Long, String> roomNames(Set<Long> roomIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (roomIds == null) {
            return names;
        }
        new LinkedHashSet<>(roomIds).stream().filter(id -> id != null).forEach(id -> {
            Map<String, Object> room = roomMapper.selectRoomById(id);
            if (room != null) {
                names.put(id, String.valueOf(room.get("roomName")));
            }
        });
        return names;
    }
}
