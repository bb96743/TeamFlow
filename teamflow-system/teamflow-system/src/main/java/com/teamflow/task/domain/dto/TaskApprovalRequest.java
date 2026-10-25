package com.teamflow.task.domain.dto;

/**
 * 发起单节点审批请求（{@code POST /api/task/tasks/{id}/approvals}）。
 *
 * <p>本期只做线性单节点审批骨架（D-2026-10-25-01；会签/并行/加签属 P2 的 3.1 审批中心）。
 * 审批通过后会以同一事务复用状态流转逻辑，因此同样会留痕并发布 {@code TaskStatusChangedEvent}。</p>
 *
 * <p>{@code approverId} 为空时默认由任务创建人审批；{@code targetStatus} 必须是当前状态的合法目标状态。</p>
 */
public class TaskApprovalRequest {

    private String targetStatus;
    private Long approverId;
    private String opinion;

    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }
    public Long getApproverId() { return approverId; }
    public void setApproverId(Long approverId) { this.approverId = approverId; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
}
