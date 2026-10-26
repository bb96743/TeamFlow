package com.teamflow.collab.service;

import com.teamflow.personal.service.TodoPushService;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.personal.todo.TodoPushCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * M09 → M10 的**待办投递适配器**（D-2026-10-26-11 结论四：通知只投递不代劳）。
 *
 * <p>本类只调用 M10 的统一投递服务（与 {@code POST /api/personal/todos/internal/push} 同一实现），
 * **绝不直写个人待办数据**；投递失败只返回失败结果，由调用方记投递日志，不影响消息/评论本身。</p>
 *
 * <p>{@code bizType} 使用 M10 已登记的 {@code collab_mention}：该类型按 D-07 语义**没有 handler**，
 * 允许投递入库，在收件箱直接处理时会被拒绝并提示回源处理。</p>
 */
@Component
public class CollabTodoPushAdapter {

    private static final Logger log = LoggerFactory.getLogger(CollabTodoPushAdapter.class);

    private final TodoPushService todoPushService;

    public CollabTodoPushAdapter(TodoPushService todoPushService) {
        this.todoPushService = todoPushService;
    }

    /** 投递结果：成功与否 + 失败原因（用于写入投递日志）。 */
    public record PushResult(boolean ok, String reason) {
    }

    /**
     * 把一条「需要用户行动」的通知投递到个人待办收件箱。
     *
     * <p>本方法**不开启事务**：M10 的投递方法会自己开启事务，失败只回滚它自己的事务，
     * 不影响调用方已写入的通知与业务数据（R2 投递失败不回滚业务）。</p>
     */
    public PushResult pushMention(
            Long receiverId, Long sourceId, String title, String actionUrl, String idempotencyKey
    ) {
        try {
            todoPushService.push(new TodoPushCommand(
                    TodoBizTypes.COLLAB_MENTION,
                    sourceId,
                    receiverId,
                    "mention",
                    title,
                    actionUrl,
                    null,
                    "normal",
                    idempotencyKey));
            return new PushResult(true, null);
        } catch (RuntimeException exception) {
            log.warn("提及待办投递失败（不影响消息/评论）：receiverId={} sourceId={} reason={}",
                    receiverId, sourceId, exception.getMessage());
            return new PushResult(false, exception.getMessage());
        }
    }
}
