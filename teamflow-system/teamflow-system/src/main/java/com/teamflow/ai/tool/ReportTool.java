package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.service.AiReportService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：生成日报/周报草稿（report.generate）。
 *
 * <p>产出固定为草稿（status=draft），本期没有发布能力；权限码 {@code ai:report:generate}。</p>
 */
@Component
public class ReportTool implements AiTool {

    private final AiReportService reportService;
    private final AiToolSupport support;

    public ReportTool(AiReportService reportService, AiToolSupport support) {
        this.reportService = reportService;
        this.support = support;
    }

    @Override
    public String name() {
        return "report.generate";
    }

    @Override
    public String label() {
        return "生成日报/周报草稿";
    }

    @Override
    public String permissionCode() {
        return "ai:report:generate";
    }

    @Override
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        String type = support.text(params, "type", "daily");
        String date = support.text(params, "date", "");
        Map<String, Object> report;
        if ("weekly".equalsIgnoreCase(type)) {
            report = reportService.generateWeekly(caller, null, null);
        } else {
            report = reportService.generateDaily(caller, date.isBlank() ? null : date);
        }
        String content = String.valueOf(report.get("content"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", report.get("id"));
        data.put("reportType", report.get("reportType"));
        data.put("reportTitle", report.get("reportTitle"));
        data.put("status", report.get("status"));
        data.put("content", content);
        data.put("sourceSummary", report.get("sourceSummary"));
        String summary = "已生成" + ("weekly".equals(report.get("reportType")) ? "周报" : "日报")
                + "草稿（ID：" + report.get("id") + "，状态：draft）。草稿可编辑，系统不会自动发布：\n\n" + content;
        return ToolOutcome.ok(name(), params, permissionCode(), summary, data);
    }
}
