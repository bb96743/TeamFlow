package com.teamflow.integration.config;

import com.teamflow.integration.mcp.IntegrationMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 工具注册（需求 2.4.2）。
 *
 * <p>只注册 {@link IntegrationMcpTools} 中的白名单只读工具；MCP Server 本体由
 * {@code spring-ai-starter-mcp-server-webmvc} 自动装配（默认 streamable HTTP 传输，
 * 端点 {@code /mcp}）。工具集合固定，不接受运行时注册任意能力。</p>
 */
@Configuration
public class IntegrationMcpConfig {

    @Bean
    @ConditionalOnMissingBean(ToolCallbackProvider.class)
    public ToolCallbackProvider integrationMcpToolCallbackProvider(IntegrationMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
