package com.teamflow.perf.domain.dto;

/**
 * 绩效申诉提交请求（锁定后修改结果的唯一入口）。
 */
public class PerfAppealWriteRequest {

    private Long resultId;
    private String reason;

    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
