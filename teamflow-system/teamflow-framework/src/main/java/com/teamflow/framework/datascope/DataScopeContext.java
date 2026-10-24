package com.teamflow.framework.datascope;

/**
 * 数据范围上下文：承载当前线程的数据权限条件（全量 / 本人 / 部门集合）。
 *
 * <p><b>默认拒绝（fail-closed）</b>：没有任何 {@code @DataScope} 上下文时，{@link #current()}
 * 返回 {@link DataScopeCriteria#selfOnly(Long) selfOnly(null)}（空集，对应 SQL {@code id = NULL} 恒不成立），
 * 而不是全量。历史上这里返回 {@link DataScopeCriteria#all()}，导致 Service 漏加 {@code @DataScope} 时
 * 静默返回全量数据（G-2 缺口）；现已改为默认拒绝。</p>
 *
 * <p>确需全量数据的查询必须显式标注 {@link IgnoreDataScope}（并说明理由），
 * 由 {@link DataScopeAspect} 在方法执行期间切换为全量、退出后恢复原上下文。</p>
 *
 * <p>上下文支持嵌套：{@link DataScopeAspect} 通过 {@link #snapshot()} / {@link #restore(DataScopeCriteria)}
 * 保存并恢复外层上下文，避免内层切面提前清理外层数据范围。</p>
 */
public final class DataScopeContext {

    private static final ThreadLocal<DataScopeCriteria> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /**
     * 设置当前线程的数据范围。入参为 {@code null} 时按默认拒绝处理，等价于 {@code selfOnly(null)}。
     */
    public static void set(DataScopeCriteria criteria) {
        HOLDER.set(criteria == null ? DataScopeCriteria.selfOnly(null) : criteria);
    }

    /**
     * 读取当前数据范围。
     *
     * <p><b>无上下文时返回空集</b>（{@code selfOnly(null)}），即"查不到任何数据"。
     * 这是 G-2 的默认拒绝语义：宁可返回空，也不静默放行全量。</p>
     */
    public static DataScopeCriteria current() {
        DataScopeCriteria criteria = HOLDER.get();
        return criteria == null ? DataScopeCriteria.selfOnly(null) : criteria;
    }

    /**
     * 保存当前原始上下文（可能是 {@code null}，表示无上下文），供 {@link #restore(DataScopeCriteria)} 恢复。
     * 仅框架切面使用，业务代码不应直接调用。
     */
    public static DataScopeCriteria snapshot() {
        return HOLDER.get();
    }

    /**
     * 恢复 {@link #snapshot()} 保存的原始上下文；入参为 {@code null} 时清除上下文。
     * 仅框架切面使用，业务代码不应直接调用。
     */
    public static void restore(DataScopeCriteria previous) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
