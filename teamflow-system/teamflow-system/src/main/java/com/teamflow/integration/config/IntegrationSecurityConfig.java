package com.teamflow.integration.config;

import com.teamflow.integration.security.ApiKeyAuthenticationFilter;
import com.teamflow.integration.service.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 开放通道独立过滤器链（M12 RULE R1：内外接口物理隔离）。
 *
 * <p>为 {@code /open/v1/**} 与 {@code /mcp} 单独定义 <b>更高优先级</b>的安全链：
 * 认证完全由 {@link ApiKeyAuthenticationFilter} 完成（{@code X-API-Key}），不使用会话令牌；
 * 内部 {@code /api/**} 继续走框架既有的 JWT 链，两条链鉴权模型互不复用。
 * 这样也避免「在安全链之前设置 SecurityContext 被安全链覆盖」的顺序问题。</p>
 */
@Configuration
public class IntegrationSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain openChannelSecurityFilterChain(
            HttpSecurity http,
            ApiKeyService apiKeyService,
            ObjectMapper objectMapper
    ) throws Exception {
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(apiKeyService, objectMapper);
        http
                .securityMatcher("/open/v1/**", "/mcp", "/mcp/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
