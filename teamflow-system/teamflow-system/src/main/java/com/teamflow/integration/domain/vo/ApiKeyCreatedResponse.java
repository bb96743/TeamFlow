package com.teamflow.integration.domain.vo;

/**
 * 开放密钥创建结果：<b>明文只在这一次响应中出现</b>，之后只保留前缀。
 *
 * @param id        密钥 ID
 * @param keyName   密钥名称
 * @param keyPrefix 前缀（展示与识别用）
 * @param plainKey  明文密钥（仅本次返回；库中只存哈希）
 * @param userId    绑定用户 ID
 * @param scope     权限范围
 * @param expiresAt 过期时间
 * @param warning   安全提示
 */
public record ApiKeyCreatedResponse(
        Long id,
        String keyName,
        String keyPrefix,
        String plainKey,
        Long userId,
        String scope,
        String expiresAt,
        String warning
) {
}
