package com.teamflow.personal.domain.dto;

/**
 * 统一投递请求（{@code POST /api/personal/todos/internal/push}，R1）。
 *
 * <p>字段与 L0 §9.1 {@code D-2026-10-26-07} / M10 RULE R1 一致：
 * {@code bizType, bizId, userId, title, actionUrl, dueTime, priority}；
 * 额外提供可选的 {@code actionType}（幂等键组成部分，默认 {@code handle}）与 {@code sourceEventId}（追溯用）。</p>
 */
public class TodoPushRequest {

    private String bizType;
    private Long bizId;
    private Long userId;
    private String actionType;
    private String title;
    private String actionUrl;
    private String dueTime;
    private String priority;
    private String sourceEventId;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getActionUrl() { return actionUrl; }
    public void setActionUrl(String actionUrl) { this.actionUrl = actionUrl; }
    public String getDueTime() { return dueTime; }
    public void setDueTime(String dueTime) { this.dueTime = dueTime; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }
}
