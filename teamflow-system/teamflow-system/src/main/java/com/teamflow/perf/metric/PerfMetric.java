package com.teamflow.perf.metric;

/**
 * 可归集指标的定义（M05 RULE R2：指标字典四要素）。
 *
 * @param code           指标编码（模板项 {@code metric_code} 引用此值）
 * @param name           指标名称
 * @param sourceModule   ① 数据来源模块（跨模块一律经对方 Service，不直连表）
 * @param formula        ② 计算公式
 * @param timeRange      ③ 取数时间范围
 * @param missingPolicy  ④ 缺失值处理（与 L0 §9.1 D-06.5 对齐）
 * @param drillDownType  下钻类型：kr 下钻到 KR 清单，task 下钻到任务清单
 */
public record PerfMetric(
        String code,
        String name,
        String sourceModule,
        String formula,
        String timeRange,
        String missingPolicy,
        String drillDownType
) {
}
