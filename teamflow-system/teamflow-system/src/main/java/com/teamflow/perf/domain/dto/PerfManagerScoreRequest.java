package com.teamflow.perf.domain.dto;

import java.util.List;

/**
 * 上级评分提交请求：被评人由请求显式指定（M04 未开工，暂无汇报关系，由具备
 * {@code perf:score:submit} 权限的管理者评分）。
 */
public class PerfManagerScoreRequest {

    private Long cycleId;
    private Long employeeId;
    private List<PerfItemScoreRequest> items;

    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public List<PerfItemScoreRequest> getItems() { return items; }
    public void setItems(List<PerfItemScoreRequest> items) { this.items = items; }
}
