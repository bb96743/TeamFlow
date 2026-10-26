package com.teamflow.ai.domain;

import java.util.List;

/**
 * AI 工具调用者身份（由当前登录用户解析，绝不使用系统超管身份）。
 *
 * <p>工具内部一律按该身份查询：权限码由 {@code PermissionResolver} 校验，
 * 数据范围由各业务 Service 的 {@code @DataScope} 按当前登录上下文计算。</p>
 */
public record AiCaller(
        Long userId,
        String username,
        String nickname,
        Long deptId,
        String deptName,
        List<String> roles,
        List<String> permissions
) {

    public String displayName() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }
}
