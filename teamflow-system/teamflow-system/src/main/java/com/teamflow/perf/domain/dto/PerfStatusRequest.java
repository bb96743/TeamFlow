package com.teamflow.perf.domain.dto;

/**
 * 绩效周期状态流转请求。
 *
 * <p>仅接受 {@code active} / {@code scoring} 两个"无副作用"的目标状态；{@code archived} 由
 * 归档接口（同时固化结果）触发、{@code locked} 由锁定接口触发，见 {@code PerfResultService}。</p>
 */
public class PerfStatusRequest {

    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
