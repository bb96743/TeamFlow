package com.teamflow.recruit.domain.dto;

/**
 * Offer 模板新增/修改请求（2.1.4）。
 */
public class RecruitOfferTemplateWriteRequest {

    private String templateCode;
    private String templateName;
    private String content;
    private String status;
    private String remark;

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
