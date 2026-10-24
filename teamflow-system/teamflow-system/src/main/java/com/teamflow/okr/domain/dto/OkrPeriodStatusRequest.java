package com.teamflow.okr.domain.dto;

/**
 * 目标周期状态变更请求：draft 未开启 / active 进行中 / archived 已归档。
 */
public class OkrPeriodStatusRequest {

    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
