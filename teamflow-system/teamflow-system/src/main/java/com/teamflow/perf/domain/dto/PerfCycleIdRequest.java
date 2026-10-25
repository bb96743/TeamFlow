package com.teamflow.perf.domain.dto;

/**
 * 归档 / 锁定请求（周期维度）。
 */
public class PerfCycleIdRequest {

    private Long cycleId;

    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
}
