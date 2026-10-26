package com.teamflow.integration.security;

import com.teamflow.integration.service.OpenApiAuditService;
import com.teamflow.integration.service.OpenApiScopeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开放接口审计拦截器：请求完成时写一条 {@code open_event_log}（含状态码与耗时）。
 *
 * <p>认证失败在过滤器层就被拦截（401），不会进入拦截器；进入拦截器的请求一定已通过 API Key 认证，
 * 因此这里能记录 key/app/user 与最终状态（含 scope 越权的 403）。</p>
 */
@Component
public class OpenApiAuditInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "teamflow.open.startedAt";

    private final OpenApiScopeService scopeService;
    private final OpenApiAuditService auditService;

    public OpenApiAuditInterceptor(OpenApiScopeService scopeService, OpenApiAuditService auditService) {
        this.scopeService = scopeService;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        Object started = request.getAttribute(ATTR_START);
        long cost = started instanceof Long start ? System.currentTimeMillis() - start : 0L;
        String detail = exception == null ? null : exception.getMessage();
        auditService.recordOpenApi(scopeService.identityOf(request), request, response.getStatus(), cost, detail);
    }
}
