package com.teamflow.ai.support;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * M11 JSON 小工具：统一序列化工具入参/结果，序列化失败时退化为可读文本（不阻断对话）。
 */
@Component
public class AiJson {

    private final ObjectMapper objectMapper;

    public AiJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            return String.valueOf(value);
        }
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (RuntimeException exception) {
            return Map.of("raw", json);
        }
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // 粗略估算：中文按 1 字 1 token，英文按 4 字符 1 token，仅用于成本提示
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + Math.max(1, (text.length() - cjk) / 4);
    }
}
