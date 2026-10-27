package com.teamflow.integration.mapper;

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
 * M12 Webhook 投递留痕表（{@code open_webhook_delivery}）访问层。
 *
 * <p>唯一键 {@code (webhook_id, event_id)} 是幂等闸门：同一订阅同一事件只落一行、只投递一次；
 * 重试复用同一行并累加 {@code attempts}。</p>
 */
@Mapper
public interface OpenWebhookDeliveryMapper {

    @Insert("""
            INSERT INTO open_webhook_delivery (webhook_id, event_id, event_type, payload, status,
                                               attempts, create_by, update_by)
            VALUES (#{row.webhookId}, #{row.eventId}, #{row.eventType}, #{row.payload}, 'pending',
                    1, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertDelivery(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT d.id,
                   d.webhook_id AS webhookId,
                   d.event_id AS eventId,
                   d.event_type AS eventType,
                   d.payload,
                   d.status,
                   d.http_status AS httpStatus,
                   d.attempts,
                   DATE_FORMAT(d.next_retry_at, '%Y-%m-%d %H:%i:%s') AS nextRetryAt,
                   d.error_message AS errorMessage,
                   d.response_snippet AS responseSnippet,
                   DATE_FORMAT(d.create_time, '%Y-%m-%d %H:%i:%s') AS createTime,
                   DATE_FORMAT(d.update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM open_webhook_delivery d
            WHERE d.webhook_id = #{webhookId} AND d.event_id = #{eventId} AND d.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectByWebhookAndEvent(@Param("webhookId") Long webhookId,
                                                @Param("eventId") String eventId);

    @Select("""
            SELECT d.id,
                   d.webhook_id AS webhookId,
                   d.event_id AS eventId,
                   d.event_type AS eventType,
                   d.payload,
                   d.status,
                   d.http_status AS httpStatus,
                   d.attempts,
                   DATE_FORMAT(d.next_retry_at, '%Y-%m-%d %H:%i:%s') AS nextRetryAt,
                   d.error_message AS errorMessage,
                   d.response_snippet AS responseSnippet,
                   DATE_FORMAT(d.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM open_webhook_delivery d
            WHERE d.webhook_id = #{webhookId} AND d.deleted = 0
            ORDER BY d.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectByWebhook(@Param("webhookId") Long webhookId, @Param("limit") int limit);

    /** 重试用：投递行 + 订阅的目标地址、密钥密文与状态（内部使用，不对外返回）。 */
    @Select("""
            SELECT d.id,
                   d.webhook_id AS webhookId,
                   d.event_id AS eventId,
                   d.event_type AS eventType,
                   d.payload,
                   d.attempts,
                   w.webhook_name AS webhookName,
                   w.target_url AS targetUrl,
                   w.secret_cipher AS secretCipher,
                   w.timeout_seconds AS timeoutSeconds,
                   w.status AS webhookStatus
            FROM open_webhook_delivery d
            JOIN open_webhook w ON w.id = d.webhook_id AND w.deleted = 0
            WHERE d.id = #{id} AND d.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectForRetry(@Param("id") Long id);

    @Update("""
            UPDATE open_webhook_delivery
            SET status = #{status},
                http_status = #{httpStatus},
                attempts = #{attempts},
                next_retry_at = #{nextRetryAt},
                error_message = #{errorMessage},
                response_snippet = #{responseSnippet},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateResult(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("httpStatus") Integer httpStatus,
                     @Param("attempts") Integer attempts,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt,
                     @Param("errorMessage") String errorMessage,
                     @Param("responseSnippet") String responseSnippet,
                     @Param("operator") String operator);

    @Update("UPDATE open_webhook_delivery SET deleted = 1, update_by = #{operator}"
            + " WHERE webhook_id = #{webhookId} AND deleted = 0")
    int softDeleteByWebhook(@Param("webhookId") Long webhookId, @Param("operator") String operator);
}
