package com.teamflow.collab.service;

import com.teamflow.collab.event.CollabMentionEvent;
import com.teamflow.collab.event.CollabTodoPushEvent;
import com.teamflow.personal.todo.TodoBizTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M09 提及通知投递端：**在内容事务提交后**处理站内通知与待办投递。
 *
 * <p>这样做满足两条约束：① 投递不阻塞业务事务（R2）；② 内容回滚时不产生"幽灵通知"。
 * 每一步写入都带幂等键，重复投递不会产生重复通知或重复待办。</p>
 */
@Component
public class CollabMentionNotifier {

    private static final Logger log = LoggerFactory.getLogger(CollabMentionNotifier.class);
    private static final int SNIPPET_MAX = 180;

    private final CollabNotifyWriter writer;
    private final CollabTodoPushAdapter todoPushAdapter;

    public CollabMentionNotifier(CollabNotifyWriter writer, CollabTodoPushAdapter todoPushAdapter) {
        this.writer = writer;
        this.todoPushAdapter = todoPushAdapter;
    }

    /** 评论/消息保存后：给被提及者写站内通知，并经 M10 投递待办。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMention(CollabMentionEvent event) {
        if (event.sourceId() == null || event.mentionedUserIds() == null || event.mentionedUserIds().isEmpty()) {
            return;
        }
        Set<Long> receivers = new LinkedHashSet<>(event.mentionedUserIds());
        String sourceName = "comment".equals(event.sourceType()) ? "评论" : "消息";
        String title = (event.authorName() == null ? "有人" : event.authorName()) + " 在" + sourceName + "中提到了你";
        String content = snippet(event.snippet());
        String bizType = "comment".equals(event.sourceType()) ? "collab_comment" : "collab_message";
        for (Long receiverId : receivers) {
            if (receiverId == null || receiverId.equals(event.authorId())) {
                continue;
            }
            String idempotencyKey = "mention:" + event.sourceType() + ":" + event.sourceId() + ":" + receiverId;
            try {
                writer.recordNotification(receiverId, "mention", title, content,
                        bizType, event.sourceId(), event.actionUrl(), idempotencyKey, "collab");
                writer.logDelivery("inapp", receiverId, bizType, event.sourceId(), idempotencyKey, "ok", null);
                CollabTodoPushAdapter.PushResult result = todoPushAdapter.pushMention(
                        receiverId, event.sourceId(), title, event.actionUrl(), idempotencyKey);
                writer.logDelivery("todo", receiverId, TodoBizTypes.COLLAB_MENTION, event.sourceId(),
                        idempotencyKey, result.ok() ? "ok" : "failed", result.reason());
                writer.markMentionStatus(idempotencyKey, result.ok() ? "notified" : "failed", "collab");
            } catch (RuntimeException exception) {
                log.error("提及通知处理失败：sourceType={} sourceId={} receiverId={}",
                        event.sourceType(), event.sourceId(), receiverId, exception);
                try {
                    writer.logDelivery("inapp", receiverId, bizType, event.sourceId(),
                            idempotencyKey, "failed", exception.getMessage());
                    writer.markMentionStatus(idempotencyKey, "failed", "collab");
                } catch (RuntimeException ignored) {
                    // 投递日志写入失败不影响主流程
                }
            }
        }
    }

    /** 统一通知入口请求的待办投递（事务提交后执行）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTodoPush(CollabTodoPushEvent event) {
        if (event.receiverId() == null || event.bizId() == null) {
            return;
        }
        if (!TodoBizTypes.COLLAB_MENTION.equals(event.bizType())) {
            writer.logDelivery("todo", event.receiverId(), event.bizType(), event.bizId(),
                    event.idempotencyKey(), "skipped", "该业务类型未登记待办投递（仅支持 collab_mention）");
            return;
        }
        CollabTodoPushAdapter.PushResult result = todoPushAdapter.pushMention(
                event.receiverId(), event.bizId(), event.title(), event.actionUrl(), event.idempotencyKey());
        writer.logDelivery("todo", event.receiverId(), event.bizType(), event.bizId(),
                event.idempotencyKey(), result.ok() ? "ok" : "failed", result.reason());
    }

    private String snippet(String text) {
        if (text == null) {
            return "有一条新的提及，点击查看。";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) {
            return "有一条新的提及，点击查看。";
        }
        return compact.length() <= SNIPPET_MAX ? compact : compact.substring(0, SNIPPET_MAX) + "…";
    }
}
