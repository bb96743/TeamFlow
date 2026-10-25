package com.teamflow.perf.metric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M05 指标字典（**R2 硬门槛的唯一登记处**）。
 *
 * <p>M05 RULE R2 要求「每个可归集指标必须登记：数据来源模块、计算公式、取数时间范围、缺失值处理，
 * 未登记口径的指标不得上线」。本类即指标字典的唯一来源：</p>
 * <ul>
 *   <li>模板保存时，可归集项（{@code item_category=collectable}）的 {@code metricCode} 必须在本字典登记，
 *       否则直接拒绝保存；周期开启时再次校验，确保"未登记不得上线"；</li>
 *   <li>字典四要素通过 {@code GET /api/perf/metrics} 对外可查，便于评审者核对口径；</li>
 *   <li>口径来自 L0 §9.1 {@code D-2026-10-25-06}（D-06.1～D-06.5），**不得在本模块自行改动**。</li>
 * </ul>
 */
public final class PerfMetricDictionary {

    /** KR 达成率：数据来源 M02，员工维度取该员工承接 KR 的平均进度。 */
    public static final String KR_ACHIEVEMENT = "kr_achievement";
    /** 任务完成率：数据来源 M03，顶层任务完成比。 */
    public static final String TASK_COMPLETION = "task_completion";
    /** 时限达标率：数据来源 M03，D-06.1 为逾期另立的可归集指标。 */
    public static final String TIME_COMPLIANCE = "time_compliance";

    private static final Map<String, PerfMetric> METRICS;

    static {
        Map<String, PerfMetric> metrics = new LinkedHashMap<>();
        register(metrics, new PerfMetric(
                KR_ACHIEVEMENT,
                "KR 达成率",
                "M02 目标与 OKR（经 OkrKeyResultService 只读取数，禁止直连 okr_* 表）",
                "min(100, AVG(KR.progress))：取该员工在取数范围内承接的全部 KR，进度算术平均；"
                        + "KR 进度口径遵循 L0 D-2026-10-25-03（量化型 min(1,current/target)；任务驱动型=已完成顶层任务数/关联顶层任务总数）；"
                        + "D-06.1：任务逾期不扣减 KR 进度，逾期由「时限达标率」单独承接",
                "绩效周期 [start_date, end_date] 内：目标所属 okr_period 与绩效周期日期区间重叠，且 KR.owner_id=被评人",
                "D-06.5：无 KR（有目标无任务/完全无目标）默认取 0；仅经 HR/管理者显式标记 not_applicable 的例外状态"
                        + "（新入职当年/长假/借调/试用期未定目标）按剩余权重归一化",
                "kr"
        ));
        register(metrics, new PerfMetric(
                TASK_COMPLETION,
                "任务完成率",
                "M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）",
                "已完成顶层任务数 / 顶层任务总数 × 100；按归集时点 task.status 快照判定（D-06.1：未完成不计入分子）",
                "绩效周期内截止日期（due_date）落在周期内的顶层任务；due_date 为空时按 create_time 落在周期内判定；"
                        + "子任务（有父任务关系）不计入分子分母",
                "D-06.5：无任何顶层任务默认取 0；有任务但未启动/未完成同样取 0；"
                        + "仅显式标记 not_applicable 的例外状态按剩余权重归一化",
                "task"
        ));
        register(metrics, new PerfMetric(
                TIME_COMPLIANCE,
                "时限达标率",
                "M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）",
                "按时完成顶层任务数 / 已完成顶层任务数 × 100；按时 = done_time 的日期 ≤ due_date；"
                        + "due_date 为空视为按时；D-06.1：逾期另立本指标，不影响 KR 进度",
                "与「任务完成率」同一顶层任务集合（取数范围一致），仅统计其中已完成部分",
                "D-06.5：无已完成顶层任务时取 0；仅显式标记 not_applicable 的例外状态按剩余权重归一化",
                "task"
        ));
        METRICS = Collections.unmodifiableMap(metrics);
    }

    private PerfMetricDictionary() {
    }

    private static void register(Map<String, PerfMetric> metrics, PerfMetric metric) {
        metrics.put(metric.code(), metric);
    }

    /** 按编码查字典；未登记返回空。 */
    public static Optional<PerfMetric> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(METRICS.get(code.trim()));
    }

    /** 是否已登记（R2 硬门槛校验用）。 */
    public static boolean isRegistered(String code) {
        return find(code).isPresent();
    }

    /** 全部已登记指标（有序）。 */
    public static List<PerfMetric> all() {
        return List.copyOf(METRICS.values());
    }

    /** 对外展示结构（含四要素）。 */
    public static List<Map<String, Object>> definition() {
        return all().stream().map(metric -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricCode", metric.code());
            row.put("metricName", metric.name());
            row.put("sourceModule", metric.sourceModule());
            row.put("formula", metric.formula());
            row.put("timeRange", metric.timeRange());
            row.put("missingPolicy", metric.missingPolicy());
            row.put("drillDownType", metric.drillDownType());
            return row;
        }).toList();
    }
}
