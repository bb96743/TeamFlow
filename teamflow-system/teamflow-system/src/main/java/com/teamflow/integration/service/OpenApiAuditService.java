package com.teamflow.integration.service;

import com.teamflow.integration.mapper.OpenEventLogMapper;
import com.teamflow.integration.security.ApiKeyIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放平台调用审计（M12 RULE R2：每次调用记一条审计）。
 *
 * <p>审计失败<b>不影响</b>主流程；日志不记录任何密钥/凭据内容（只记前缀与 ID）。</p>
 */
@Service
public class OpenApiAuditService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuditService.class);

    private final OpenEventLogMapper eventLogMapper;

    public OpenApiAuditService(OpenEventLogMapper eventLogMapper) {
        this.eventLogMapper = eventLogMapper;
    }

    /** 记录一次开放接口调用（在请求完成时由拦截器写入）。 */
    public void recordOpenApi(ApiKeyIdentity identity, HttpServletRequest request, int status, long costMs,
                              String detail) {
        Map<String, Object> row = baseRow("open_api", identity, status, costMs);
        row.put("method", request.getMethod());
        row.put("path", truncate(request.getRequestURI(), 255));
        row.put("clientIp", clientIp(request));
        row.put("requestId", requestId());
        row.put("detail", truncate(detail, 500));
        write(row);
    }

    /** 记录一次 MCP 工具调用（由工具方法内部写入）。 */
    public void recordMcp(String toolName, ApiKeyIdentity identity, int status, long costMs, String detail) {
        Map<String, Object> row = baseRow("mcp", identity, status, costMs);
        row.put("method", "TOOL");
        row.put("path", truncate(toolName, 255));
        row.put("clientIp", null);
        row.put("requestId", requestId());
        row.put("detail", truncate(detail, 500));
        write(row);
    }

    /** 最近调用记录（管理端排障用）。 */
    public List<Map<String, Object>> recent(String channel, Long keyId) {
        return eventLogMapper.selectRecent(channel, keyId);
    }

    private Map<String, Object> baseRow(String channel, ApiKeyIdentity identity, int status, long costMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", channel);
        row.put("keyId", identity == null ? null : identity.keyId());
        row.put("appId", identity == null ? null : identity.appId());
        row.put("userId", identity == null ? null : identity.userId());
        row.put("status", status);
        row.put("costMs", (int) Math.min(costMs, Integer.MAX_VALUE));
        return row;
    }

    private void write(Map<String, Object> row) {
        try {
            eventLogMapper.insertLog(row, "system");
        } catch (RuntimeException exception) {
            log.warn("开放调用审计写入失败（不影响主流程）：{}", exception.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String requestId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? null : traceId;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
