package com.teamflow.collab.event;

/**
 * M09 内部待办投递事件：统一通知入口在请求结束后，按需把「需要用户行动」的通知
 * 经 M10 统一投递入口送进个人待办收件箱（D-2026-10-26-11 结论四）。
 */
public record CollabTodoPushEvent(
        Long receiverId,
        String bizType,
        Long bizId,
        String title,
        String actionUrl,
        String idempotencyKey
) {
}
