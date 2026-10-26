package com.teamflow.collab.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M09 站内消息访问层：消息是**全站唯一消息存储**，只在会话内追加，不改写历史。
 */
@Mapper
public interface CollabMessageMapper {

    @Insert("""
            INSERT INTO msg_message (conversation_id, sender_id, content_type, content, action_url,
                                     biz_type, biz_id, mentioned_user_ids, create_by, update_by)
            VALUES (#{row.conversationId}, #{row.senderId}, #{row.contentType}, #{row.content}, #{row.actionUrl},
                    #{row.bizType}, #{row.bizId}, #{row.mentionedUserIds}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMessage(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT m.id,
                   m.conversation_id AS conversationId,
                   m.sender_id AS senderId,
                   u.nickname AS senderName,
                   u.username AS senderUsername,
                   m.content_type AS contentType,
                   m.content,
                   m.action_url AS actionUrl,
                   m.biz_type AS bizType,
                   m.biz_id AS bizId,
                   m.mentioned_user_ids AS mentionedUserIds,
                   DATE_FORMAT(m.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM msg_message m
            LEFT JOIN sys_user u ON u.id = m.sender_id
            WHERE m.conversation_id = #{conversationId} AND m.deleted = 0
            ORDER BY m.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Map<String, Object>> selectMessages(
            @Param("conversationId") Long conversationId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*) FROM msg_message
            WHERE conversation_id = #{conversationId} AND deleted = 0
            """)
    long countMessages(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT id, conversation_id AS conversationId, sender_id AS senderId, content,
                   biz_type AS bizType, biz_id AS bizId
            FROM msg_message
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectMessageById(@Param("id") Long id);
}
