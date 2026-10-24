package com.teamflow.framework.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Aspect
@Component
public class OperationLogAspect {

    private static final int MAX_PARAMS_LENGTH = 4_000;
    private static final int MAX_ERROR_LENGTH = 4_000;

    private final OperationLogRecorder recorder;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(operLog)")
    public Object record(ProceedingJoinPoint joinPoint, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            record(joinPoint, operLog, start, 1, null);
            return result;
        } catch (Throwable error) {
            record(joinPoint, operLog, start, 0, error);
            throw error;
        }
    }

    private void record(ProceedingJoinPoint joinPoint, OperLog operLog, long start, int result, Throwable error) {
        HttpServletRequest request = currentRequest();
        recorder.record(new OperationLogEntry(
                operLog.module(),
                operLog.action(),
                request == null ? null : request.getMethod(),
                request == null ? null : request.getRequestURI(),
                truncate(params(joinPoint.getArgs()), MAX_PARAMS_LENGTH),
                currentUsername(),
                request == null ? null : clientIp(request),
                System.currentTimeMillis() - start,
                result,
                truncate(error == null ? null : error.getMessage(), MAX_ERROR_LENGTH)
        ));
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String params(Object[] args) {
        try {
            JsonNode root = objectMapper.valueToTree(args);
            maskSensitiveFields(root);
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException error) {
            // Jackson 3 中 JacksonException 是非受检异常，此处 catch 属防御性保留：
            // 审计日志绝不能因为参数序列化失败而影响主流程
            return "[]";
        }
    }

    private void maskSensitiveFields(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            // Jackson 3 移除了 fieldNames()，改用 properties()
            for (Map.Entry<String, JsonNode> property : objectNode.properties()) {
                if (isSensitive(property.getKey())) {
                    objectNode.put(property.getKey(), "******");
                } else {
                    maskSensitiveFields(property.getValue());
                }
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            // Jackson 3 中 values() 返回 Collection，可直接 for-each
            for (JsonNode element : arrayNode.values()) {
                maskSensitiveFields(element);
            }
        }
    }

    private boolean isSensitive(String field) {
        String lower = field.toLowerCase();
        return lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("authorization");
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : String.valueOf(authentication.getPrincipal());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
