package com.teamflow.okr.domain.dto;

/**
 * 目标周期新增/修改请求。
 *
 * <p>日期使用 {@code yyyy-MM-dd} 字符串，由 Service 解析校验，避免依赖日期序列化配置。</p>
 */
public class OkrPeriodWriteRequest {

    private String periodName;
    private String periodType;
    private String startDate;
    private String endDate;
    private String status;
    private Integer sort;
    private String remark;

    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }
    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
