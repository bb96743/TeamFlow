package com.teamflow.task.domain.dto;

/**
 * 审批处理请求（{@code PUT /api/task/flow/instances/{id}/approve}）。
 *
 * <p>单节点审批只有「通过 / 驳回」两种结果；通过时才执行目标状态流转（复用
 * {@code TaskStatusService}，因此轨迹与领域事件都不会漏）。</p>
 */
public class TaskApprovalDecisionRequest {

    private Boolean approved;
    private String opinion;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
}
