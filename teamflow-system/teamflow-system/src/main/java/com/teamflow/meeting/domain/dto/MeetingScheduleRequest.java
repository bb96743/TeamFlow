package com.teamflow.meeting.domain.dto;

/**
 * 会议改期请求：新时间 + 可选会议室（改会议室会释放旧预定并重新做冲突检测）。
 *
 * <p>{@code onlineOnly=true} 表示改为线上会议并释放会议室。</p>
 */
public class MeetingScheduleRequest {

    private String startTime;
    private String endTime;
    private Long roomId;
    private Boolean onlineOnly;
    private String remark;

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Boolean getOnlineOnly() { return onlineOnly; }
    public void setOnlineOnly(Boolean onlineOnly) { this.onlineOnly = onlineOnly; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
