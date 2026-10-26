package com.teamflow.ai.domain.dto;

/**
 * 对话请求（{@code POST /api/ai/chat}）。
 *
 * <p>{@code sessionId} 为空时新建会话；携带时续用该会话（校验归属当前用户）。</p>
 */
public class AiChatRequest {

    private String message;
    private Long sessionId;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
}
