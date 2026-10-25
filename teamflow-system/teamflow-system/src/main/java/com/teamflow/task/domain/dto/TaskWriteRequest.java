package com.teamflow.task.domain.dto;

/**
 * 任务新增请求（{@code POST /api/task/tasks}）。
 *
 * <p>状态不由前端指定：有责任人 → {@code todo}，无责任人 → {@code pool}（待认领池，M03 RULE R4）。
 * {@code krId} 为任务↔KR 权威关联（D-2026-10-25-05），可为空；为空时该任务不参与 KR 进度回写。</p>
 */
public class TaskWriteRequest {

    private String taskTitle;
    private String taskDesc;
    private Long krId;
    private Long assigneeId;
    private Long parentTaskId;
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
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public Long getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(Long parentTaskId) { this.parentTaskId = parentTaskId; }
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
