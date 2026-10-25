package com.teamflow.okr.service;

import com.teamflow.common.event.TaskDeletedEvent;
import com.teamflow.common.event.TaskKrChangedEvent;
import com.teamflow.common.event.TaskStatusChangedEvent;
import com.teamflow.okr.mapper.OkrKeyResultMapper;
import com.teamflow.okr.mapper.OkrPlatformMapper;
import com.teamflow.okr.mapper.OkrTaskEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M02 任务事件订阅端（G-3）：消费 M03 的 {@link TaskStatusChangedEvent}，维护派生投影并回写 KR 进度。
 *
 * <p><b>链路</b>：M03 在任务状态变更的事务内发布事件 → 本监听器在**事务提交后**
 * （{@link TransactionPhase#AFTER_COMMIT}）执行；事务回滚则不会被调用。</p>
 *
 * <p><b>本期补齐的两个 G-3 遗留事件</b>（M03 开工时按 G-3 交付说明一并补，堵脏数据路径）：</p>
 * <ul>
 *   <li>{@link TaskKrChangedEvent}：任务改挂 KR → 清理旧 KR 投影并重算旧 KR，再写新 KR 投影并重算新 KR；</li>
 *   <li>{@link TaskDeletedEvent}：任务逻辑删除 → 按 taskId 清理其所有 KR 投影行并逐个重算。</li>
 * </ul>
 * 若无这两个消费，改挂/删除后 {@code okr_kr_task_link} 会残留旧关联，KR 进度永久虚高/偏低。
 * 三个监听方法均只读写 M02 自有表，**不查 {@code task_*} 表**（D-2026-10-25-05）。
 *
 * <p><b>幂等（M02 RULE R3，双重去重）</b>：</p>
 * <ol>
 *   <li>{@code eventId}：写入 {@code okr_task_event_log.event_id}（唯一键），重复投递直接跳过；</li>
 *   <li>业务键 {@code (taskId, krId)}：{@code okr_kr_task_link} 唯一键，同一任务对同一 KR 只有一行，
 *       状态覆盖而非累加；进度按投影表 COUNT 重算（计算式而非累加式）。</li>
 * </ol>
 *
 * <p><b>数据边界</b>：只写 M02 自有表（{@code okr_kr_task_link} / {@code okr_task_event_log} /
 * {@code okr_key_result} / {@code okr_progress_log}），**不查 {@code task_*} 表**；
 * 任务状态完全来自事件（L0 §4.3、§9.1 {@code D-2026-10-25-05}）。</p>
 *
 * <p><b>进度口径</b>（{@code D-2026-10-25-03}）：任务驱动型 = 已完成顶层任务数 / 关联顶层任务总数，
 * 子任务（{@code isRoot=false}）只入投影、不计入分子分母；回写写 {@code okr_progress_log} 且
 * {@code progress_source=task_writeback}。</p>
 *
 * <p><b>失败处理</b>：本监听器在独立事务（{@code REQUIRES_NEW}）中执行；消费异常记录 error 日志与
 * 事件日志（{@code process_result=failed}），**不向发布方抛出**（原业务事务已提交，不能回滚）。
 * 本期不做重试/Outbox，事件丢失的兜底留到 M4 前评估（D-05 回退条件）。</p>
 */
@Component
public class OkrTaskEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(OkrTaskEventSubscriber.class);
    /** 事件里没有可用操作人时的兜底账号（系统保留名，不会与真实用户冲突）。 */
    private static final String EVENT_OPERATOR_FALLBACK = "m03:event";
    private static final int REASON_MAX_LENGTH = 480;

    private final OkrTaskEventMapper eventMapper;
    private final OkrKeyResultMapper keyResultMapper;
    private final OkrKeyResultService keyResultService;
    private final OkrPlatformMapper platformMapper;

    public OkrTaskEventSubscriber(
            OkrTaskEventMapper eventMapper,
            OkrKeyResultMapper keyResultMapper,
            OkrKeyResultService keyResultService,
            OkrPlatformMapper platformMapper
    ) {
        this.eventMapper = eventMapper;
        this.keyResultMapper = keyResultMapper;
        this.keyResultService = keyResultService;
        this.platformMapper = platformMapper;
    }

    /**
     * 消费任务状态变更事件（事务提交后、独立事务执行）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        try {
            handle(event);
        } catch (DuplicateKeyException duplicate) {
            log.info("任务事件重复投递，已按 eventId 幂等跳过：eventId={}", event.eventId());
        } catch (RuntimeException exception) {
            log.error("任务事件处理失败：eventId={} taskId={} krId={}",
                    event.eventId(), event.taskId(), event.krId(), exception);
            markResultQuietly(event.eventId(), "failed", null, truncate(exception.getMessage()));
        }
    }

    /**
     * 消费任务改挂 KR 事件（补 G-3 遗留事件）：清理旧 KR 投影 → 重算旧 KR；写新 KR 投影 → 重算新 KR。
     *
     * <p>不消费本事件会导致任务从 KR-A 改挂到 KR-B 后，KR-A 的投影行永久残留、进度永久虚高——
     * 这正是 G-3 交付时明确要求 M03 开工时一并补的脏数据路径。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskKrChanged(TaskKrChangedEvent event) {
        try {
            handleKrChanged(event);
        } catch (DuplicateKeyException duplicate) {
            log.info("任务改挂事件重复投递，已按 eventId 幂等跳过：eventId={}", event.eventId());
        } catch (RuntimeException exception) {
            log.error("任务改挂事件处理失败：eventId={} taskId={} fromKrId={} toKrId={}",
                    event.eventId(), event.taskId(), event.fromKrId(), event.toKrId(), exception);
            markResultQuietly(event.eventId(), "failed", null, truncate(exception.getMessage()));
        }
    }

    /**
     * 消费任务删除事件（补 G-3 遗留事件）：按 taskId 清理其在**所有 KR** 上的投影行，并重算受影响的 KR。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskDeleted(TaskDeletedEvent event) {
        try {
            handleDeleted(event);
        } catch (DuplicateKeyException duplicate) {
            log.info("任务删除事件重复投递，已按 eventId 幂等跳过：eventId={}", event.eventId());
        } catch (RuntimeException exception) {
            log.error("任务删除事件处理失败：eventId={} taskId={} krId={}",
                    event.eventId(), event.taskId(), event.krId(), exception);
            markResultQuietly(event.eventId(), "failed", null, truncate(exception.getMessage()));
        }
    }

    private void handle(TaskStatusChangedEvent event) {
        if (event.taskId() == null) {
            insertEventLog(event, "skipped_no_kr", "事件缺少 taskId，忽略");
            return;
        }
        if (event.krId() == null) {
            insertEventLog(event, "skipped_no_kr", "任务未关联 KR，无需回写");
            return;
        }

        // 第一层幂等：event_id 唯一键；重复投递在此抛 DuplicateKeyException 并被上层捕获跳过
        insertEventLog(event, "received", null);

        // 第二层幂等：业务键 (kr_id, task_id) 唯一，状态覆盖
        eventMapper.upsertTaskLink(
                event.krId(), event.taskId(), event.isRoot(), event.toStatus(), completedAt(event));

        Map<String, Object> keyResult = keyResultMapper.selectKeyResultById(event.krId());
        if (keyResult == null) {
            markResult(event.eventId(), "skipped_kr_missing", null, "KR 不存在或已删除，投影已记录");
            return;
        }
        if (!"task_driven".equals(String.valueOf(keyResult.get("krType")))) {
            markResult(event.eventId(), "skipped_not_task_driven", null, "非任务驱动型 KR，任务事件仅入投影");
            return;
        }

        int taskCount = eventMapper.countRootTasks(event.krId());
        int doneTaskCount = eventMapper.countDoneRootTasks(event.krId());
        String operator = resolveOperator(event.operatorId());
        keyResultService.applyTaskWriteback(
                event.krId(), doneTaskCount, taskCount, operator, buildReason(event, doneTaskCount, taskCount));

        Map<String, Object> after = keyResultMapper.selectKeyResultById(event.krId());
        markResult(event.eventId(), "applied", after == null ? null : decimal(after.get("progress")), null);
        log.info("任务事件回写完成：krId={} 顶层任务 {}/{}，progress={}",
                event.krId(), doneTaskCount, taskCount, after == null ? null : after.get("progress"));
    }

    // ------------------------------------------------------------------
    // G-3 遗留事件：任务改挂 KR / 任务删除（投影清理，堵脏数据）
    // ------------------------------------------------------------------

    private void handleKrChanged(TaskKrChangedEvent event) {
        if (event.taskId() == null) {
            insertEventLog(event.eventId(), event.eventType(), null, event.toKrId(), event.rootTaskId(),
                    event.isRoot(), null, event.taskStatus(), event.operatorId(), toLocalDateTime(event.occurredAt()),
                    "skipped_no_kr", "事件缺少 taskId，忽略");
            return;
        }
        insertEventLog(event.eventId(), event.eventType(), event.taskId(), event.toKrId(), event.rootTaskId(),
                event.isRoot(), null, event.taskStatus(), event.operatorId(), toLocalDateTime(event.occurredAt()),
                "received", null);

        // ① 清理旧 KR 投影并重算旧 KR（否则旧 KR 进度永久虚高）
        if (event.fromKrId() != null) {
            eventMapper.deleteTaskLink(event.fromKrId(), event.taskId());
            recalculate(event.fromKrId(), event.operatorId(),
                    "任务改挂 KR 后重算旧 KR（taskId=" + event.taskId() + " 已脱离 krId=" + event.fromKrId()
                            + "，eventId=" + event.eventId() + "）");
        }
        // ② 写入新 KR 投影并重算新 KR
        if (event.toKrId() != null) {
            eventMapper.upsertTaskLink(event.toKrId(), event.taskId(), event.isRoot(), event.taskStatus(),
                    completedAt(event.taskStatus(), event.occurredAt()));
            recalculate(event.toKrId(), event.operatorId(),
                    "任务改挂 KR 后重算新 KR（taskId=" + event.taskId() + " 已挂到 krId=" + event.toKrId()
                            + "，eventId=" + event.eventId() + "）");
        }
        markResult(event.eventId(), "applied", null,
                "任务改挂 KR：fromKrId=" + event.fromKrId() + "，toKrId=" + event.toKrId());
        log.info("任务改挂 KR 投影清理完成：taskId={} {}→{}", event.taskId(), event.fromKrId(), event.toKrId());
    }

    private void handleDeleted(TaskDeletedEvent event) {
        if (event.taskId() == null) {
            insertEventLog(event.eventId(), event.eventType(), null, event.krId(), event.rootTaskId(),
                    event.isRoot(), null, null, event.operatorId(), toLocalDateTime(event.occurredAt()),
                    "skipped_no_kr", "事件缺少 taskId，忽略");
            return;
        }
        insertEventLog(event.eventId(), event.eventType(), event.taskId(), event.krId(), event.rootTaskId(),
                event.isRoot(), null, null, event.operatorId(), toLocalDateTime(event.occurredAt()),
                "received", null);

        // 任务可能改挂过 KR，投影可能散落多行：按 taskId 全量清理后逐个重算受影响 KR
        List<Long> affectedKrIds = eventMapper.selectKrIdsByTaskId(event.taskId());
        eventMapper.deleteTaskLinksByTaskId(event.taskId());
        for (Long krId : affectedKrIds) {
            recalculate(krId, event.operatorId(),
                    "任务删除后清理投影并重算（taskId=" + event.taskId() + " 已删除，eventId="
                            + event.eventId() + "）");
        }
        markResult(event.eventId(), "applied", null,
                "任务删除：清理投影 KR=" + affectedKrIds + "（任务原 krId=" + event.krId() + "）");
        log.info("任务删除投影清理完成：taskId={} 受影响 KR={}", event.taskId(), affectedKrIds);
    }

    /** 按投影表 COUNT 重算指定 KR 的任务驱动型进度（非任务驱动型 / KR 不存在时安全跳过）。 */
    private void recalculate(Long krId, Long operatorId, String reason) {
        Map<String, Object> keyResult = keyResultMapper.selectKeyResultById(krId);
        if (keyResult == null) {
            log.info("KR 不存在或已删除，跳过重算：krId={}", krId);
            return;
        }
        if (!"task_driven".equals(String.valueOf(keyResult.get("krType")))) {
            log.info("非任务驱动型 KR，跳过重算：krId={}", krId);
            return;
        }
        int taskCount = eventMapper.countRootTasks(krId);
        int doneTaskCount = eventMapper.countDoneRootTasks(krId);
        keyResultService.applyTaskWriteback(krId, doneTaskCount, taskCount, resolveOperator(operatorId), reason);
    }

    private void insertEventLog(TaskStatusChangedEvent event, String processResult, String reason) {
        insertEventLog(event.eventId(), event.eventType(), event.taskId(), event.krId(), event.rootTaskId(),
                event.isRoot(), event.fromStatus(), event.toStatus(), event.operatorId(),
                toLocalDateTime(event.occurredAt()), processResult, reason);
    }

    private void insertEventLog(
            String eventId, String eventType, Long taskId, Long krId, Long rootTaskId, boolean isRoot,
            String fromStatus, String toStatus, Long operatorId, LocalDateTime occurredAt,
            String processResult, String reason
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", eventId);
        row.put("eventType", eventType);
        row.put("taskId", taskId);
        row.put("krId", krId);
        row.put("rootTaskId", rootTaskId);
        row.put("isRoot", isRoot);
        row.put("fromStatus", fromStatus);
        row.put("toStatus", toStatus);
        row.put("operatorId", operatorId);
        row.put("occurredAt", occurredAt);
        row.put("processResult", processResult);
        row.put("reason", truncate(reason));
        eventMapper.insertEventLog(row);
    }

    private void markResult(String eventId, String processResult, BigDecimal progressAfter, String reason) {
        eventMapper.markResult(eventId, processResult, progressAfter, truncate(reason));
    }

    private void markResultQuietly(String eventId, String processResult, BigDecimal progressAfter, String reason) {
        try {
            markResult(eventId, processResult, progressAfter, reason);
        } catch (RuntimeException markException) {
            log.warn("回写事件处理结果失败：eventId={}", eventId, markException);
        }
    }

    private String resolveOperator(Long operatorId) {
        if (operatorId == null) {
            return EVENT_OPERATOR_FALLBACK;
        }
        String username = platformMapper.selectUsernameById(operatorId);
        return username == null || username.isBlank() ? EVENT_OPERATOR_FALLBACK : username;
    }

    private String buildReason(TaskStatusChangedEvent event, int doneTaskCount, int taskCount) {
        return "M03 任务事件回写：taskId=" + event.taskId()
                + " " + event.fromStatus() + "→" + event.toStatus()
                + "，已完成顶层任务 " + doneTaskCount + "/" + taskCount
                + "（eventId=" + event.eventId() + "，子任务不计入分母）";
    }

    private LocalDateTime completedAt(TaskStatusChangedEvent event) {
        return event.completed() ? toLocalDateTime(event) : null;
    }

    private LocalDateTime completedAt(String taskStatus, Instant occurredAt) {
        return TaskStatusChangedEvent.STATUS_DONE.equals(taskStatus) ? toLocalDateTime(occurredAt) : null;
    }

    private LocalDateTime toLocalDateTime(TaskStatusChangedEvent event) {
        return toLocalDateTime(event.occurredAt());
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= REASON_MAX_LENGTH ? text : text.substring(0, REASON_MAX_LENGTH);
    }
}
