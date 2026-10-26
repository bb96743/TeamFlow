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
 * M06 日程事件表（{@code meeting_event}）访问层。
 */
@Mapper
public interface MeetingEventMapper {

    @Select("""
            <script>
            SELECT e.id,
                   e.event_title AS eventTitle,
                   e.event_type AS eventType,
                   e.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   e.dept_id AS deptId,
                   d.dept_name AS deptName,
                   e.meeting_id AS meetingId,
                   DATE_FORMAT(e.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(e.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   e.all_day AS allDay,
                   e.location,
                   e.description,
                   e.remind_minutes AS remindMinutes,
                   e.status,
                   e.source_type AS sourceType,
                   DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM meeting_event e
            LEFT JOIN sys_user u ON u.id = e.owner_id
            LEFT JOIN sys_dept d ON d.id = e.dept_id
            WHERE e.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR e.event_title LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{eventType} IS NULL OR #{eventType} = '' OR e.event_type = #{eventType})
              AND (#{fromTime} IS NULL OR e.end_time &gt; #{fromTime})
              AND (#{toTime} IS NULL OR e.start_time &lt; #{toTime})
              AND (
                  #{scope.allScope} = TRUE
                  OR e.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR e.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            ORDER BY e.start_time, e.id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectEvents(
            @Param("keyword") String keyword,
            @Param("eventType") String eventType,
            @Param("fromTime") String fromTime,
            @Param("toTime") String toTime,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM meeting_event e
            WHERE e.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR e.event_title LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{eventType} IS NULL OR #{eventType} = '' OR e.event_type = #{eventType})
              AND (#{fromTime} IS NULL OR e.end_time &gt; #{fromTime})
              AND (#{toTime} IS NULL OR e.start_time &lt; #{toTime})
              AND (
                  #{scope.allScope} = TRUE
                  OR e.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR e.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            </script>
            """)
    long countEvents(
            @Param("keyword") String keyword,
            @Param("eventType") String eventType,
            @Param("fromTime") String fromTime,
            @Param("toTime") String toTime,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            SELECT id, event_title AS eventTitle, event_type AS eventType, owner_id AS ownerId,
                   dept_id AS deptId, meeting_id AS meetingId,
                   DATE_FORMAT(start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(end_time, '%Y-%m-%d %H:%i') AS endTime,
                   all_day AS allDay, location, description, remind_minutes AS remindMinutes,
                   status, source_type AS sourceType, remark
            FROM meeting_event
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectEventById(@Param("id") Long id);

    @Select("""
            SELECT id, meeting_id AS meetingId, owner_id AS ownerId, status
            FROM meeting_event
            WHERE meeting_id = #{meetingId} AND owner_id = #{ownerId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectMeetingEvent(@Param("meetingId") Long meetingId, @Param("ownerId") Long ownerId);

    @Select("""
            SELECT id, owner_id AS ownerId, status FROM meeting_event
            WHERE meeting_id = #{meetingId} AND deleted = 0
            """)
    List<Map<String, Object>> selectEventsByMeeting(@Param("meetingId") Long meetingId);

    @Insert("""
            INSERT INTO meeting_event (event_title, event_type, owner_id, dept_id, meeting_id, start_time, end_time,
                                       all_day, location, description, remind_minutes, status, source_type,
                                       remark, create_by, update_by)
            VALUES (#{row.eventTitle}, #{row.eventType}, #{row.ownerId}, #{row.deptId}, #{row.meetingId},
                    #{row.startTime}, #{row.endTime}, #{row.allDay}, #{row.location}, #{row.description},
                    #{row.remindMinutes}, #{row.status}, #{row.sourceType}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertEvent(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_event
            SET event_title = #{row.eventTitle},
                event_type = #{row.eventType},
                dept_id = #{row.deptId},
                start_time = #{row.startTime},
                end_time = #{row.endTime},
                all_day = #{row.allDay},
                location = #{row.location},
                description = #{row.description},
                remind_minutes = #{row.remindMinutes},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateEvent(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_event
            SET status = 'cancelled', update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int cancelEvent(@Param("id") Long id, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_event
            SET status = 'cancelled', update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND deleted = 0
            """)
    int cancelByMeeting(@Param("meetingId") Long meetingId, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_event
            SET event_title = #{title}, start_time = #{startTime}, end_time = #{endTime}, update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND deleted = 0
            """)
    int updateMeetingEventTimes(
            @Param("meetingId") Long meetingId,
            @Param("title") String title,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE meeting_event
            SET status = 'cancelled', update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND owner_id = #{ownerId} AND deleted = 0
            """)
    int cancelParticipantEvent(
            @Param("meetingId") Long meetingId,
            @Param("ownerId") Long ownerId,
            @Param("operator") String operator
    );
}
