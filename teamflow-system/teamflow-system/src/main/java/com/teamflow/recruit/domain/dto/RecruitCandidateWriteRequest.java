package com.teamflow.recruit.domain.dto;

/**
 * 候选人入库请求（2.1.2）。
 *
 * <p>查重键由服务端归一化（去空格/统一小写）手机号与邮箱后组成；手机号与邮箱至少填一个。</p>
 */
public class RecruitCandidateWriteRequest {

    private Long jobId;
    private String nameSnapshot;
    private String phone;
    private String email;
    private String sourceChannel;
    private String tags;
    private String remark;

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getNameSnapshot() { return nameSnapshot; }
    public void setNameSnapshot(String nameSnapshot) { this.nameSnapshot = nameSnapshot; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getSourceChannel() { return sourceChannel; }
    public void setSourceChannel(String sourceChannel) { this.sourceChannel = sourceChannel; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
