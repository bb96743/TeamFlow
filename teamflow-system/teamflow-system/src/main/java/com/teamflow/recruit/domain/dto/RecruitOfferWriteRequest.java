package com.teamflow.recruit.domain.dto;

import java.math.BigDecimal;

/**
 * Offer 新增/修改请求（2.1.4）。同一候选人只允许一份 Offer。
 */
public class RecruitOfferWriteRequest {

    private Long candidateId;
    private Long templateId;
    private BigDecimal salary;
    private String expectedOnboardDate;
    private String remark;

    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public BigDecimal getSalary() { return salary; }
    public void setSalary(BigDecimal salary) { this.salary = salary; }
    public String getExpectedOnboardDate() { return expectedOnboardDate; }
    public void setExpectedOnboardDate(String expectedOnboardDate) { this.expectedOnboardDate = expectedOnboardDate; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
