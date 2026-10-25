package com.teamflow.perf.service;

import java.math.BigDecimal;

/**
 * 单个可归集指标项的归集结果（供 {@link PerfScoreService#persistCollected} 落库）。
 *
 * @param itemId    指标项 ID
 * @param itemCode  指标项编码
 * @param itemName  指标项名称
 * @param weight    权重快照
 * @param autoScore 归集自动值（缺失时按 D-06.5 取 0）
 * @param missing   是否"该员工完全无数据"（用于预览提示，不影响 0 的取值口径）
 */
public record PerfCollectedValue(
        Long itemId,
        String itemCode,
        String itemName,
        BigDecimal weight,
        BigDecimal autoScore,
        boolean missing
) {
}
