package com.teamflow.framework.datascope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明"本方法确需全量数据"，临时跳过数据权限默认拒绝。
 *
 * <p>G-2 之后数据权限默认拒绝：未标注 {@link DataScope} 的方法调用
 * {@link DataScopeContext#current()} 只能拿到空集。确实需要全量数据的查询
 * （例如平台级元数据、统计口径需要跨部门汇总）必须显式标注本注解，
 * 由 {@link DataScopeAspect} 在方法执行期间把上下文切换为
 * {@link DataScopeCriteria#all()}，方法返回后恢复原上下文（含嵌套场景）。</p>
 *
 * <p><b>使用约束（必须遵守）：</b></p>
 * <ol>
 *   <li>仅用于确实需要全量数据的查询，不得用于"图省事"绕过数据权限；</li>
 *   <li>使用本注解的方法必须在模块 {@code RULE.md} 或代码注释中说明"为什么确需全量"，
 *       否则视为越权口子，评审时直接驳回；</li>
 *   <li>禁止与 {@link DataScope} 标注在同一方法/类上（两者语义冲突，切面执行顺序不确定），
 *       {@code DataScopeGuardTest} 会在测试期拦截。</li>
 * </ol>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreDataScope {
}
