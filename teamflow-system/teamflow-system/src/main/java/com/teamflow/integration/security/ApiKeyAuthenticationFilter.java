package com.teamflow.integration.security;

import com.teamflow.common.domain.R;
import com.teamflow.integration.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 开放通道认证过滤器（M12 RULE R1：内外接口物理隔离）。
 *
 * <p>只处理 <b>开放通道</b>：{@code /open/v1/**} 与 {@code /mcp}（MCP Server 端点）。
 * 这两类路径一律要求 {@code X-API-Key}，不接受的会话令牌；内部 {@code /api/**}
 * 完全不经过本过滤器（继续由 JWT 过滤器认证），因此：
 * <ul>
 *   <li>API Key 访问 {@code /api/**} → 无 JWT → 401；</li>
 *   <li>内部 JWT 访问 {@code /open/v1/**} → 无 API Key → 401。</li>
 * </ul>
 * 过滤器注册在 Spring Security 过滤器链之前（order = -200），认证成功后 SecurityContext
 * 已就绪，交由既有安全链的 {@code authenticated()} 判定。</p>
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isOpenChannel(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String plainKey = request.getHeader(HEADER);
        try {
            ApiKeyIdentity identity = apiKeyService.authenticate(plainKey)
                    .orElseThrow(() -> new AccessDeniedException("API Key 无效、已撤销或已过期"));
            request.setAttribute(McpCallerContext.ATTR_USERNAME, identity.username());
            request.setAttribute(McpCallerContext.ATTR_KEY_ID, identity.keyId());
            request.setAttribute(McpCallerContext.ATTR_APP_ID, identity.appId());
            request.setAttribute(McpCallerContext.ATTR_USER_ID, identity.userId());
            request.setAttribute(McpCallerContext.ATTR_SCOPE, String.join(",", identity.scope()));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(identity.username(), "api-key:" + identity.keyId(),
                            List.of()));
            filterChain.doFilter(request, response);
        } catch (AccessDeniedException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, exception.getMessage());
        }
    }

    private boolean isOpenChannel(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith("/open/v1/") || path.equals("/open/v1")
                || path.equals("/mcp") || path.startsWith("/mcp/");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        R<Void> body = R.error(401, message == null ? "未提供有效的 API Key" : message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 供诊断：返回本次请求的密钥前缀（不泄露明文）。 */
    public Optional<String> keyPrefixOf(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(McpCallerContext.ATTR_USERNAME))
                .map(value -> String.valueOf(value));
    }
}
