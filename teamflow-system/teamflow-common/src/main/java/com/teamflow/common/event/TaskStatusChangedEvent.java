package com.teamflow.common.event;

import java.io.Serial;
import java.time.Instant;
import java.util.UUID;

/**
 * 任务状态变更领域事件（M03 发布 → M02 订阅）。
 *
 * <p><b>契约字段由 L0 §9.1 {@code D-2026-10-25-05} 裁定</b>，不得随意增删：
 * {@code eventId}（UUID 幂等键）、{@code taskId}、{@code krId}（可空，任务未关联 KR 时为 null）、
 * {@code rootTaskId}、{@code isRoot}、{@code fromStatus}、{@code toStatus}、{@code operatorId}、
 * {@code occurredAt}。</p>
 *
 * <p><b>发布端（M03）约定</b>：在任务状态变更的**业务事务内**调用
 * {@code com.teamflow.framework.event.DomainEventPublisher#publish}；订阅端用
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 消费——事务回滚时事件不会投递。
 * M03 只发事件，**不得写 {@code okr_*} 表**；M02 只读事件、**不得查 {@code task_*} 表**。</p>
 *
 * <p><b>投影口径</b>：M02 用本事件维护派生投影 {@code okr_kr_task_link}，并只对
 * {@code isRoot = true} 的任务计入「任务驱动型 KR 进度」的分子/分母（D-2026-10-25-03）。</p>
 *
 * <p>不可变 record，可被 Jackson 3 直接序列化（未来切 MQ 零改动）。</p>
 */
public record TaskStatusChangedEvent(
        String eventId,
        Long taskId,
        Long krId,
        Long rootTaskId,
        boolean isRoot,
        String fromStatus,
        String toStatus,
        Long operatorId,
        Instant occurredAt
) implements DomainEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务"已完成"的状态值（M03 状态机里的完成态；投影/进度判定集中在此，避免各处硬编码）。 */
    public static final String STATUS_DONE = "done";

    /**
     * 发布端便捷工厂：自动生成 {@code eventId}（UUID）与 {@code occurredAt}（当前时间）。
     */
    public static TaskStatusChangedEvent of(
            Long taskId, Long krId, Long rootTaskId, boolean isRoot,
            String fromStatus, String toStatus, Long operatorId
    ) {
        return new TaskStatusChangedEvent(
                UUID.randomUUID().toString(), taskId, krId, rootTaskId, isRoot,
                fromStatus, toStatus, operatorId, Instant.now());
    }

    /** 本次变更是否把任务推进到"已完成"。 */
    public boolean completed() {
        return STATUS_DONE.equals(toStatus);
    }
}
