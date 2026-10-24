package com.teamflow.okr.domain.dto;

import java.math.BigDecimal;

/**
 * 关键结果（KR）新增/修改请求。
 *
 * <p>krType：quantitative 量化型（targetValue 必填且大于 0）/ task_driven 任务驱动型。
 * direction：positive 正向（越大越好）/ negative 反向（成本、耗时类，越小越好）。</p>
 */
public class OkrKeyResultWriteRequest {

    private String krTitle;
    private String krType;
    private String metricUnit;
    private BigDecimal targetValue;
    private BigDecimal currentValue;
    private String direction;
    private Long ownerId;
    private Integer sort;
    private String remark;

    public String getKrTitle() { return krTitle; }
    public void setKrTitle(String krTitle) { this.krTitle = krTitle; }
    public String getKrType() { return krType; }
    public void setKrType(String krType) { this.krType = krType; }
    public String getMetricUnit() { return metricUnit; }
    public void setMetricUnit(String metricUnit) { this.metricUnit = metricUnit; }
    public BigDecimal getTargetValue() { return targetValue; }
    public void setTargetValue(BigDecimal targetValue) { this.targetValue = targetValue; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
