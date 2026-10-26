package com.teamflow.collab.domain.dto;

import java.util.Map;

/**
 * 统一通知投递请求（{@code POST /api/collab/internal/notify}）：业务模块的唯一通知入口。
 *
 * <p>按 R1/R3：业务模块只给「接收人 + 模板编码 + 参数 + 业务链接」，**不接收裸 HTML**；
 * 正文由本模块模板渲染。需要同时进入个人待办收件箱时，本模块**只经 M10 统一投递入口**，
 * 不直接写待办数据（D-2026-10-26-11 结论四）。</p>
 *
 * @param receiverId       接收人用户 ID
 * @param templateCode     模板编码（如 mention / announcement / task_assigned / system）
 * @param params           模板参数
 * @param bizType          关联业务类型（可空）
 * @param bizId            关联业务单据 ID（可空）
 * @param actionUrl        回源链接（可空）
 * @param idempotencyKey   幂等键（必填；同一来源同一接收人只入一条通知）
 * @param requireTodo      是否同时投递到个人待办收件箱（只对已登记的待办业务类型生效）
 */
public record InternalNotifyRequest(
        Long receiverId,
        String templateCode,
        Map<String, Object> params,
        String bizType,
        Long bizId,
        String actionUrl,
        String idempotencyKey,
        Boolean requireTodo
) {
}
