package com.teamflow.ai.domain.dto;

/**
 * 报告草稿编辑请求（{@code PUT /api/ai/reports/{id}}）。
 *
 * <p>只允许编辑草稿；不存在发布接口，草稿需人工复制/确认后使用。</p>
 */
public class AiReportUpdateRequest {

    private String reportTitle;
    private String content;

    public String getReportTitle() { return reportTitle; }
    public void setReportTitle(String reportTitle) { this.reportTitle = reportTitle; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
