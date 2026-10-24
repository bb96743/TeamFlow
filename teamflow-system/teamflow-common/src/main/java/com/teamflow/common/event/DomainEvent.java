package com.teamflow.common.event;

import java.io.Serializable;
import java.time.Instant;

/**
 * 领域事件契约基接口（G-3）。
 *
 * <p>所有跨模块领域事件都实现本接口。契约放在 {@code teamflow-common}，是为了让发布方（M03）
 * 与订阅方（M02/M05/M09/M10）**只依赖契约、互不依赖实现**（L0 §9.1 {@code D-2026-10-25-05} 结论三）。</p>
 *
 * <p><b>可序列化</b>：实现类必须是不可变、可被 Jackson 3 序列化的结构（record 优先），
 * 且实现 {@link Serializable}。这样将来若把 Spring Event 换成 MQ，事件体无需改动，只需换传输层。</p>
 */
public interface DomainEvent extends Serializable {

    /**
     * 幂等键：同一业务事件重复投递时必须保持不变（UUID 字符串）。
     * 订阅方以它做第一层去重（见 {@code okr_task_event_log.event_id} 唯一键）。
     */
    String eventId();

    /**
     * 事件发生时间（UTC {@link Instant}）。跨进程/切 MQ 后语义不变，不依赖 JVM 时区。
     */
    Instant occurredAt();

    /** 事件类型名，供 MQ routing 与日志检索；默认取实现类简单类名。 */
    default String eventType() {
        return getClass().getSimpleName();
    }
}
