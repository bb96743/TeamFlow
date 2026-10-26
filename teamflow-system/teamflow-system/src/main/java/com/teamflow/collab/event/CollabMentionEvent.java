package com.teamflow.collab.event;

import java.util.List;

/**
 * M09 内部提及事件：评论/消息保存成功后发布，由投递端在**事务提交后**处理通知与待办投递。
 *
 * <p>不放进公共事件契约包：这是模块内部的投递触发信号，不构成跨模块契约；
 * 跨模块通知统一走 {@code POST /api/collab/internal/notify}。</p>
 *
 * @param sourceType      来源类型：message / comment
 * @param sourceId        来源 ID（消息 ID 或评论 ID）
 * @param conversationId  会话 ID（消息来源时有值）
 * @param mentionedUserIds 被提及用户 ID
 * @param bizType         业务类型（评论来源时有值）
 * @param bizId           业务单据 ID（评论来源时有值）
 * @param actionUrl       回源链接
 * @param authorId        内容作者用户 ID
 * @param authorName      内容作者展示名
 * @param snippet         内容摘要（用于通知正文）
 */
public record CollabMentionEvent(
        String sourceType,
        Long sourceId,
        Long conversationId,
        List<Long> mentionedUserIds,
        String bizType,
        Long bizId,
        String actionUrl,
        Long authorId,
        String authorName,
        String snippet
) {
}
