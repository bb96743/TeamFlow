package com.teamflow.task.domain.dto;

/**
 * 任务状态流转请求（{@code PUT /api/task/tasks/{id}/status}）。
 *
 * <p>合法性一律由 {@code TaskStatusMachine} 唯一判定（M03 RULE R1），流转写
 * {@code flow_instance}/{@code flow_node_instance} 留痕（R2），并在同一事务内发布
 * {@code TaskStatusChangedEvent}（R3）。</p>
 */
public class TaskStatusRequest {

    private String toStatus;
    private String opinion;

    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
}
