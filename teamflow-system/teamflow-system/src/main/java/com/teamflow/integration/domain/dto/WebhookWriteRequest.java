package com.teamflow.integration.domain.dto;

/**
 * Webhook 订阅新增/编辑请求（2.4.4）。
 *
 * <p>签名密钥由接入方在创建时提供，服务端对称加密存储；**任何响应都不返回明文**，
 * 编辑时留空表示不修改。</p>
 */
public record WebhookWriteRequest(
        String webhookCode,
        String webhookName,
        String targetUrl,
        String eventTypes,
        String secret,
        Integer timeoutSeconds,
        String status,
        String remark
) {
}
