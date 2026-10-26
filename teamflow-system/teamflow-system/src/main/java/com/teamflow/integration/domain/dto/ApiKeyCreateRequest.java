package com.teamflow.integration.domain.dto;

/**
 * 开放 API 密钥创建请求（2.4.3）。
 *
 * <p>密钥绑定用户与权限范围：调用时以绑定用户身份执行，且只能使用 {@code scope} 内的权限；
 * {@code scope} 为空表示仅受绑定用户自身权限约束。{@code userId} 为空时默认绑定当前登录用户。</p>
 */
public record ApiKeyCreateRequest(
        String keyName,
        Long appId,
        Long userId,
        String scope,
        String expiresAt,
        String remark
) {
}
