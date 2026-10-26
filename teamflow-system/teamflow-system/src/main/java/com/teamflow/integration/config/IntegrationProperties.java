package com.teamflow.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M12 集成与开放模块配置。
 *
 * <p>凭据加密密钥只从环境变量注入（不写入配置文件、不提交仓库）：
 * <pre>
 *   TEAMFLOW_INTEGRATION_CREDENTIAL_KEY=base64:&lt;32 字节密钥的 Base64&gt;
 * </pre>
 * 未配置时，任何需要加密/解密凭据的操作都会明确失败（不会降级为明文存储）。</p>
 */
@Component
@ConfigurationProperties(prefix = "teamflow.integration")
public class IntegrationProperties {

    /** 凭据加密密钥（Base64 或原始 16/24/32 字节字符串；为空表示未配置）。 */
    private String credentialKey = "";

    /** 连通性检测超时时间（秒）。 */
    private int checkTimeoutSeconds = 3;

    /** MCP 工具单次返回的最大行数（防止一次拉取过多数据）。 */
    private int mcpMaxRows = 50;

    public String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(String credentialKey) {
        this.credentialKey = credentialKey;
    }

    public int getCheckTimeoutSeconds() {
        return checkTimeoutSeconds;
    }

    public void setCheckTimeoutSeconds(int checkTimeoutSeconds) {
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    public int getMcpMaxRows() {
        return mcpMaxRows;
    }

    public void setMcpMaxRows(int mcpMaxRows) {
        this.mcpMaxRows = mcpMaxRows;
    }
}
