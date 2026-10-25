package com.teamflow.perf.domain.dto;

import java.util.List;

/**
 * 归集预览 / 重算请求。
 *
 * <p>{@code employeeIds} 为空时按「全部启用用户」归集（管理端预览用）；指定时只算指定员工。</p>
 */
public class PerfCollectRequest {

    private Long cycleId;
    private List<Long> employeeIds;

    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
    public List<Long> getEmployeeIds() { return employeeIds; }
    public void setEmployeeIds(List<Long> employeeIds) { this.employeeIds = employeeIds; }
}
