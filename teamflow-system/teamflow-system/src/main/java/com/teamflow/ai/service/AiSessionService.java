package com.teamflow.ai.service;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.mapper.AiMessageMapper;
import com.teamflow.ai.mapper.AiSessionMapper;
import com.teamflow.ai.support.AiJson;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11 会话与消息服务：只存会话上下文，不复制无权访问的业务数据。
 */
@Service
public class AiSessionService {

    private static final int TITLE_MAX_LENGTH = 60;
    private static final int MESSAGES_MAX = 200;

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final AiJson aiJson;

    public AiSessionService(AiSessionMapper sessionMapper, AiMessageMapper messageMapper, AiJson aiJson) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.aiJson = aiJson;
    }

    @Transactional
    public Long create(AiCaller caller, String title, String sessionType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionTitle", truncate(title == null || title.isBlank() ? "新的对话" : title.trim(), TITLE_MAX_LENGTH));
        row.put("sessionType", sessionType == null || sessionType.isBlank() ? "chat" : sessionType);
        row.put("ownerId", caller.userId());
        row.put("ownerName", caller.username());
        sessionMapper.insertSession(row, caller.username());
        return numberAsLong(row.get("id"));
    }

    public Map<String, Object> requireOwned(Long sessionId, AiCaller caller) {
        if (sessionId == null) {
            throw new BusinessException("会话 ID 不能为空");
        }
        Map<String, Object> session = sessionMapper.selectSessionById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        Long ownerId = numberAsLong(session.get("ownerId"));
        if (ownerId == null || !ownerId.equals(caller.userId())) {
            throw new AccessDeniedException("无权访问该会话");
        }
        return session;
    }

    @Transactional
    public Long append(AiCaller caller, Long sessionId, String role, String content, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", sessionId);
        row.put("role", role);
        row.put("content", content == null ? "" : content);
        row.put("intentCode", extra == null ? null : extra.get("intentCode"));
        row.put("intentLabel", extra == null ? null : extra.get("intentLabel"));
        row.put("toolName", extra == null ? null : extra.get("toolName"));
        row.put("toolParams", extra == null ? null : extra.get("toolParams"));
        row.put("toolResult", extra == null ? null : extra.get("toolResult"));
        row.put("citations", extra == null ? null : extra.get("citations"));
        row.put("modelName", extra == null ? null : extra.get("modelName"));
        row.put("status", extra == null || extra.get("status") == null ? "ok" : extra.get("status"));
        row.put("elapsedMs", extra == null ? null : extra.get("elapsedMs"));
        row.put("tokenEstimate", extra == null ? null : extra.get("tokenEstimate"));
        messageMapper.insertMessage(row, caller.username());
        sessionMapper.touchSession(sessionId, 1, null, caller.username());
        return numberAsLong(row.get("id"));
    }

    public void touch(AiCaller caller, Long sessionId, int delta, String firstTitle) {
        sessionMapper.touchSession(sessionId, delta, firstTitle, caller.username());
    }

    public PageResult<Map<String, Object>> list(AiCaller caller, PageQuery query) {
        int pageNum = query == null ? 1 : query.normalizedPageNum();
        int pageSize = query == null ? 10 : query.normalizedPageSize();
        long total = sessionMapper.countSessions(caller.userId());
        List<Map<String, Object>> rows = sessionMapper.selectSessions(caller.userId(), (pageNum - 1) * pageSize, pageSize);
        return PageResult.of(rows, total, pageNum, pageSize);
    }

    public List<Map<String, Object>> messages(AiCaller caller, Long sessionId) {
        requireOwned(sessionId, caller);
        return messageMapper.selectMessages(sessionId, MESSAGES_MAX);
    }

    public String json(Object value) {
        return aiJson.toJson(value);
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
