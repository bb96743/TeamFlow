package com.teamflow.integration.security;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.mapper.IntegrationPlatformMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.function.Supplier;

/**
 * MCP 工具调用身份入口。
 *
 * <p><b>禁止超管越权</b>：工具只以当前请求认证的调用方身份执行。身份来自
 * API Key 过滤器写入的 SecurityContext（或请求属性），<b>不会</b>回退到 super_admin；
 * 拿不到调用方身份时直接拒绝（未认证），不读取任何业务数据。</p>
 *
 * <p>业务 Service 的 {@code @DataScope} 依赖 SecurityContext 中的账号，故本类在调用业务前
 * 保证上下文已设置，调用结束后恢复原状。</p>
 */
@Component
public class McpCallerContext {

    /** 请求属性：API Key 过滤器写入的绑定用户账号。 */
    public static final String ATTR_USERNAME = "teamflow.open.username";
    /** 请求属性：API Key ID。 */
    public static final String ATTR_KEY_ID = "teamflow.open.keyId";
    /** 请求属性：接入应用 ID。 */
    public static final String ATTR_APP_ID = "teamflow.open.appId";
    /** 请求属性：绑定用户 ID。 */
    public static final String ATTR_USER_ID = "teamflow.open.userId";
    /** 请求属性：权限范围。 */
    public static final String ATTR_SCOPE = "teamflow.open.scope";

    private final IntegrationPlatformMapper platformMapper;

    public McpCallerContext(IntegrationPlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    /** 当前调用方（未认证时拒绝）。 */
    public Caller require() {
        String username = currentUsername();
        if (username == null || username.isBlank()) {
            throw new AccessDeniedException("MCP 调用未认证：请使用已启用的 API Key 调用 /mcp");
        }
        Long userId = platformMapper.selectUserIdByUsername(username);
        Long keyId = requestAttribute(ATTR_KEY_ID);
        Long appId = requestAttribute(ATTR_APP_ID);
        return new Caller(username, userId, keyId, appId);
    }

    /** 以调用方身份执行（确保业务 Service 的数据范围能取到账号）。 */
    public <T> T callAs(Caller caller, Supplier<T> supplier) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        boolean replaced = previous == null
                || !caller.username().equals(String.valueOf(previous.getPrincipal()));
        if (replaced) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(caller.username(), "mcp-api-key", List.of()));
        }
        try {
            return supplier.get();
        } finally {
            if (replaced) {
                if (previous == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityContextHolder.getContext().setAuthentication(previous);
                }
            }
        }
    }

    /** 当前登录/认证账号（无则 null）。 */
    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            return String.valueOf(authentication.getPrincipal());
        }
        ServletRequestAttributes attributes = currentRequest();
        if (attributes != null) {
            Object value = attributes.getRequest().getAttribute(ATTR_USERNAME);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private Long requestAttribute(String name) {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getRequest().getAttribute(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private ServletRequestAttributes currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes;
        }
        return null;
    }

    /**
     * 调用方身份。
     *
     * @param username 绑定账号
     * @param userId   绑定用户 ID（可空）
     * @param keyId    API Key ID（可空）
     * @param appId    接入应用 ID（可空）
     */
    public record Caller(String username, Long userId, Long keyId, Long appId) {
    }

    /** 供测试与诊断：未认证时抛出可读异常（与 require 一致，避免未使用告警）。 */
    public void assertAuthenticated() {
        Caller caller = require();
        if (caller.userId() == null) {
            throw new BusinessException("MCP 调用方账号不存在或已停用");
        }
    }
}
