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
 * M12 Webhook 订阅表（{@code open_webhook}）访问层。
 *
 * <p>对外视图不返回密钥密文；只有内部投递与重试通过 {@link #selectForDelivery(Long)} /
 * {@link #selectEnabledForDelivery()} 读取密文并解密使用。</p>
 */
@Mapper
public interface OpenWebhookMapper {

    String VIEW_COLUMNS = """
            SELECT w.id,
                   w.webhook_code AS webhookCode,
                   w.webhook_name AS webhookName,
                   w.target_url AS targetUrl,
                   w.event_types AS eventTypes,
                   w.secret_hint AS secretHint,
                   CASE WHEN w.secret_cipher IS NULL OR w.secret_cipher = '' THEN 0 ELSE 1 END
                       AS secretConfigured,
                   w.timeout_seconds AS timeoutSeconds,
                   w.status,
                   DATE_FORMAT(w.last_delivery_time, '%Y-%m-%d %H:%i:%s') AS lastDeliveryTime,
                   w.last_delivery_status AS lastDeliveryStatus,
                   w.last_delivery_message AS lastDeliveryMessage,
                   w.remark,
                   DATE_FORMAT(w.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(w.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM open_webhook w
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM open_webhook w
            WHERE w.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (w.webhook_code LIKE CONCAT('%', #{keyword}, '%') OR w.webhook_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">AND w.status = #{status}</if>
            </script>
            """)
    long countWebhooks(@Param("keyword") String keyword, @Param("status") String status);

    @Select("""
            <script>
            """ + VIEW_COLUMNS + """
            WHERE w.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (w.webhook_code LIKE CONCAT('%', #{keyword}, '%') OR w.webhook_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">AND w.status = #{status}</if>
            ORDER BY w.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectWebhooks(@Param("keyword") String keyword, @Param("status") String status,
                                             @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(VIEW_COLUMNS + " WHERE w.id = #{id} AND w.deleted = 0 LIMIT 1")
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("SELECT id FROM open_webhook WHERE webhook_code = #{code} AND deleted = 0"
            + " AND (#{excludeId} IS NULL OR id <> #{excludeId}) LIMIT 1")
    Long selectIdByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    /** 内部投递用：含密钥密文与超时配置（不对外返回）。 */
    @Select("""
            SELECT w.id,
                   w.webhook_code AS webhookCode,
                   w.webhook_name AS webhookName,
                   w.target_url AS targetUrl,
                   w.event_types AS eventTypes,
                   w.secret_cipher AS secretCipher,
                   w.timeout_seconds AS timeoutSeconds
            FROM open_webhook w
            WHERE w.id = #{id} AND w.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectForDelivery(@Param("id") Long id);

    /** 所有启用订阅（内部投递用，含密钥密文）。 */
    @Select("""
            SELECT w.id,
                   w.webhook_code AS webhookCode,
                   w.webhook_name AS webhookName,
                   w.target_url AS targetUrl,
                   w.event_types AS eventTypes,
                   w.secret_cipher AS secretCipher,
                   w.timeout_seconds AS timeoutSeconds
            FROM open_webhook w
            WHERE w.deleted = 0 AND w.status = 'enabled'
            ORDER BY w.id
            """)
    List<Map<String, Object>> selectEnabledForDelivery();

    @Insert("""
            INSERT INTO open_webhook (webhook_code, webhook_name, target_url, event_types, secret_cipher,
                                      secret_hint, timeout_seconds, status, remark, create_by, update_by)
            VALUES (#{row.webhookCode}, #{row.webhookName}, #{row.targetUrl}, #{row.eventTypes},
                    #{row.secretCipher}, #{row.secretHint}, #{row.timeoutSeconds}, #{row.status},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertWebhook(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE open_webhook
            SET webhook_name = #{row.webhookName},
                target_url = #{row.targetUrl},
                event_types = #{row.eventTypes},
                secret_cipher = #{row.secretCipher},
                secret_hint = #{row.secretHint},
                timeout_seconds = #{row.timeoutSeconds},
                status = #{row.status},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateWebhook(@Param("id") Long id, @Param("row") Map<String, Object> row,
                      @Param("operator") String operator);

    @Update("""
            UPDATE open_webhook
            SET last_delivery_time = #{time},
                last_delivery_status = #{status},
                last_delivery_message = #{message},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateDeliveryResult(@Param("id") Long id, @Param("status") String status,
                             @Param("message") String message, @Param("time") LocalDateTime time,
                             @Param("operator") String operator);

    @Update("UPDATE open_webhook SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id, @Param("operator") String operator);
}
