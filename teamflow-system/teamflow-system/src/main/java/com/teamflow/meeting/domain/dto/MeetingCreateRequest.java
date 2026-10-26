package com.teamflow.meeting.domain.dto;

import java.util.List;

/**
 * 会议创建请求：标题 + 时间 + 可选会议室/模板/议程 + 参会人。
 *
 * <p>主持人取当前登录用户；创建时自动预定会议室（冲突则整体回滚）、生成会议日程、
 * 经 M09 通知参会人、经 M10 给参会人投递待办。</p>
 */
public class MeetingCreateRequest {

    private String meetingTitle;
    private Long templateId;
    private Long roomId;
    private String startTime;
    private String endTime;
    private String location;
    private String meetingLink;
    private String agenda;
    private List<Long> participantIds;
    private String remark;

    public String getMeetingTitle() { return meetingTitle; }
    public void setMeetingTitle(String meetingTitle) { this.meetingTitle = meetingTitle; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
    public String getAgenda() { return agenda; }
    public void setAgenda(String agenda) { this.agenda = agenda; }
    public List<Long> getParticipantIds() { return participantIds; }
    public void setParticipantIds(List<Long> participantIds) { this.participantIds = participantIds; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
