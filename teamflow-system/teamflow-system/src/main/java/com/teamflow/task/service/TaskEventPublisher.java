package com.teamflow.task.service;

import com.teamflow.common.event.TaskDeletedEvent;
import com.teamflow.common.event.TaskKrChangedEvent;
import com.teamflow.common.event.TaskStatusChangedEvent;
import com.teamflow.framework.event.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * M03 领域事件发布出口（R3）：把任务侧的业务动作翻译成 {@code com.teamflow.common.event} 契约事件。
 *
 * <p><b>边界</b>（L0 §9.1 {@code D-2026-10-25-05}）：本模块只发事件，<b>绝不写 {@code okr_*} 表</b>，
 * 也不在事务内调用 M02 的服务/接口；M02 订阅端在 {@code AFTER_COMMIT} 消费并维护自己的派生投影。
 * 事件必须在业务事务内发布——事务回滚时订阅端不会被调用，避免"任务没落库但 KR 已回写"。</p>
 */
@Component
public class TaskEventPublisher {

    private final DomainEventPublisher publisher;

    public TaskEventPublisher(DomainEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 任务状态变更（含创建时的 null → 初始状态、指派导致的 pool → todo）。 */
    public void publishStatusChanged(
            Long taskId, Long krId, Long rootTaskId, boolean isRoot,
            String fromStatus, String toStatus, Long operatorId
    ) {
        publisher.publish(TaskStatusChangedEvent.of(
                taskId, krId, rootTaskId, isRoot, fromStatus, toStatus, operatorId));
    }

    /**
     * 任务改挂 KR：补 G-3 遗留事件，用于让 M02 清理旧 KR 投影并重算两个 KR 的进度，
     * 否则旧 KR 的任务驱动型进度会永久虚高。
     */
    public void publishKrChanged(
            Long taskId, Long fromKrId, Long toKrId, Long rootTaskId, boolean isRoot,
            String taskStatus, Long operatorId
    ) {
        publisher.publish(TaskKrChangedEvent.of(
                taskId, fromKrId, toKrId, rootTaskId, isRoot, taskStatus, operatorId));
    }

    /** 任务逻辑删除：补 G-3 遗留事件，用于让 M02 清理该任务在所有 KR 上的投影行。 */
    public void publishDeleted(Long taskId, Long krId, Long rootTaskId, boolean isRoot, Long operatorId) {
        publisher.publish(TaskDeletedEvent.of(taskId, krId, rootTaskId, isRoot, operatorId));
    }
}
