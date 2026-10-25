package com.teamflow.common.event;

import java.io.Serial;
import java.time.Instant;
import java.util.UUID;

/**
 * 任务改挂 KR（任务↔KR 关联变更）领域事件（M03 发布 → M02 订阅）。
 *
 * <p><b>存在的原因</b>：任务关联 KR 的权威数据在 M03 的 {@code task_task.kr_id}
 * （L0 §9.1 {@code D-2026-10-25-05} 结论一），M02 侧只有事件驱动的派生投影
 * {@code okr_kr_task_link}。若任务从 KR-A 改挂到 KR-B 而只发状态变更事件，
 * <b>KR-A 的投影行会永久残留</b>，导致 KR-A 的任务驱动型进度永久虚高（脏数据）。
 * 本事件就是清理旧投影的显式信号。</p>
 *
 * <p><b>订阅端（M02）处理语义</b>：</p>
 * <ol>
 *   <li>删除 {@code (fromKrId, taskId)} 的投影行，并按 COUNT 重算 {@code fromKrId} 的进度；</li>
 *   <li>按 {@code (toKrId, taskId)} 写入/覆盖投影行（{@code isRoot} / {@code taskStatus} 来自本事件），
 *       并按 COUNT 重算 {@code toKrId} 的进度；</li>
 *   <li>{@code fromKrId / toKrId} 任一为 {@code null} 表示「原本未关联 / 改为不关联」。</li>
 * </ol>
 *
 * <p>不可变 record，可被 Jackson 3 直接序列化（未来切 MQ 零改动）。</p>
 */
public record TaskKrChangedEvent(
        String eventId,
        Long taskId,
        Long fromKrId,
        Long toKrId,
        Long rootTaskId,
        boolean isRoot,
        String taskStatus,
        Long operatorId,
        Instant occurredAt
) implements DomainEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发布端便捷工厂：自动生成 {@code eventId}（UUID）与 {@code occurredAt}（当前时间）。
     *
     * @param taskStatus 任务当前状态（订阅端需要用它写新 KR 的投影行，避免状态信息缺失）
     */
    public static TaskKrChangedEvent of(
            Long taskId, Long fromKrId, Long toKrId, Long rootTaskId, boolean isRoot,
            String taskStatus, Long operatorId
    ) {
        return new TaskKrChangedEvent(
                UUID.randomUUID().toString(), taskId, fromKrId, toKrId, rootTaskId, isRoot,
                taskStatus, operatorId, Instant.now());
    }
}
