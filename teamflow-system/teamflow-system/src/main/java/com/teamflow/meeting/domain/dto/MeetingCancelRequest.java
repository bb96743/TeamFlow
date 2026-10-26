package com.teamflow.meeting.domain.dto;

/**
 * 会议取消请求（R5：取消写取消人、时间、原因，不物理删除）。
 */
public class MeetingCancelRequest {

    private String cancelReason;

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
