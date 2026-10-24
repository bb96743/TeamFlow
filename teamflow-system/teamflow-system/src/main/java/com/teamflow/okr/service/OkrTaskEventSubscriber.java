package com.teamflow.okr.service;

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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M02 任务事件订阅端（G-3）：消费 M03 的 {@link TaskStatusChangedEvent}，维护派生投影并回写 KR 进度。
 *
 * <p><b>链路</b>：M03 在任务状态变更的事务内发布事件 → 本监听器在**事务提交后**
 * （{@link TransactionPhase#AFTER_COMMIT}）执行；事务回滚则不会被调用。</p>
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

    private void insertEventLog(TaskStatusChangedEvent event, String processResult, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", event.eventId());
        row.put("eventType", event.eventType());
        row.put("taskId", event.taskId());
        row.put("krId", event.krId());
        row.put("rootTaskId", event.rootTaskId());
        row.put("isRoot", event.isRoot());
        row.put("fromStatus", event.fromStatus());
        row.put("toStatus", event.toStatus());
        row.put("operatorId", event.operatorId());
        row.put("occurredAt", toLocalDateTime(event));
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

    private LocalDateTime toLocalDateTime(TaskStatusChangedEvent event) {
        return event.occurredAt() == null ? null
                : LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault());
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
