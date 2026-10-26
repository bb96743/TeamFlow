package com.teamflow.insight.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.insight.service.InsightTeamEfficiencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M13 团队效能视图接口（{@code /api/insight/teams}，需求 7.3）。
 *
 * <p>承接量/在办经 M04 团队详情（其内部经 M03 只读端口取负载），响应时长经 M03 只读列表接口计算；
 * 不直连任何业务表，不落明细。</p>
 */
@RestController
@RequestMapping("/api/insight/teams")
public class InsightTeamController {

    private final InsightTeamEfficiencyService service;

    public InsightTeamController(InsightTeamEfficiencyService service) {
        this.service = service;
    }

    /** 团队选项（M04 既有选项接口的读模型代理，供看板选团队）。 */
    @GetMapping("/options")
    @RequiresPermission("insight:team:view")
    public R<List<Map<String, Object>>> options() {
        return R.success(service.teamOptions());
    }

    /**
     * 团队效能：承接量、响应时长、负载均衡。
     *
     * @param teamId    团队 ID（必填）
     * @param startDate 响应时长统计开始日期（可空，默认近 30 天）
     * @param endDate   响应时长统计结束日期（可空，默认今天）
     */
    @GetMapping("/efficiency")
    @RequiresPermission("insight:team:view")
    public R<Map<String, Object>> efficiency(
            @RequestParam Long teamId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return R.success(service.efficiency(teamId, startDate, endDate));
    }
}
