package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M11 会话表访问层。
 */
@Mapper
public interface AiSessionMapper {

    @Insert("""
            INSERT INTO ai_session (session_title, session_type, owner_id, owner_name, status,
                                    message_count, create_by, update_by)
            VALUES (#{row.sessionTitle}, #{row.sessionType}, #{row.ownerId}, #{row.ownerName}, 'active',
                    0, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertSession(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   session_title AS sessionTitle,
                   session_type AS sessionType,
                   owner_id AS ownerId,
                   owner_name AS ownerName,
                   status,
                   message_count AS messageCount,
                   DATE_FORMAT(last_message_time, '%Y-%m-%d %H:%i:%s') AS lastMessageTime,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM ai_session
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectSessionById(@Param("id") Long id);

    @Select("""
            SELECT id,
                   session_title AS sessionTitle,
                   session_type AS sessionType,
                   owner_id AS ownerId,
                   owner_name AS ownerName,
                   status,
                   message_count AS messageCount,
                   DATE_FORMAT(last_message_time, '%Y-%m-%d %H:%i:%s') AS lastMessageTime,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM ai_session
            WHERE owner_id = #{ownerId} AND deleted = 0
            ORDER BY COALESCE(last_message_time, create_time) DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Map<String, Object>> selectSessions(@Param("ownerId") Long ownerId,
                                             @Param("offset") int offset,
                                             @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*) FROM ai_session WHERE owner_id = #{ownerId} AND deleted = 0
            """)
    long countSessions(@Param("ownerId") Long ownerId);

    @Update("""
            UPDATE ai_session
            SET message_count = message_count + #{delta},
                last_message_time = NOW(),
                session_title = CASE WHEN session_title IS NULL OR session_title = '' THEN #{title} ELSE session_title END,
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int touchSession(@Param("id") Long id,
                     @Param("delta") int delta,
                     @Param("title") String title,
                     @Param("operator") String operator);
}
