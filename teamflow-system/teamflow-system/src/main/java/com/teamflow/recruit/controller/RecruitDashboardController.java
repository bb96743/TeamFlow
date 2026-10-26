package com.teamflow.recruit.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.recruit.service.RecruitDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M08 招聘看板接口（{@code /api/recruit/dashboard}，需求 2.1.5）。
 *
 * <p>渠道效果、漏斗转化、周期分析；**指标口径须先在 M13 指标字典登记**（{@code POST register-metrics}
 * 经 M13 Service 登记 4 个口径，未登记时看板拒绝出数）。</p>
 */
@RestController
@RequestMapping("/api/recruit/dashboard")
public class RecruitDashboardController {

    private final RecruitDashboardService service;

    public RecruitDashboardController(RecruitDashboardService service) {
        this.service = service;
    }

    /** 指标口径登记（幂等）：4 个招聘看板指标写入 M13 指标字典（category=cross，draft）。 */
    @PostMapping("/register-metrics")
    @RequiresPermission("recruit:dashboard:register")
    @OperLog(module = "招聘看板", action = "登记指标口径")
    public R<Map<String, Object>> registerMetrics() {
        return R.success(service.registerMetrics());
    }

    /** 指标登记状态（不含统计值）。 */
    @GetMapping("/metrics")
    @RequiresPermission("recruit:dashboard:view")
    public R<Map<String, Object>> metrics() {
        return R.success(service.metrics());
    }

    /** 招聘看板总览：漏斗、渠道效果、招聘周期、Offer 接受率。 */
    @GetMapping("/overview")
    @RequiresPermission("recruit:dashboard:view")
    public R<Map<String, Object>> overview(
            @RequestParam String startDate,
            @RequestParam String endDate
    ) {
        return R.success(service.overview(startDate, endDate));
    }
}
