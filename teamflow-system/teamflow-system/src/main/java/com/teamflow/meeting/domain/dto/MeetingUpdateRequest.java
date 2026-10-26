package com.teamflow.meeting.domain.dto;

/**
 * 会议基本信息修改请求（时间与会议室变更走改期接口，以触发冲突检测与通知）。
 */
public class MeetingUpdateRequest {

    private String meetingTitle;
    private Long templateId;
    private String location;
    private String meetingLink;
    private String agenda;
    private String remark;

    public String getMeetingTitle() { return meetingTitle; }
    public void setMeetingTitle(String meetingTitle) { this.meetingTitle = meetingTitle; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
    public String getAgenda() { return agenda; }
    public void setAgenda(String agenda) { this.agenda = agenda; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
