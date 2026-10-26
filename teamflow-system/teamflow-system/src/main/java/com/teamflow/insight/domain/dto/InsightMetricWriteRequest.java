package com.teamflow.insight.domain.dto;

/**
 * 指标字典新增/修改请求（7.1）。
 *
 * <p>R1：指标必须先登记口径（定义、公式、数据来源、时间口径、缺失值策略、刷新频率）才允许上线；
 * 上线时还必须命中 M13 代码侧 {@code InsightMetricRegistry} 的计算实现。</p>
 */
public class InsightMetricWriteRequest {

    private String metricCode;
    private String metricName;
    private String metricCategory;
    private String metricDefinition;
    private String formula;
    private String sourceModule;
    private String sourceDetail;
    private String timeGrain;
    private String missingPolicy;
    private String refreshFrequency;
    private Integer cacheTtlSeconds;
    private String ownerName;
    private String status;
    private String remark;

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getMetricCategory() { return metricCategory; }
    public void setMetricCategory(String metricCategory) { this.metricCategory = metricCategory; }
    public String getMetricDefinition() { return metricDefinition; }
    public void setMetricDefinition(String metricDefinition) { this.metricDefinition = metricDefinition; }
    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }
    public String getSourceModule() { return sourceModule; }
    public void setSourceModule(String sourceModule) { this.sourceModule = sourceModule; }
    public String getSourceDetail() { return sourceDetail; }
    public void setSourceDetail(String sourceDetail) { this.sourceDetail = sourceDetail; }
    public String getTimeGrain() { return timeGrain; }
    public void setTimeGrain(String timeGrain) { this.timeGrain = timeGrain; }
    public String getMissingPolicy() { return missingPolicy; }
    public void setMissingPolicy(String missingPolicy) { this.missingPolicy = missingPolicy; }
    public String getRefreshFrequency() { return refreshFrequency; }
    public void setRefreshFrequency(String refreshFrequency) { this.refreshFrequency = refreshFrequency; }
    public Integer getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(Integer cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
