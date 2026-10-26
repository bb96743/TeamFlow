package com.teamflow.ai.intent;

import com.teamflow.ai.tool.LlmToolInvocation;

import java.util.List;
import java.util.Map;

/**
 * 意图识别结果：意图编码 + 要调用的工具 + 槽位参数。
 *
 * <p>规则实现与 LLM 实现返回同一结构；{@code directAnswer} 非空时表示 LLM 已直接产出回答
 * （工具已经由模型通过 {@code @Tool} 调用，{@code invocations} 记录轨迹）。</p>
 */
public record IntentResult(
        String intentCode,
        String intentLabel,
        String toolName,
        Map<String, Object> params,
        double confidence,
        String reason,
        String directAnswer,
        List<LlmToolInvocation> invocations
) {

    public static IntentResult of(String intentCode, String intentLabel, String toolName,
                                  Map<String, Object> params, double confidence, String reason) {
        return new IntentResult(intentCode, intentLabel, toolName, params, confidence, reason, null, List.of());
    }

    public static IntentResult unknown(String reason) {
        return new IntentResult("unknown", "未识别", null, Map.of(), 0.0, reason, null, List.of());
    }
}
