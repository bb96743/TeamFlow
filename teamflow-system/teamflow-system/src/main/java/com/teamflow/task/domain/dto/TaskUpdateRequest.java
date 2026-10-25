package com.teamflow.task.domain.dto;

/**
 * 任务编辑请求（{@code PUT /api/task/tasks/{id}}）。
 *
 * <p>只允许改标题/描述/优先级/起止日期/关联 KR/备注；<b>不允许</b>改状态（走状态流转接口）
 * 与责任人（走指派/转派接口），避免绕过状态机与轨迹留痕。</p>
 *
 * <p>{@code krId} 为任务↔KR 权威关联：发生变更时本模块会发布 {@code TaskKrChangedEvent}，
 * 由 M02 清理旧 KR 投影并重算两个 KR 的进度（否则旧 KR 进度会永久虚高）。</p>
 */
public class TaskUpdateRequest {

    private String taskTitle;
    private String taskDesc;
    private Long krId;
    private String priority;
    private String startDate;
    private String dueDate;
    private Integer sort;
    private String remark;

    public String getTaskTitle() { return taskTitle; }
    public void setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; }
    public String getTaskDesc() { return taskDesc; }
    public void setTaskDesc(String taskDesc) { this.taskDesc = taskDesc; }
    public Long getKrId() { return krId; }
    public void setKrId(Long krId) { this.krId = krId; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
