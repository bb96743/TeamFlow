package com.teamflow.collab.domain.dto;

/**
 * 会话已读请求：把已读游标推进到指定消息。
 *
 * @param lastReadMessageId 已读到的消息 ID（必须属于该会话）
 */
public record ConversationReadRequest(Long lastReadMessageId) {
}
