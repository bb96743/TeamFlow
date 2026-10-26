package com.teamflow.recruit.domain.dto;

/**
 * 面试修改请求（2.1.3）：改期、改面试官、完成/取消、给出通过结论（结论由人操作并留痕，R3）。
 */
public class RecruitInterviewUpdateRequest {

    private String roundName;
    private Long interviewerId;
    private String scheduledTime;
    private Integer durationMinutes;
    private String location;
    private String status;
    private String result;
    private String remark;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
