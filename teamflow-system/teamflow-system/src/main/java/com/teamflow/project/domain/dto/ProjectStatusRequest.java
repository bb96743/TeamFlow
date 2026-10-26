package com.teamflow.project.domain.dto;

/**
 * 项目 / 里程碑状态流转请求。
 */
public class ProjectStatusRequest {

    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
