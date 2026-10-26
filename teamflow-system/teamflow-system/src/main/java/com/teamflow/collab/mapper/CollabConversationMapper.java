package com.teamflow.collab.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M09 会话 / 会话成员 / 已读游标访问层。
 *
 * <p>「我参与的会话」与成员在册判定统一为 {@code leave_time IS NULL}；
 * 已读状态按「会话 + 用户」记录游标（R4：避免逐条已读写放大）。</p>
 */
@Mapper
public interface CollabConversationMapper {

    @Insert("""
            INSERT INTO msg_conversation (conversation_type, title, creator_id, member_count, status,
                                         create_by, update_by)
            VALUES (#{row.conversationType}, #{row.title}, #{row.creatorId}, #{row.memberCount}, 'active',
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertConversation(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, conversation_type AS conversationType, title, creator_id AS creatorId,
                   member_count AS memberCount, last_message_id AS lastMessageId, last_message_time AS lastMessageTime,
                   status
            FROM msg_conversation
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectConversationById(@Param("id") Long id);

    /** 我参与的会话列表（含最近消息摘要与未读数）。 */
    @Select("""
            SELECT c.id,
                   c.conversation_type AS conversationType,
                   c.title,
                   c.member_count AS memberCount,
                   c.last_message_id AS lastMessageId,
                   DATE_FORMAT(c.last_message_time, '%Y-%m-%d %H:%i') AS lastMessageTime,
                   c.status,
                   (SELECT m.content FROM msg_message m WHERE m.id = c.last_message_id) AS lastMessage,
                   (SELECT COUNT(*) FROM msg_message m2
                     WHERE m2.conversation_id = c.id AND m2.deleted = 0
                       AND m2.id > COALESCE(r.last_read_message_id, 0)
                       AND m2.sender_id <> #{userId}) AS unreadCount
            FROM msg_conversation c
            JOIN msg_conversation_member cm
              ON cm.conversation_id = c.id AND cm.user_id = #{userId}
             AND cm.leave_time IS NULL AND cm.deleted = 0
            LEFT JOIN msg_read r
              ON r.conversation_id = c.id AND r.user_id = #{userId} AND r.deleted = 0
            WHERE c.deleted = 0
            ORDER BY COALESCE(c.last_message_time, c.create_time) DESC, c.id DESC
            """)
    List<Map<String, Object>> selectMyConversations(@Param("userId") Long userId);

    /** 直聊去重：两人都已参与且只有两名在册成员的 direct 会话。 */
    @Select("""
            SELECT c.id
            FROM msg_conversation c
            JOIN msg_conversation_member a
              ON a.conversation_id = c.id AND a.user_id = #{userId} AND a.leave_time IS NULL AND a.deleted = 0
            JOIN msg_conversation_member b
              ON b.conversation_id = c.id AND b.user_id = #{otherUserId} AND b.leave_time IS NULL AND b.deleted = 0
            WHERE c.conversation_type = 'direct' AND c.deleted = 0
              AND (SELECT COUNT(*) FROM msg_conversation_member m
                    WHERE m.conversation_id = c.id AND m.leave_time IS NULL AND m.deleted = 0) = 2
            ORDER BY c.id
            LIMIT 1
            """)
    Map<String, Object> selectDirectConversation(
            @Param("userId") Long userId, @Param("otherUserId") Long otherUserId);

    /** 批量取会话里除当前用户外的在册成员（单聊展示对方昵称用）。 */
    @Select("""
            <script>
            SELECT cm.conversation_id AS conversationId, cm.user_id AS userId
            FROM msg_conversation_member cm
            WHERE cm.conversation_id IN
            <foreach collection="conversationIds" item="conversationId" open="(" separator="," close=")">
                #{conversationId}
            </foreach>
              AND cm.user_id &lt;&gt; #{userId}
              AND cm.leave_time IS NULL AND cm.deleted = 0
            </script>
            """)
    List<Map<String, Object>> selectOtherMembers(
            @Param("conversationIds") List<Long> conversationIds, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO msg_conversation_member (conversation_id, user_id, member_role, join_time,
                                                 create_by, update_by)
            VALUES (#{conversationId}, #{userId}, #{memberRole}, NOW(), #{operator}, #{operator})
            """)
    int insertMember(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("memberRole") String memberRole,
            @Param("operator") String operator);

    @Select("""
            SELECT id, conversation_id AS conversationId, user_id AS userId, member_role AS memberRole,
                   join_time AS joinTime, leave_time AS leaveTime
            FROM msg_conversation_member
            WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectMember(
            @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM msg_conversation_member
            WHERE conversation_id = #{conversationId} AND leave_time IS NULL AND deleted = 0
            """)
    int countActiveMembers(@Param("conversationId") Long conversationId);

    @Update("""
            UPDATE msg_conversation
            SET member_count = #{memberCount}, update_by = #{operator}
            WHERE id = #{conversationId} AND deleted = 0
            """)
    int updateMemberCount(
            @Param("conversationId") Long conversationId,
            @Param("memberCount") int memberCount,
            @Param("operator") String operator);

    @Update("""
            UPDATE msg_conversation
            SET last_message_id = #{messageId}, last_message_time = #{messageTime}, update_by = #{operator}
            WHERE id = #{conversationId} AND deleted = 0
            """)
    int updateLastMessage(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId,
            @Param("messageTime") LocalDateTime messageTime,
            @Param("operator") String operator);

    @Select("""
            SELECT id, conversation_id AS conversationId, user_id AS userId,
                   last_read_message_id AS lastReadMessageId, read_time AS readTime
            FROM msg_read
            WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectRead(
            @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO msg_read (conversation_id, user_id, last_read_message_id, read_time, create_by, update_by)
            VALUES (#{conversationId}, #{userId}, #{lastReadMessageId}, NOW(), #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE
                last_read_message_id = GREATEST(last_read_message_id, VALUES(last_read_message_id)),
                read_time = NOW(),
                deleted = 0,
                update_by = VALUES(update_by)
            """)
    int upsertRead(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("lastReadMessageId") Long lastReadMessageId,
            @Param("operator") String operator);

    /** 我在全部会话里的未读消息总数。 */
    @Select("""
            SELECT COALESCE(SUM(x.cnt), 0) FROM (
                SELECT (SELECT COUNT(*) FROM msg_message m
                         WHERE m.conversation_id = c.id AND m.deleted = 0
                           AND m.id > COALESCE(r.last_read_message_id, 0)
                           AND m.sender_id <> #{userId}) AS cnt
                FROM msg_conversation c
                JOIN msg_conversation_member cm
                  ON cm.conversation_id = c.id AND cm.user_id = #{userId}
                 AND cm.leave_time IS NULL AND cm.deleted = 0
                LEFT JOIN msg_read r
                  ON r.conversation_id = c.id AND r.user_id = #{userId} AND r.deleted = 0
                WHERE c.deleted = 0
            ) x
            """)
    long countUnreadMessages(@Param("userId") Long userId);
}
