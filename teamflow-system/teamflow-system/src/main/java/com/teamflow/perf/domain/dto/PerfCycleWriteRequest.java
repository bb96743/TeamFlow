package com.teamflow.perf.domain.dto;

/**
 * 绩效周期新增/修改请求。
 *
 * <p>日期使用 {@code yyyy-MM-dd} 字符串，由 Service 解析校验，避免依赖日期序列化配置。</p>
 */
public class PerfCycleWriteRequest {

    private String cycleCode;
    private String cycleName;
    private String cycleType;
    private String startDate;
    private String endDate;
    private Long templateId;
    private String remark;

    public String getCycleCode() { return cycleCode; }
    public void setCycleCode(String cycleCode) { this.cycleCode = cycleCode; }
    public String getCycleName() { return cycleName; }
    public void setCycleName(String cycleName) { this.cycleName = cycleName; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
