package com.teamflow.framework.datascope;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 数据范围切面：在 {@link DataScope} / {@link IgnoreDataScope} 标注的方法执行期间建立线程上下文。
 *
 * <p>两个 advice 都采用「保存 — 切换 — 恢复」语义，而不是直接 {@code clear()}，
 * 因此支持方法嵌套调用：内层切面退出后外层上下文仍然有效（G-2 修复前
 * {@code finally clear()} 会提前清掉外层数据范围）。</p>
 */
@Aspect
@Component
public class DataScopeAspect {

    private final DataScopeResolver dataScopeResolver;

    public DataScopeAspect(DataScopeResolver dataScopeResolver) {
        this.dataScopeResolver = dataScopeResolver;
    }

    /** 按当前登录用户解析数据范围（默认拒绝：未认证用户解析为空集）。 */
    @Around("@annotation(com.teamflow.framework.datascope.DataScope) || @within(com.teamflow.framework.datascope.DataScope)")
    public Object applyDataScope(ProceedingJoinPoint joinPoint) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : String.valueOf(authentication.getPrincipal());
        DataScopeCriteria previous = DataScopeContext.snapshot();
        DataScopeContext.set(dataScopeResolver.resolve(username));
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.restore(previous);
        }
    }

    /**
     * 显式全量：仅用于标注了 {@link IgnoreDataScope} 的方法，方法执行期间上下文为全量，退出后恢复。
     *
     * <p>{@link IgnoreDataScope} 的 Javadoc 已限定使用条件；与 {@link DataScope} 同标属于违规，
     * 由 {@code DataScopeGuardTest} 在测试期拦截（两者同标时执行顺序不确定，语义无法保证）。</p>
     */
    @Around("@annotation(com.teamflow.framework.datascope.IgnoreDataScope) || @within(com.teamflow.framework.datascope.IgnoreDataScope)")
    public Object applyIgnoreDataScope(ProceedingJoinPoint joinPoint) throws Throwable {
        DataScopeCriteria previous = DataScopeContext.snapshot();
        DataScopeContext.set(DataScopeCriteria.all());
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.restore(previous);
        }
    }
}
