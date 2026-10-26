package com.teamflow.collab.domain.dto;

import java.util.List;

/**
 * 创建会话请求（单聊 / 群组）。
 *
 * @param conversationType direct 单聊，group 群组
 * @param title            群组名称（单聊忽略）
 * @param memberIds        参与人用户 ID 列表（不含创建人；单聊必须恰好 1 人）
 */
public record ConversationCreateRequest(String conversationType, String title, List<Long> memberIds) {
}
