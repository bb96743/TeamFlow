package com.teamflow.ai.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果（统一封装「是否允许 / 摘要 / 数据 / 引用」）。
 *
 * <p>拒绝时 {@code allowed=false}，{@code summary} 是给用户的可读原因，不包含任何业务数据；
 * 这样越权请求不会因为异常重试或错误信息泄露数据。</p>
 */
public record ToolOutcome(
        boolean allowed,
        String toolName,
        Map<String, Object> params,
        String permissionCode,
        String summary,
        Object data,
        List<Map<String, Object>> citations,
        String denyReason
) {

    public static ToolOutcome ok(String toolName, Map<String, Object> params, String permissionCode,
                                 String summary, Object data) {
        return new ToolOutcome(true, toolName, params, permissionCode, summary, data, List.of(), null);
    }

    public static ToolOutcome ok(String toolName, Map<String, Object> params, String permissionCode,
                                 String summary, Object data, List<Map<String, Object>> citations) {
        return new ToolOutcome(true, toolName, params, permissionCode, summary, data, citations, null);
    }

    public static ToolOutcome denied(String toolName, Map<String, Object> params, String permissionCode, String reason) {
        return new ToolOutcome(false, toolName, params, permissionCode, reason, null, List.of(), reason);
    }

    public static ToolOutcome error(String toolName, Map<String, Object> params, String permissionCode, String message) {
        return new ToolOutcome(false, toolName, params, permissionCode, message, null, List.of(), message);
    }
}
