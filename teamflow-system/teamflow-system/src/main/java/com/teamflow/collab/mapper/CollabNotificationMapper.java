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
 * M09 统一通知 / 提及记录 / 投递日志访问层。
 *
 * <p>三者职责分离：通知是**用户可见**的结果；提及记录是解析与投递状态的可追溯凭据；
 * 投递日志记录每个通道的成败（R2：投递失败只留痕、不回滚业务）。</p>
 */
@Mapper
public interface CollabNotificationMapper {

    // ------------------------------------------------------------------
    // 统一通知
    // ------------------------------------------------------------------

    @Insert("""
            INSERT INTO msg_notification (receiver_id, notify_type, title, content, biz_type, biz_id,
                                          action_url, idempotency_key, create_by, update_by)
            VALUES (#{row.receiverId}, #{row.notifyType}, #{row.title}, #{row.content}, #{row.bizType},
                    #{row.bizId}, #{row.actionUrl}, #{row.idempotencyKey}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertNotification(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, receiver_id AS receiverId, notify_type AS notifyType, title, content,
                   biz_type AS bizType, biz_id AS bizId, action_url AS actionUrl,
                   DATE_FORMAT(read_time, '%Y-%m-%d %H:%i') AS readTime,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM msg_notification
            WHERE idempotency_key = #{idempotencyKey} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            SELECT id, receiver_id AS receiverId, notify_type AS notifyType, title, content,
                   biz_type AS bizType, biz_id AS bizId, action_url AS actionUrl,
                   DATE_FORMAT(read_time, '%Y-%m-%d %H:%i') AS readTime,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM msg_notification
            WHERE receiver_id = #{receiverId} AND deleted = 0
              <if test="unreadOnly"> AND read_time IS NULL </if>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectNotifications(
            @Param("receiverId") Long receiverId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*) FROM msg_notification
            WHERE receiver_id = #{receiverId} AND deleted = 0
              AND read_time IS NULL
            """)
    long countUnreadNotifications(@Param("receiverId") Long receiverId);

    @Select("""
            SELECT COUNT(*) FROM msg_notification
            WHERE receiver_id = #{receiverId} AND deleted = 0
            """)
    long countNotifications(@Param("receiverId") Long receiverId);

    @Update("""
            UPDATE msg_notification
            SET read_time = #{readTime}, update_by = #{operator}
            WHERE id = #{id} AND receiver_id = #{receiverId} AND deleted = 0
            """)
    int markNotificationRead(
            @Param("id") Long id,
            @Param("receiverId") Long receiverId,
            @Param("readTime") LocalDateTime readTime,
            @Param("operator") String operator);

    @Update("""
            UPDATE msg_notification
            SET read_time = #{readTime}, update_by = #{operator}
            WHERE receiver_id = #{receiverId} AND read_time IS NULL AND deleted = 0
            """)
    int markAllRead(
            @Param("receiverId") Long receiverId,
            @Param("readTime") LocalDateTime readTime,
            @Param("operator") String operator);

    // ------------------------------------------------------------------
    // 提及记录
    // ------------------------------------------------------------------

    @Insert("""
            INSERT INTO msg_mention (source_type, source_id, conversation_id, mentioned_user_id, biz_type, biz_id,
                                     action_url, notify_status, idempotency_key, create_by, update_by)
            VALUES (#{row.sourceType}, #{row.sourceId}, #{row.conversationId}, #{row.mentionedUserId},
                    #{row.bizType}, #{row.bizId}, #{row.actionUrl}, 'pending', #{row.idempotencyKey},
                    #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE notify_status = notify_status
            """)
    int insertMention(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE msg_mention
            SET notify_status = #{notifyStatus}, update_by = #{operator}
            WHERE idempotency_key = #{idempotencyKey}
            """)
    int updateMentionStatus(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("notifyStatus") String notifyStatus,
            @Param("operator") String operator);

    @Select("""
            SELECT id, source_type AS sourceType, source_id AS sourceId, mentioned_user_id AS mentionedUserId,
                   notify_status AS notifyStatus, idempotency_key AS idempotencyKey
            FROM msg_mention
            WHERE source_type = #{sourceType} AND source_id = #{sourceId}
            ORDER BY id
            """)
    List<Map<String, Object>> selectMentionsBySource(
            @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    /**
     * 全仓门禁自检：本模块源码/SQL 不得直接写入个人待办表（通知只投递不代劳）。
     * 这里只做「本模块是否存在提及记录」的统计，供测试断言使用。
     */
    @Select("SELECT COUNT(*) FROM msg_mention WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    int countMentions(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    // ------------------------------------------------------------------
    // 投递日志
    // ------------------------------------------------------------------

    @Insert("""
            INSERT INTO msg_channel_log (channel, target_user_id, biz_type, biz_id, idempotency_key,
                                         status, reason, create_by, update_by)
            VALUES (#{row.channel}, #{row.targetUserId}, #{row.bizType}, #{row.bizId}, #{row.idempotencyKey},
                    #{row.status}, #{row.reason}, 'notify', 'notify')
            """)
    int insertChannelLog(@Param("row") Map<String, Object> row);

    @Select("""
            SELECT COUNT(*) FROM msg_channel_log
            WHERE channel = #{channel} AND idempotency_key = #{idempotencyKey} AND status = #{status}
            """)
    int countChannelLog(
            @Param("channel") String channel,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("status") String status);
}
