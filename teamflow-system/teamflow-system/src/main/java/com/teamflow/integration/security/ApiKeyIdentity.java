package com.teamflow.integration.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * API Key 认证身份（认证成功后写入请求属性，供开放接口与审计使用）。
 *
 * @param keyId    密钥 ID
 * @param appId    所属接入应用 ID（可空）
 * @param userId   绑定用户 ID
 * @param username 绑定用户账号
 * @param keyPrefix 密钥前缀（展示/审计用，非敏感）
 * @param scope    权限范围（逗号拆分后的权限码集合；空集合表示仅受用户权限约束）
 */
public record ApiKeyIdentity(
        Long keyId,
        Long appId,
        Long userId,
        String username,
        String keyPrefix,
        Set<String> scope
) {

    /** 未认证时的空身份。 */
    public static ApiKeyIdentity anonymous() {
        return new ApiKeyIdentity(null, null, null, null, null, Set.of());
    }

    public static Set<String> parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(scope.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList());
    }
}
