package com.teamflow.common.event;

import java.io.Serial;
import java.time.Instant;
import java.util.UUID;

/**
 * 任务（逻辑）删除领域事件（M03 发布 → M02 订阅）。
 *
 * <p><b>存在的原因</b>：任务删除是逻辑删除（M03 RULE R6），M02 的派生投影
 * {@code okr_kr_task_link} 不会自动消失。若不发本事件，被删任务会继续留在 KR 的任务分母里，
 * 使 KR 进度永久偏低且无法回正——与「改挂 KR 残留旧关联」同属必须堵住的脏数据路径。</p>
 *
 * <p><b>订阅端（M02）处理语义</b>：按 {@code taskId} 清理该任务在**所有 KR** 上的投影行
 * （任务可能改挂过 KR，投影可能不止一行），然后对受影响的每个 KR 按 COUNT 重算进度。</p>
 *
 * <p>不可变 record，可被 Jackson 3 直接序列化（未来切 MQ 零改动）。</p>
 */
public record TaskDeletedEvent(
        String eventId,
        Long taskId,
        Long krId,
        Long rootTaskId,
        boolean isRoot,
        Long operatorId,
        Instant occurredAt
) implements DomainEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发布端便捷工厂：自动生成 {@code eventId}（UUID）与 {@code occurredAt}（当前时间）。
     *
     * @param krId 任务删除时关联的 KR（可空；订阅端清理按 taskId 全量清理，不依赖本字段）
     */
    public static TaskDeletedEvent of(
            Long taskId, Long krId, Long rootTaskId, boolean isRoot, Long operatorId
    ) {
        return new TaskDeletedEvent(
                UUID.randomUUID().toString(), taskId, krId, rootTaskId, isRoot, operatorId, Instant.now());
    }
}
