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
 * M06 会议参会人表（{@code meeting_participant}）访问层。
 */
@Mapper
public interface MeetingParticipantMapper {

    @Select("""
            SELECT p.id,
                   p.meeting_id AS meetingId,
                   p.user_id AS userId,
                   u.username,
                   u.nickname,
                   d.dept_name AS deptName,
                   p.participant_role AS participantRole,
                   p.response_status AS responseStatus,
                   DATE_FORMAT(p.invited_time, '%Y-%m-%d %H:%i') AS invitedTime,
                   DATE_FORMAT(p.respond_time, '%Y-%m-%d %H:%i') AS respondTime,
                   p.remark
            FROM meeting_participant p
            JOIN sys_user u ON u.id = p.user_id
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE p.meeting_id = #{meetingId} AND p.deleted = 0
            ORDER BY p.participant_role = 'host' DESC, p.id
            """)
    List<Map<String, Object>> selectParticipants(@Param("meetingId") Long meetingId);

    @Select("""
            SELECT id, meeting_id AS meetingId, user_id AS userId, participant_role AS participantRole,
                   response_status AS responseStatus
            FROM meeting_participant
            WHERE meeting_id = #{meetingId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectParticipant(@Param("meetingId") Long meetingId, @Param("userId") Long userId);

    @Select("""
            SELECT user_id FROM meeting_participant
            WHERE meeting_id = #{meetingId} AND deleted = 0
            ORDER BY id
            """)
    List<Long> listParticipantIds(@Param("meetingId") Long meetingId);

    @Select("SELECT COUNT(*) FROM meeting_participant WHERE meeting_id = #{meetingId} AND deleted = 0")
    long countParticipants(@Param("meetingId") Long meetingId);

    @Insert("""
            INSERT INTO meeting_participant (meeting_id, user_id, participant_role, response_status, remark,
                                             create_by, update_by)
            VALUES (#{row.meetingId}, #{row.userId}, #{row.participantRole}, #{row.responseStatus},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertParticipant(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_participant
            SET response_status = #{responseStatus}, respond_time = NOW(), update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND user_id = #{userId} AND deleted = 0
            """)
    int updateResponse(
            @Param("meetingId") Long meetingId,
            @Param("userId") Long userId,
            @Param("responseStatus") String responseStatus,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE meeting_participant
            SET deleted = 1, update_by = #{operator}
            WHERE meeting_id = #{meetingId} AND user_id = #{userId} AND deleted = 0
            """)
    int softDeleteParticipant(
            @Param("meetingId") Long meetingId,
            @Param("userId") Long userId,
            @Param("operator") String operator
    );
}
