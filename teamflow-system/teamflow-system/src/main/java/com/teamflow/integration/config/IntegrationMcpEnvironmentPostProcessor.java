package com.teamflow.integration.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * M12 默认开启 MCP Server 的 streamable HTTP 传输。
 *
 * <p>Spring AI 2.0 的 MCP Server 自动配置要求显式声明
 * {@code spring.ai.mcp.server.protocol=streamable}（缺省条件下不会注册 {@code /mcp} 端点）。
 * 为做到「集成模块随包即用、无需改主配置」，本后处理器<strong>只在属性缺失时</strong>补默认值；
 * 显式配置优先，不会覆盖使用方设置。</p>
 */
public class IntegrationMcpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROTOCOL_KEY = "spring.ai.mcp.server.protocol";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty(PROTOCOL_KEY) != null) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(
                "teamflowIntegrationMcpDefaults",
                Map.of(PROTOCOL_KEY, "streamable")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
