package com.teamflow.meeting.domain.dto;

/**
 * 会议室预定请求（同一会议室时间重叠由服务端拒绝，M06 RULE R1/R6）。
 *
 * <p>时间按 ISO-8601 传输（如 {@code 2026-10-27T14:00:00}），也兼容 {@code yyyy-MM-dd HH:mm:ss}。</p>
 */
public class MeetingBookingWriteRequest {

    private Long roomId;
    private Long meetingId;
    private String bookingTitle;
    private Long bookerId;
    private String startTime;
    private String endTime;
    private String remark;

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getMeetingId() { return meetingId; }
    public void setMeetingId(Long meetingId) { this.meetingId = meetingId; }
    public String getBookingTitle() { return bookingTitle; }
    public void setBookingTitle(String bookingTitle) { this.bookingTitle = bookingTitle; }
    public Long getBookerId() { return bookerId; }
    public void setBookerId(Long bookerId) { this.bookerId = bookerId; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
