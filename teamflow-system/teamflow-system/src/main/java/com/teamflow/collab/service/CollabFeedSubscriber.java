package com.teamflow.collab.service;

import com.teamflow.collab.mapper.CollabFeedMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.event.TaskDeletedEvent;
import com.teamflow.common.event.TaskKrChangedEvent;
import com.teamflow.common.event.TaskStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M09 动态流事件订阅端（D-2026-10-26-11 结论五：动态流只由领域事件投影生成）。
 *
 * <p>三个约束：① **不轮询业务表**：动态内容只由事件字段 + 平台用户资料生成；
 * ② **不读取任务/目标/绩效等业务数据表**：任务标题等未随事件给出的信息不臆造，摘要用任务 ID 表达；
 * ③ **幂等**：以事件 {@code eventId} 唯一键去重，重复投递不会产生重复动态。</p>
 */
@Component
public class CollabFeedSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CollabFeedSubscriber.class);

    private final CollabFeedMapper feedMapper;
    private final CollabPlatformMapper platformMapper;

    public CollabFeedSubscriber(CollabFeedMapper feedMapper, CollabPlatformMapper platformMapper) {
        this.feedMapper = feedMapper;
        this.platformMapper = platformMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        boolean completed = "done".equals(event.toStatus());
        String action = completed ? "task_completed" : "task_status_changed";
        String summary = actorName(event.operatorId())
                + (completed ? " 完成了任务 #" : " 变更了任务 #") + event.taskId()
                + " 的状态（" + taskLabel(event.fromStatus()) + " → " + taskLabel(event.toStatus()) + "）";
        record(event.eventId(), event.eventType(), event.operatorId(), action,
                "task", event.taskId(), summary, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskDeleted(TaskDeletedEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        String summary = actorName(event.operatorId()) + " 删除了任务 #" + event.taskId();
        record(event.eventId(), event.eventType(), event.operatorId(), "task_deleted",
                "task", event.taskId(), summary, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskKrChanged(TaskKrChangedEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        String summary = actorName(event.operatorId()) + " 调整了任务 #" + event.taskId() + " 关联的目标";
        record(event.eventId(), event.eventType(), event.operatorId(), "task_kr_changed",
                "task", event.taskId(), summary, event.occurredAt());
    }

    private void record(String eventId, String eventType, Long actorId, String action,
                        String bizType, Long bizId, String summary, Instant occurredAt) {
        if (eventId == null || feedMapper.countByEventId(eventId) > 0) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", eventId);
        row.put("eventType", eventType);
        row.put("actorId", actorId);
        row.put("actorName", actorName(actorId));
        row.put("action", action);
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
        row.put("occurredAt", occurredAt == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(occurredAt, ZoneId.systemDefault()));
        try {
            feedMapper.insertFeed(row);
        } catch (DuplicateKeyException duplicate) {
            // 并发/重复投递：eventId 唯一键兜底，已存在即跳过
            log.info("动态流事件重复投递，已按事件幂等键跳过：eventId={}", eventId);
        }
    }

    private String actorName(Long actorId) {
        if (actorId == null) {
            return "系统";
        }
        Map<String, Object> brief = platformMapper.selectUserBrief(actorId);
        if (brief == null) {
            return "用户 #" + actorId;
        }
        Object nickname = brief.get("nickname");
        Object username = brief.get("username");
        return nickname == null ? String.valueOf(username) : String.valueOf(nickname);
    }

    private String taskLabel(String status) {
        return switch (status == null ? "" : status) {
            case "pool" -> "待认领";
            case "todo" -> "待办";
            case "in_progress" -> "进行中";
            case "done" -> "已完成";
            case "closed" -> "已关闭";
            default -> status == null ? "—" : status;
        };
    }
}
