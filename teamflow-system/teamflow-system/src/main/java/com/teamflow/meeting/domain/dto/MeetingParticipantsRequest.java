package com.teamflow.meeting.domain.dto;

import java.util.List;

/**
 * 会议新增参会人请求。
 */
public class MeetingParticipantsRequest {

    private List<Long> userIds;
    /** required 必选 / optional 可选 */
    private String participantRole;

    public List<Long> getUserIds() { return userIds; }
    public void setUserIds(List<Long> userIds) { this.userIds = userIds; }
    public String getParticipantRole() { return participantRole; }
    public void setParticipantRole(String participantRole) { this.participantRole = participantRole; }
}
