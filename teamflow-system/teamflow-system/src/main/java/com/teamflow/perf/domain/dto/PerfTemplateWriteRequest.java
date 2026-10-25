package com.teamflow.perf.domain.dto;

import java.util.List;

/**
 * 指标模板新增/修改请求（模板 + 指标项整体提交，便于一次性校验 R6 权重和）。
 */
public class PerfTemplateWriteRequest {

    private String templateCode;
    private String templateName;
    private String description;
    private String status;
    private String remark;
    private List<PerfTemplateItemRequest> items;

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<PerfTemplateItemRequest> getItems() { return items; }
    public void setItems(List<PerfTemplateItemRequest> items) { this.items = items; }
}
