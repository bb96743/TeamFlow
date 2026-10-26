package com.teamflow.integration.domain.dto;

/**
 * 接入应用新增/编辑请求（2.4.1 第三方集成中心）。
 *
 * <p>{@code credential} 为第三方凭据明文：只在请求中出现一次，落库前由服务端对称加密，
 * 接口返回与日志都不包含明文。编辑时留空表示保持原凭据不变。</p>
 */
public record IntegrationAppWriteRequest(
        String appCode,
        String appName,
        String appType,
        String endpointUrl,
        String configJson,
        String credential,
        String status,
        String remark
) {
}
