package com.teamflow.integration.config;

import com.teamflow.integration.security.OpenApiAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * M12 开放接口审计装配。
 *
 * <p>认证与过滤器链见 {@link IntegrationSecurityConfig}（开放通道独立安全链）；本类只注册
 * {@code /open/v1/**} 的调用审计拦截器。内部 {@code /api/**} 不受影响。</p>
 */
@Configuration
public class IntegrationWebConfig implements WebMvcConfigurer {

    private final OpenApiAuditInterceptor auditInterceptor;

    public IntegrationWebConfig(OpenApiAuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor).addPathPatterns("/open/v1/**");
    }
}
