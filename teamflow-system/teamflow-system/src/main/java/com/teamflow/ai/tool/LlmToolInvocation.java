package com.teamflow.ai.tool;

import java.util.Map;

/**
 * LLM 路径中模型实际触发的工具调用（由 {@code @Tool} 回调记录，供审计与响应回放）。
 */
public record LlmToolInvocation(String toolName, Map<String, Object> params, ToolOutcome outcome) {
}
