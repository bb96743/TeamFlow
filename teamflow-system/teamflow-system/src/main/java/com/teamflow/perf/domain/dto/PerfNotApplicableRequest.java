package com.teamflow.perf.domain.dto;

/**
 * D-06.5 例外状态标记请求（not_applicable）。
 *
 * <p>只有「经 HR/管理者显式标记」的例外状态（新入职当年/长假/借调/试用期未定目标）才允许标不适用，
 * 且必须填写原因；未标记却无数据一律按 0 计。</p>
 */
public class PerfNotApplicableRequest {

    private Long cycleId;
    private Long employeeId;
    private Long itemId;
    private Boolean notApplicable;
    private String reason;

    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Boolean getNotApplicable() { return notApplicable; }
    public void setNotApplicable(Boolean notApplicable) { this.notApplicable = notApplicable; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
