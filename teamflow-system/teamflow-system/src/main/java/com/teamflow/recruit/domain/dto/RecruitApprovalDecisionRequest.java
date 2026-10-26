package com.teamflow.recruit.domain.dto;

/**
 * 审批处理请求（职位需求审批 / Offer 审批共用）：通过或驳回 + 审批意见。
 */
public class RecruitApprovalDecisionRequest {

    private Boolean approved;
    private String opinion;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
}
