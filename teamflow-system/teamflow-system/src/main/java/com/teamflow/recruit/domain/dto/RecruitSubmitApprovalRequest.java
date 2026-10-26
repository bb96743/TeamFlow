package com.teamflow.recruit.domain.dto;

/**
 * 提交需求审批请求（2.1.1）：指定审批人，审批实例复用 M03 {@code flow_instance}（类型 {@code job_approval}）。
 */
public class RecruitSubmitApprovalRequest {

    private Long approverId;

    public Long getApproverId() { return approverId; }
    public void setApproverId(Long approverId) { this.approverId = approverId; }
}
