package com.teamflow.recruit.domain.dto;

/**
 * 面试安排请求（2.1.3）：轮次由服务端按候选人递增，面试官/时间由排期人指定。
 *
 * <p>与 M06 日历互通属 M06 排期（M3），本期只保存时间与地点，不写日程表。</p>
 */
public class RecruitInterviewWriteRequest {

    private Long candidateId;
    private String roundName;
    private Long interviewerId;
    private String scheduledTime;
    private Integer durationMinutes;
    private String location;
    private String remark;

    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public String getRoundName() { return roundName; }
    public void setRoundName(String roundName) { this.roundName = roundName; }
    public Long getInterviewerId() { return interviewerId; }
    public void setInterviewerId(Long interviewerId) { this.interviewerId = interviewerId; }
    public String getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(String scheduledTime) { this.scheduledTime = scheduledTime; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
