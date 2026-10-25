package com.teamflow.task.domain.dto;

/**
 * 任务指派 / 转派请求（{@code PUT /api/task/tasks/{id}/assign}）。
 *
 * <p>不传 {@code assigneeId}（或传 0）表示收回指派，任务退回待认领池（M03 RULE R4）。</p>
 */
public class TaskAssignRequest {

    private Long assigneeId;
    private String remark;

    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
