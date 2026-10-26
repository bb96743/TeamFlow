package com.teamflow.insight.metric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 指标实现登记表（**「未登记口径的指标不得上线」的代码侧闸门**）。
 *
 * <p>指标字典的权威数据在 {@code insight_metric} 表（V20），但表里能写什么受本类约束：</p>
 * <ul>
 *   <li>新增/上线指标时必须命中本表登记的编码（即 M13 真的有该口径的计算实现），
 *       否则接口直接拒绝并提示「未在 M13 指标实现登记，不得上线」；</li>
 *   <li>看板出数时同时校验「字典 status=online」+「本表已登记」，双闸门防止手工改库绕过口径评审；</li>
 *   <li>与 M05 对齐的三个指标（{@link #KR_ACHIEVEMENT} / {@link #TASK_COMPLETION} / {@link #TIME_COMPLIANCE}）
 *       使用**与 M05 指标字典完全相同的编码**，口径文字也逐字一致（V20 种子 + 验收测试断言）。</li>
 * </ul>
 *
 * <p><b>边界</b>：本类是只读登记表，不含业务逻辑；计算实现位于 M13 的只读统计 Service 中。</p>
 */
public final class InsightMetricRegistry {

    /** KR 达成率（与 M05 同码同口径）。 */
    public static final String KR_ACHIEVEMENT = "kr_achievement";
    /** 任务完成率（与 M05 同码同口径）。 */
    public static final String TASK_COMPLETION = "task_completion";
    /** 时限达标率（与 M05 同码同口径，D-06.1 逾期另立指标）。 */
    public static final String TIME_COMPLIANCE = "time_compliance";
    /** 逾期率（M13 任务看板口径）。 */
    public static final String TASK_OVERDUE_RATE = "task_overdue_rate";
    /** 任务周期趋势（明细型指标）。 */
    public static final String TASK_PERIOD_TREND = "task_period_trend";
    /** 任务成员分布（明细型指标）。 */
    public static final String TASK_MEMBER_DISTRIBUTION = "task_member_distribution";
    /** 团队承接量与在办。 */
    public static final String TEAM_MEMBER_LOAD = "team_member_load";
    /** 团队响应时长。 */
    public static final String TEAM_RESPONSE_TIME = "team_response_time";
    /** 团队负载均衡度。 */
    public static final String TEAM_LOAD_BALANCE = "team_load_balance";

    private static final Map<String, String> CATEGORIES;

    static {
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put(KR_ACHIEVEMENT, "task");
        categories.put(TASK_COMPLETION, "task");
        categories.put(TIME_COMPLIANCE, "task");
        categories.put(TASK_OVERDUE_RATE, "task");
        categories.put(TASK_PERIOD_TREND, "task");
        categories.put(TASK_MEMBER_DISTRIBUTION, "task");
        categories.put(TEAM_MEMBER_LOAD, "team");
        categories.put(TEAM_RESPONSE_TIME, "team");
        categories.put(TEAM_LOAD_BALANCE, "team");
        CATEGORIES = Collections.unmodifiableMap(categories);
    }

    /** 任务统计看板出数所需指标（7.2）。 */
    public static final List<String> TASK_BOARD_METRICS = List.of(
            KR_ACHIEVEMENT, TASK_COMPLETION, TIME_COMPLIANCE,
            TASK_OVERDUE_RATE, TASK_PERIOD_TREND, TASK_MEMBER_DISTRIBUTION);

    /** 团队效能视图出数所需指标（7.3）。 */
    public static final List<String> TEAM_EFFICIENCY_METRICS = List.of(
            TEAM_MEMBER_LOAD, TEAM_RESPONSE_TIME, TEAM_LOAD_BALANCE);

    private InsightMetricRegistry() {
    }

    /** 是否已在 M13 登记计算实现（未登记不得上线）。 */
    public static boolean isRegistered(String metricCode) {
        return metricCode != null && CATEGORIES.containsKey(metricCode.trim());
    }

    /** 指标类别：task / team；未登记返回 null。 */
    public static String categoryOf(String metricCode) {
        return metricCode == null ? null : CATEGORIES.get(metricCode.trim());
    }

    /** 全部已实现指标编码（有序）。 */
    public static List<String> codes() {
        return List.copyOf(CATEGORIES.keySet());
    }
}
