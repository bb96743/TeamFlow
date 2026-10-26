package com.teamflow.meeting.domain.dto;

/**
 * 会议模板新增/修改请求。
 */
public class MeetingTemplateWriteRequest {

    private String templateCode;
    private String templateName;
    /** normal 常规会议 / okr_calibration 目标校准会 / retro 复盘会 */
    private String templateType;
    private String description;
    private String agendaTemplate;
    private Integer defaultDurationMinutes;
    /** active 启用 / disabled 停用 */
    private String status;
    private Integer sort;
    private String remark;

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getTemplateType() { return templateType; }
    public void setTemplateType(String templateType) { this.templateType = templateType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAgendaTemplate() { return agendaTemplate; }
    public void setAgendaTemplate(String agendaTemplate) { this.agendaTemplate = agendaTemplate; }
    public Integer getDefaultDurationMinutes() { return defaultDurationMinutes; }
    public void setDefaultDurationMinutes(Integer defaultDurationMinutes) { this.defaultDurationMinutes = defaultDurationMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
