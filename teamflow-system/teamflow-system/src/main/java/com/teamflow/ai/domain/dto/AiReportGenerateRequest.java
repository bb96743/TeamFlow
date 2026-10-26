package com.teamflow.ai.domain.dto;

/**
 * 日报/周报草稿生成请求。
 *
 * <p>日报用 {@code reportDate}（默认今天）；周报用 {@code periodStart}/{@code periodEnd}
 * （默认本周一至今天）。生成结果固定为草稿，本期无发布接口。</p>
 */
public class AiReportGenerateRequest {

    private String reportDate;
    private String periodStart;
    private String periodEnd;

    public String getReportDate() { return reportDate; }
    public void setReportDate(String reportDate) { this.reportDate = reportDate; }

    public String getPeriodStart() { return periodStart; }
    public void setPeriodStart(String periodStart) { this.periodStart = periodStart; }

    public String getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(String periodEnd) { this.periodEnd = periodEnd; }
}
