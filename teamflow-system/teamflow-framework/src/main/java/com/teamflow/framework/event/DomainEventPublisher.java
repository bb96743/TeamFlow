package com.teamflow.framework.event;

import com.teamflow.common.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 领域事件发布器（G-3）：统一封装 Spring 的 {@link ApplicationEventPublisher}。
 *
 * <p><b>发布时机</b>：必须在业务事务内发布。订阅方用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 消费，因此：</p>
 * <ul>
 *   <li>事务提交前不会投递（订阅方在提交后执行）；</li>
 *   <li>事务回滚时不投递（避免"任务没落库但 KR 已回写"的脏数据）。</li>
 * </ul>
 *
 * <p><b>M03 接入方式</b>（发布端只需这一行，不接触 M02 实现）：</p>
 * <pre>{@code
 * // 在任务状态变更的 @Transactional 方法内
 * domainEventPublisher.publish(TaskStatusChangedEvent.of(
 *         taskId, krId, rootTaskId, isRoot, fromStatus, toStatus, operatorId));
 * }</pre>
 *
 * <p>不引入 MQ：私有化单机部署（MySQL + Redis + 单 JAR）是项目既有叙事；M1 只要求"能回写"，
 * 不要求"跨重启不丢"。事件契约可序列化，若 M4 前出现事件丢失事故，可换 MQ/Outbox 而不改契约
 * （L0 §9.1 {@code D-2026-10-25-05} 结论四与回退条件）。</p>
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final ApplicationEventPublisher publisher;

    public DomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 发布一个领域事件。
     *
     * @param event 事件对象（不可为 null）
     */
    public void publish(DomainEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("领域事件不能为空");
        }
        log.info("发布领域事件 type={} eventId={} occurredAt={}",
                event.eventType(), event.eventId(), event.occurredAt());
        publisher.publishEvent(event);
    }
}
