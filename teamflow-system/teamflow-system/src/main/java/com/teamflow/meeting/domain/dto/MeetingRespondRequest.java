package com.teamflow.meeting.domain.dto;

/**
 * 参会答复请求：accepted 接受 / declined 拒绝。
 */
public class MeetingRespondRequest {

    private String responseStatus;

    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }
}
