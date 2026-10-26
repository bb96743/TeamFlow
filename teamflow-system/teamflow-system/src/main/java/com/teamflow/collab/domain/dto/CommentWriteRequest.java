package com.teamflow.collab.domain.dto;

import java.util.List;

/**
 * 发表评论请求（多态挂载：同一张评论表按业务类型 + 业务单据挂载）。
 *
 * @param bizType          业务类型（如 task / document / meeting，由调用方给出）
 * @param bizId            业务单据 ID
 * @param parentId         父评论 ID（可空，0 表示顶层）
 * @param content          评论正文（必填；正文中的 @账号 会被解析为提及）
 * @param mentionedUserIds 明确指定的被提及用户（可空）
 */
public record CommentWriteRequest(
        String bizType,
        Long bizId,
        Long parentId,
        String content,
        List<Long> mentionedUserIds
) {
}
