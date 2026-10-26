package com.teamflow.integration.service;

import com.teamflow.framework.security.PermissionResolver;
import com.teamflow.integration.security.ApiKeyIdentity;
import com.teamflow.integration.security.McpCallerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 开放接口/工具调用的鉴权入口：在 API Key 认证基础上，再校验「权限范围 + 绑定用户权限」。
 *
 * <p>鉴权模型（M12 RULE §二/§六）：Key 绑定用户与权限范围；
 * <ul>
 *   <li>Key 的 scope 非空且不含目标权限 → 403（scope 越权）；</li>
 *   <li>绑定用户在系统中无该权限（含已停用）→ 403（用户权限越权）；</li>
 * </ul>
 * 数据范围仍由各业务 Service 的 {@code @DataScope} 按绑定用户计算，开放通道不放大可见范围。</p>
 */
@Service
public class OpenApiScopeService {

    private final PermissionResolver permissionResolver;

    public OpenApiScopeService(PermissionResolver permissionResolver) {
        this.permissionResolver = permissionResolver;
    }

    /** 当前请求的开放身份（未认证返回 null）。 */
    public ApiKeyIdentity identityOf(HttpServletRequest request) {
        Object keyId = request.getAttribute(McpCallerContext.ATTR_KEY_ID);
        Object username = request.getAttribute(McpCallerContext.ATTR_USERNAME);
        if (keyId == null || username == null) {
            return null;
        }
        Object userId = request.getAttribute(McpCallerContext.ATTR_USER_ID);
        Object appId = request.getAttribute(McpCallerContext.ATTR_APP_ID);
        Object scope = request.getAttribute(McpCallerContext.ATTR_SCOPE);
        return new ApiKeyIdentity(
                toLong(keyId),
                toLong(appId),
                toLong(userId),
                String.valueOf(username),
                null,
                ApiKeyIdentity.parseScope(scope == null ? null : String.valueOf(scope)));
    }

    /**
     * 要求当前开放请求具备指定权限（scope 与用户权限双重校验）。
     *
     * @throws AccessDeniedException 未认证、scope 不含或绑定用户无权限
     */
    public ApiKeyIdentity require(HttpServletRequest request, String permission) {
        ApiKeyIdentity identity = identityOf(request);
        if (identity == null) {
            throw new AccessDeniedException("开放接口未认证：请在请求头携带 X-API-Key");
        }
        Set<String> scope = identity.scope();
        if (!scope.isEmpty() && !scope.contains(permission)) {
            throw new AccessDeniedException("API Key 权限范围不包含：" + permission);
        }
        if (!permissionResolver.hasPermission(identity.username(), permission)) {
            throw new AccessDeniedException("绑定用户缺少权限：" + permission + "（已停用或无授权）");
        }
        return identity;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
