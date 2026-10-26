package com.teamflow.collab.service;

import com.teamflow.collab.mapper.CollabNotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M09 通知写入端（独立事务）。
 *
 * <p><b>为什么每步独立事务</b>：投递链路按 R2 要求「失败只记日志、不回滚业务」。
 * 通知入库、提及状态、投递日志各自独立提交，投递失败不会把已成功的部分拖回滚；
 * 同时每条写入都有幂等键，重试不会产生重复通知。</p>
 */
@Service
public class CollabNotifyWriter {

    private final CollabNotificationMapper notificationMapper;

    public CollabNotifyWriter(CollabNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /** 幂等写入一条通知；已存在同幂等键时直接返回既有 ID。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordNotification(
            Long receiverId, String notifyType, String title, String content,
            String bizType, Long bizId, String actionUrl, String idempotencyKey, String operator
    ) {
        Map<String, Object> existing = notificationMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return numberAsLong(existing.get("id"));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("receiverId", receiverId);
        row.put("notifyType", notifyType);
        row.put("title", title);
        row.put("content", content);
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("actionUrl", actionUrl);
        row.put("idempotencyKey", idempotencyKey);
        notificationMapper.insertNotification(row, operator);
        return numberAsLong(row.get("id"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMentionStatus(String idempotencyKey, String notifyStatus, String operator) {
        notificationMapper.updateMentionStatus(idempotencyKey, notifyStatus, operator);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDelivery(
            String channel, Long targetUserId, String bizType, Long bizId,
            String idempotencyKey, String status, String reason
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", channel);
        row.put("targetUserId", targetUserId);
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("idempotencyKey", idempotencyKey);
        row.put("status", status);
        row.put("reason", reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500));
        notificationMapper.insertChannelLog(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNotificationRead(Long id, Long receiverId, String operator) {
        notificationMapper.markNotificationRead(id, receiverId, LocalDateTime.now(), operator);
    }

    private Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
