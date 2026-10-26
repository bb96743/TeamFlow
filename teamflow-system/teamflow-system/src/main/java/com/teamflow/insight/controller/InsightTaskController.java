package com.teamflow.insight.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.insight.service.InsightTaskStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M13 任务统计看板接口（{@code /api/insight/tasks}，需求 7.2）。
 *
 * <p>只读模型：返回完成率、逾期率、周期趋势、成员分布，并附每个指标的字典口径（公式/来源/时间口径/缺失值策略），
 * 数据全部经 M02/M03 只读端口聚合，不直连业务表。</p>
 */
@RestController
@RequestMapping("/api/insight/tasks")
public class InsightTaskController {

    private final InsightTaskStatsService service;

    public InsightTaskController(InsightTaskStatsService service) {
        this.service = service;
    }

    /**
     * 任务统计总览。
     *
     * @param startDate  统计开始日期（yyyy-MM-dd，含）
     * @param endDate    统计结束日期（yyyy-MM-dd，含）
     * @param assigneeId 可选：只看某位成员（用于逐项核对口径）；为空按当前用户数据范围统计全部可见成员
     */
    @GetMapping("/overview")
    @RequiresPermission("insight:task:view")
    public R<Map<String, Object>> overview(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long assigneeId
    ) {
        return R.success(service.overview(startDate, endDate, assigneeId));
    }
}
