package com.teamflow.ai.controller;

import com.teamflow.ai.domain.dto.AiReportGenerateRequest;
import com.teamflow.ai.domain.dto.AiReportUpdateRequest;
import com.teamflow.ai.domain.vo.AiIdResponse;
import com.teamflow.ai.service.AiPermissionService;
import com.teamflow.ai.service.AiReportService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M11 日报/周报草稿接口（1.3.4）。
 *
 * <p>只生成草稿（status=draft），可查看与编辑；<b>不提供发布接口</b>，草稿需人工确认后使用。</p>
 */
@RestController
@RequestMapping("/api/ai/reports")
public class AiReportController {

    private final AiReportService reportService;
    private final AiPermissionService permissionService;

    public AiReportController(AiReportService reportService, AiPermissionService permissionService) {
        this.reportService = reportService;
        this.permissionService = permissionService;
    }

    @PostMapping("/daily")
    @RequiresPermission("ai:report:generate")
    @OperLog(module = "AI 助手", action = "生成日报草稿")
    public R<AiIdResponse> generateDaily(@RequestBody(required = false) AiReportGenerateRequest request) {
        Map<String, Object> report = reportService.generateDaily(
                permissionService.caller(), request == null ? null : request.getReportDate());
        return R.success(new AiIdResponse(numberAsLong(report.get("id"))));
    }

    @PostMapping("/weekly")
    @RequiresPermission("ai:report:generate")
    @OperLog(module = "AI 助手", action = "生成周报草稿")
    public R<AiIdResponse> generateWeekly(@RequestBody(required = false) AiReportGenerateRequest request) {
        Map<String, Object> report = reportService.generateWeekly(
                permissionService.caller(),
                request == null ? null : request.getPeriodStart(),
                request == null ? null : request.getPeriodEnd());
        return R.success(new AiIdResponse(numberAsLong(report.get("id"))));
    }

    @GetMapping
    @RequiresPermission("ai:report:generate")
    public R<PageResult<Map<String, Object>>> list(PageQuery query,
                                                   @RequestParam(required = false) String reportType) {
        return R.success(reportService.list(permissionService.caller(), query, reportType));
    }

    @GetMapping("/{id}")
    @RequiresPermission("ai:report:generate")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(reportService.detail(permissionService.caller(), id));
    }

    @PutMapping("/{id}")
    @RequiresPermission("ai:report:generate")
    @OperLog(module = "AI 助手", action = "编辑报告草稿")
    public R<Void> update(@PathVariable Long id, @RequestBody AiReportUpdateRequest request) {
        reportService.update(permissionService.caller(), id,
                request == null ? null : request.getReportTitle(),
                request == null ? null : request.getContent());
        return R.success(null);
    }

    private Long numberAsLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
