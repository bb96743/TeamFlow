package com.teamflow.personal.todo;

import java.time.LocalDateTime;

/**
 * 统一投递命令（R1）：业务模块通过 {@code TodoPushService}（HTTP 入口 {@code POST /api/personal/todos/internal/push}）
 * 登记待办，**禁止直写 {@code todo_item}**。
 *
 * @param bizType       业务类型（取值见 {@link TodoBizTypes}）
 * @param bizId         业务单据 ID（回源用；M10 原样保存，不做业务判断）
 * @param userId        待办所属用户
 * @param actionType    待办要求的动作类型（幂等键组成部分；为空时默认 {@code handle}）
 * @param title         展示用标题（可变，不作为业务判断依据）
 * @param actionUrl     回源前端路由（R5：由投递方提供）
 * @param dueTime       截止时间（可空）
 * @param priority      优先级 low/normal/high/urgent（可空，默认 normal）
 * @param sourceEventId 投递方事件/追踪号（仅追溯，不参与去重）
 */
public record TodoPushCommand(
        String bizType,
        Long bizId,
        Long userId,
        String actionType,
        String title,
        String actionUrl,
        LocalDateTime dueTime,
        String priority,
        String sourceEventId
) {
}
