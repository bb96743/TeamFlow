package com.teamflow.recruit.domain.dto;

/**
 * 职位新增/修改请求（2.1.1）。JD 正文放 {@code jobDesc}；招聘负责人 {@code ownerId} 为可空引用。
 */
public class RecruitJobWriteRequest {

    private String jobCode;
    private String jobTitle;
    private String jobDesc;
    private Long deptId;
    private Integer headcount;
    private String jobLevel;
    private String salaryRange;
    private String city;
    private Long ownerId;
    private String remark;

    public String getJobCode() { return jobCode; }
    public void setJobCode(String jobCode) { this.jobCode = jobCode; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getJobDesc() { return jobDesc; }
    public void setJobDesc(String jobDesc) { this.jobDesc = jobDesc; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public Integer getHeadcount() { return headcount; }
    public void setHeadcount(Integer headcount) { this.headcount = headcount; }
    public String getJobLevel() { return jobLevel; }
    public void setJobLevel(String jobLevel) { this.jobLevel = jobLevel; }
    public String getSalaryRange() { return salaryRange; }
    public void setSalaryRange(String salaryRange) { this.salaryRange = salaryRange; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
