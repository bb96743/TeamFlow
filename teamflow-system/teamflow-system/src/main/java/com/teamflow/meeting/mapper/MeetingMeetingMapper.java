package com.teamflow.meeting.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M06 会议表（{@code meeting_meeting}）访问层。
 *
 * <p>本表**不存纪要正文**，只存 {@code minutes_page_id} 引用；正文在知识模块的页面中。</p>
 */
@Mapper
public interface MeetingMeetingMapper {

    @Select("""
            <script>
            SELECT m.id,
                   m.meeting_title AS meetingTitle,
                   m.template_id AS templateId,
                   t.template_name AS templateName,
                   m.room_id AS roomId,
                   r.room_name AS roomName,
                   m.organizer_id AS organizerId,
                   u.nickname AS organizerName,
                   DATE_FORMAT(m.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(m.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   m.location,
                   m.meeting_link AS meetingLink,
                   m.status,
                   m.minutes_page_id AS minutesPageId,
                   m.reschedule_count AS rescheduleCount,
                   DATE_FORMAT(m.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM meeting_meeting m
            LEFT JOIN meeting_room r ON r.id = m.room_id
            LEFT JOIN meeting_template t ON t.id = m.template_id
            LEFT JOIN sys_user u ON u.id = m.organizer_id
            WHERE m.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR m.meeting_title LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR m.status = #{status})
              AND (#{fromTime} IS NULL OR m.end_time &gt; #{fromTime})
              AND (#{toTime} IS NULL OR m.start_time &lt; #{toTime})
              AND (
                  #{scope.allScope} = TRUE
                  OR m.organizer_id = #{scope.userId}
                  OR EXISTS (
                      SELECT 1 FROM meeting_participant p
                      WHERE p.meeting_id = m.id AND p.user_id = #{scope.userId} AND p.deleted = 0
                  )
              )
            ORDER BY m.start_time DESC, m.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectMeetings(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("fromTime") String fromTime,
            @Param("toTime") String toTime,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM meeting_meeting m
            WHERE m.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR m.meeting_title LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR m.status = #{status})
              AND (#{fromTime} IS NULL OR m.end_time &gt; #{fromTime})
              AND (#{toTime} IS NULL OR m.start_time &lt; #{toTime})
              AND (
                  #{scope.allScope} = TRUE
                  OR m.organizer_id = #{scope.userId}
                  OR EXISTS (
                      SELECT 1 FROM meeting_participant p
                      WHERE p.meeting_id = m.id AND p.user_id = #{scope.userId} AND p.deleted = 0
                  )
              )
            </script>
            """)
    long countMeetings(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("fromTime") String fromTime,
            @Param("toTime") String toTime,
            @Param("scope") DataScopeCriteria scope
    );

    /** 参会人时间冲突：同一参会人在该时段已有其它未取消会议（R1 参会人时段校验）。 */
    @Select("""
            <script>
            SELECT m.id AS meetingId,
                   m.meeting_title AS meetingTitle,
                   DATE_FORMAT(m.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(m.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   p.user_id AS userId,
                   u.nickname AS nickname
            FROM meeting_meeting m
            JOIN meeting_participant p ON p.meeting_id = m.id AND p.deleted = 0
            LEFT JOIN sys_user u ON u.id = p.user_id
            WHERE m.deleted = 0
              AND m.status NOT IN ('cancelled')
              AND m.start_time &lt; #{endTime}
              AND m.end_time &gt; #{startTime}
              AND (#{excludeMeetingId} IS NULL OR m.id &lt;&gt; #{excludeMeetingId})
              AND p.user_id IN
              <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                  #{userId}
              </foreach>
            ORDER BY m.start_time
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectParticipantConflict(
            @Param("userIds") List<Long> userIds,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("excludeMeetingId") Long excludeMeetingId
    );
    @Select("""
            SELECT m.id,
                   m.meeting_title AS meetingTitle,
                   m.template_id AS templateId,
                   t.template_name AS templateName,
                   m.room_id AS roomId,
                   r.room_name AS roomName,
                   m.booking_id AS bookingId,
                   m.organizer_id AS organizerId,
                   u.nickname AS organizerName,
                   DATE_FORMAT(m.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(m.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   m.location,
                   m.meeting_link AS meetingLink,
                   m.agenda,
                   m.status,
                   m.minutes_page_id AS minutesPageId,
                   DATE_FORMAT(m.minutes_archived_time, '%Y-%m-%d %H:%i') AS minutesArchivedTime,
                   m.cancel_reason AS cancelReason,
                   m.cancel_by AS cancelBy,
                   DATE_FORMAT(m.cancel_time, '%Y-%m-%d %H:%i') AS cancelTime,
                   m.reschedule_count AS rescheduleCount,
                   m.remark,
                   DATE_FORMAT(m.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(m.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM meeting_meeting m
            LEFT JOIN meeting_room r ON r.id = m.room_id
            LEFT JOIN meeting_template t ON t.id = m.template_id
            LEFT JOIN sys_user u ON u.id = m.organizer_id
            WHERE m.id = #{id} AND m.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectMeetingById(@Param("id") Long id);

    @Insert("""
            INSERT INTO meeting_meeting (meeting_title, template_id, room_id, booking_id, organizer_id,
                                         start_time, end_time, location, meeting_link, agenda, status,
                                         remark, create_by, update_by)
            VALUES (#{row.meetingTitle}, #{row.templateId}, #{row.roomId}, #{row.bookingId}, #{row.organizerId},
                    #{row.startTime}, #{row.endTime}, #{row.location}, #{row.meetingLink}, #{row.agenda},
                    #{row.status}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMeeting(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_meeting
            SET meeting_title = #{row.meetingTitle},
                template_id = #{row.templateId},
                location = #{row.location},
                meeting_link = #{row.meetingLink},
                agenda = #{row.agenda},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMeeting(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    /** 创建流程：会议室预定成功后回填会议室/预定并转为已排期。 */
    @Update("""
            UPDATE meeting_meeting
            SET room_id = #{roomId}, booking_id = #{bookingId}, status = 'scheduled', update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int confirmMeeting(
            @Param("id") Long id,
            @Param("roomId") Long roomId,
            @Param("bookingId") Long bookingId,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE meeting_meeting
            SET start_time = #{row.startTime},
                end_time = #{row.endTime},
                room_id = #{row.roomId},
                booking_id = #{row.bookingId},
                reschedule_count = reschedule_count + 1,
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int rescheduleMeeting(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_meeting
            SET status = 'cancelled', cancel_reason = #{reason}, cancel_by = #{operator},
                cancel_time = NOW(), update_by = #{operator}
            WHERE id = #{id} AND deleted = 0 AND status NOT IN ('ended', 'cancelled')
            """)
    int cancelMeeting(@Param("id") Long id, @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_meeting
            SET status = 'ended', update_by = #{operator}
            WHERE id = #{id} AND deleted = 0 AND status NOT IN ('cancelled', 'ended')
            """)
    int endMeeting(@Param("id") Long id, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_meeting
            SET minutes_page_id = #{pageId}, minutes_archived_time = NOW(), update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMinutesPage(@Param("id") Long id, @Param("pageId") Long pageId, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_meeting
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteMeeting(@Param("id") Long id, @Param("operator") String operator);
}
