package com.teamflow.meeting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M06 会议室预定表（{@code meeting_booking}）访问层：按占用区间做冲突检测。
 */
@Mapper
public interface MeetingBookingMapper {

    /** 与指定区间重叠的其它有效预定（半开区间：start &lt; 新 end 且 end &gt; 新 start）。 */
    @Select("""
            SELECT b.id AS bookingId,
                   b.room_id AS roomId,
                   r.room_name AS roomName,
                   b.booking_title AS bookingTitle,
                   DATE_FORMAT(b.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(b.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   b.booker_id AS bookerId,
                   u.nickname AS bookerName,
                   b.meeting_id AS meetingId,
                   m.meeting_title AS meetingTitle
            FROM meeting_booking b
            JOIN meeting_room r ON r.id = b.room_id
            LEFT JOIN sys_user u ON u.id = b.booker_id
            LEFT JOIN meeting_meeting m ON m.id = b.meeting_id AND m.deleted = 0
            WHERE b.deleted = 0
              AND b.status = 'booked'
              AND b.room_id = #{roomId}
              AND b.start_time < #{endTime}
              AND b.end_time > #{startTime}
              AND (#{excludeBookingId} IS NULL OR b.id <> #{excludeBookingId})
            ORDER BY b.start_time
            """)
    List<Map<String, Object>> selectConflicts(
            @Param("roomId") Long roomId,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("excludeBookingId") Long excludeBookingId
    );

    @Select("""
            SELECT b.id,
                   b.room_id AS roomId,
                   r.room_name AS roomName,
                   b.meeting_id AS meetingId,
                   b.booking_title AS bookingTitle,
                   b.booker_id AS bookerId,
                   DATE_FORMAT(b.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(b.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   b.status,
                   b.cancel_reason AS cancelReason,
                   DATE_FORMAT(b.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM meeting_booking b
            JOIN meeting_room r ON r.id = b.room_id
            WHERE b.id = #{id} AND b.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectBookingById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT b.id,
                   b.room_id AS roomId,
                   r.room_name AS roomName,
                   b.meeting_id AS meetingId,
                   b.booking_title AS bookingTitle,
                   b.booker_id AS bookerId,
                   u.nickname AS bookerName,
                   DATE_FORMAT(b.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(b.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   b.status,
                   DATE_FORMAT(b.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM meeting_booking b
            JOIN meeting_room r ON r.id = b.room_id
            LEFT JOIN sys_user u ON u.id = b.booker_id
            WHERE b.deleted = 0
              AND (#{roomId} IS NULL OR b.room_id = #{roomId})
              AND (#{status} IS NULL OR #{status} = '' OR b.status = #{status})
              AND (#{fromTime} IS NULL OR b.end_time &gt; #{fromTime})
              AND (#{toTime} IS NULL OR b.start_time &lt; #{toTime})
            ORDER BY b.start_time DESC, b.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> selectBookings(
            @Param("roomId") Long roomId,
            @Param("status") String status,
            @Param("fromTime") String fromTime,
            @Param("toTime") String toTime,
            @Param("limit") int limit
    );

    /** 会议室未结束的有效预定数（删除会议室前校验）。 */
    @Select("""
            SELECT COUNT(*) FROM meeting_booking
            WHERE room_id = #{roomId} AND status = 'booked' AND deleted = 0 AND end_time > NOW()
            """)
    long countActiveBookings(@Param("roomId") Long roomId);
    @Insert("""
            INSERT INTO meeting_booking (room_id, meeting_id, booking_title, booker_id, start_time, end_time,
                                         status, remark, create_by, update_by)
            VALUES (#{row.roomId}, #{row.meetingId}, #{row.bookingTitle}, #{row.bookerId}, #{row.startTime},
                    #{row.endTime}, #{row.status}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertBooking(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_booking
            SET status = 'cancelled', cancel_reason = #{reason}, cancel_by = #{operator},
                cancel_time = NOW(), update_by = #{operator}
            WHERE id = #{id} AND deleted = 0 AND status = 'booked'
            """)
    int cancelBooking(@Param("id") Long id, @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_booking
            SET status = 'cancelled', cancel_reason = #{reason}, cancel_by = #{operator},
                cancel_time = NOW(), update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND deleted = 0 AND status = 'booked'
            """)
    int cancelByMeeting(@Param("meetingId") Long meetingId, @Param("reason") String reason, @Param("operator") String operator);
}
