package com.teamflow.personal.domain.dto;

/**
 * 结束专注会话请求（{@code PUT /api/personal/focus-sessions/{id}/end}）。
 *
 * <p>{@code status} 取 {@code finished}（正常结束）或 {@code abandoned}（放弃），默认 finished；
 * 时长由开始/结束时间计算（不足 1 分钟按 1 分钟计）。</p>
 */
public class FocusSessionEndRequest {

    private Integer interruptCount;
    private String status;

    public Integer getInterruptCount() { return interruptCount; }
    public void setInterruptCount(Integer interruptCount) { this.interruptCount = interruptCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
