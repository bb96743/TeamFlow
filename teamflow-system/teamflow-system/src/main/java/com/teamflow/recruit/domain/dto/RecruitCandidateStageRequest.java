package com.teamflow.recruit.domain.dto;

/**
 * 候选人阶段推进请求（R5 状态机；淘汰必须填写原因）。
 */
public class RecruitCandidateStageRequest {

    private String stage;
    private String rejectReason;

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
}
