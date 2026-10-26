package com.teamflow.recruit.domain.dto;

/**
 * 通用状态流转请求（职位状态、其他单字段状态流转复用）。
 */
public class RecruitStatusRequest {

    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
