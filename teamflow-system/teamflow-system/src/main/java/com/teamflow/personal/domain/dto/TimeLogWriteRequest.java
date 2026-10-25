package com.teamflow.personal.domain.dto;

/**
 * 工时填报请求（{@code POST/PUT /api/personal/time-logs}）。
 *
 * <p>R6：{@code taskId} 必填（工时以任务为粒度，禁止裸工时）；{@code durationMinutes} 必须大于 0。
 * {@code workDate} 为空时默认取当天。</p>
 */
public class TimeLogWriteRequest {

    private Long taskId;
    private String workDate;
    private String startTime;
    private String endTime;
    private Integer durationMinutes;
    private String description;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getWorkDate() { return workDate; }
    public void setWorkDate(String workDate) { this.workDate = workDate; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
