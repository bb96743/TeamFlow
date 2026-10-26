package com.teamflow.recruit.domain.dto;

/**
 * 候选人 Offer 答复登记请求（2.1.4）：接受 / 拒绝；拒绝需填原因。
 */
public class RecruitOfferRespondRequest {

    private Boolean accepted;
    private String reason;

    public Boolean getAccepted() { return accepted; }
    public void setAccepted(Boolean accepted) { this.accepted = accepted; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
