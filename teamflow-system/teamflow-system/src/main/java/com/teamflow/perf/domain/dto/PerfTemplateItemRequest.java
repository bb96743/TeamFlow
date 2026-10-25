package com.teamflow.perf.domain.dto;

/**
 * 指标项请求。
 *
 * <p>R2：{@code itemCategory=collectable} 时必须提供已登记指标字典的 {@code metricCode}；
 * {@code itemCategory=subjective} 时评语恒为必填。</p>
 */
public class PerfTemplateItemRequest {

    private String itemCode;
    private String itemName;
    private String itemCategory;
    private String metricCode;
    private java.math.BigDecimal weight;
    private java.math.BigDecimal maxScore;
    private String scoreStandard;
    private Boolean requireComment;
    private Integer sort;
    private String remark;

    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getItemCategory() { return itemCategory; }
    public void setItemCategory(String itemCategory) { this.itemCategory = itemCategory; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public java.math.BigDecimal getWeight() { return weight; }
    public void setWeight(java.math.BigDecimal weight) { this.weight = weight; }
    public java.math.BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(java.math.BigDecimal maxScore) { this.maxScore = maxScore; }
    public String getScoreStandard() { return scoreStandard; }
    public void setScoreStandard(String scoreStandard) { this.scoreStandard = scoreStandard; }
    public Boolean getRequireComment() { return requireComment; }
    public void setRequireComment(Boolean requireComment) { this.requireComment = requireComment; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
