package com.teamflow.ai.service;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.mapper.AiToolCallMapper;
import com.teamflow.ai.support.AiJson;
import com.teamflow.ai.tool.ToolOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11 工具调用轨迹（审计）：记录谁、以什么权限、调用了什么、是否被拒绝。
 */
@Service
public class AiToolCallService {

    private final AiToolCallMapper toolCallMapper;
    private final AiJson aiJson;

    public AiToolCallService(AiToolCallMapper toolCallMapper, AiJson aiJson) {
        this.toolCallMapper = toolCallMapper;
        this.aiJson = aiJson;
    }

    @Transactional
    public Long record(Long sessionId, Long messageId, AiCaller caller, ToolOutcome outcome,
                       String toolLabel, long elapsedMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", sessionId);
        row.put("messageId", messageId);
        row.put("toolName", outcome.toolName());
        row.put("toolLabel", toolLabel);
        row.put("arguments", aiJson.toJson(outcome.params()));
        row.put("permissionCode", outcome.permissionCode());
        row.put("allowed", outcome.allowed() ? 1 : 0);
        row.put("denyReason", truncate(outcome.denyReason(), 500));
        row.put("resultSummary", truncate(outcome.summary(), 1900));
        row.put("elapsedMs", (int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs)));
        row.put("operatorId", caller.userId());
        row.put("operatorName", caller.username());
        toolCallMapper.insertToolCall(row, caller.username());
        return numberAsLong(row.get("id"));
    }

    public List<Map<String, Object>> listBySession(Long sessionId) {
        return toolCallMapper.selectToolCalls(sessionId);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
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
