package com.teamflow.integration.service;

import com.teamflow.common.event.TaskDeletedEvent;
import com.teamflow.common.event.TaskKrChangedEvent;
import com.teamflow.common.event.TaskStatusChangedEvent;
import com.teamflow.collab.event.CollabMentionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 事件订阅端（2.4.4）。
 *
 * <p><b>只订阅、不生产</b>：本类不新建任何 {@code *Event} 类，只消费 M03 已发布的
 * {@link TaskStatusChangedEvent} / {@link TaskKrChangedEvent} / {@link TaskDeletedEvent}
 * 与 M09 已发布的 {@code CollabMentionEvent}；与既有订阅端（M02/M09）使用同一套
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 语义。</p>
 *
 * <p><b>载荷由事件内容构成</b>：只把事件字段整理成投递载荷，不额外查询任何业务表。</p>
 */
@Component
public class WebhookEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventSubscriber.class);

    private final WebhookDeliveryService deliveryService;

    public WebhookEventSubscriber(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** 任务状态变更（创建时 {@code fromStatus=null}，映射为 task.created）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        String type = event.fromStatus() == null ? "task.created" : "task.status_changed";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", event.taskId());
        data.put("krId", event.krId());
        data.put("rootTaskId", event.rootTaskId());
        data.put("isRoot", event.isRoot());
        data.put("fromStatus", event.fromStatus());
        data.put("toStatus", event.toStatus());
        data.put("operatorId", event.operatorId());
        data.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        dispatch(type, event.eventId(), data);
    }

    /** 任务改挂 KR。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskKrChanged(TaskKrChangedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", event.taskId());
        data.put("fromKrId", event.fromKrId());
        data.put("toKrId", event.toKrId());
        data.put("rootTaskId", event.rootTaskId());
        data.put("isRoot", event.isRoot());
        data.put("taskStatus", event.taskStatus());
        data.put("operatorId", event.operatorId());
        data.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        dispatch("task.kr_changed", event.eventId(), data);
    }

    /** 任务逻辑删除。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskDeleted(TaskDeletedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", event.taskId());
        data.put("krId", event.krId());
        data.put("rootTaskId", event.rootTaskId());
        data.put("isRoot", event.isRoot());
        data.put("operatorId", event.operatorId());
        data.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        dispatch("task.deleted", event.eventId(), data);
    }

    /** M09 已发布的提及事件（评论/消息保存后），用于外部系统接收提及通知。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollabMention(CollabMentionEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceType", event.sourceType());
        data.put("sourceId", event.sourceId());
        data.put("conversationId", event.conversationId());
        data.put("mentionedUserIds", event.mentionedUserIds());
        data.put("bizType", event.bizType());
        data.put("bizId", event.bizId());
        data.put("actionUrl", event.actionUrl());
        data.put("authorId", event.authorId());
        data.put("authorName", event.authorName());
        data.put("snippet", event.snippet());
        String eventId = "collab.mention:" + event.sourceType() + ":" + event.sourceId();
        dispatch("collab.mention", eventId, data);
    }

    private void dispatch(String eventType, String eventId, Map<String, Object> data) {
        try {
            int delivered = deliveryService.deliver(eventType, eventId, data);
            if (delivered > 0) {
                log.info("Webhook 事件已外发 type={} eventId={} 订阅数={}", eventType, eventId, delivered);
            }
        } catch (RuntimeException exception) {
            // 订阅方异常不得影响已提交的业务事务
            log.warn("Webhook 事件外发失败（不影响主流程） type={} eventId={} error={}",
                    eventType, eventId, exception.getMessage());
        }
    }
}
