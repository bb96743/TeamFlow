package com.teamflow.collab.domain.dto;

import java.util.List;

/**
 * 发送站内信请求。
 *
 * @param content         消息正文（必填）
 * @param actionUrl       回源链接（可空）
 * @param bizType         关联业务类型（可空；只存标识，不复制业务数据）
 * @param bizId           关联业务单据 ID（可空）
 * @param mentionedUserIds 明确指定的被提及用户（可空；正文里的 @账号 也会被解析）
 */
public record MessageSendRequest(
        String content,
        String actionUrl,
        String bizType,
        Long bizId,
        List<Long> mentionedUserIds
) {
}
