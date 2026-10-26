package com.teamflow.ai.service;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.domain.dto.AiChatRequest;
import com.teamflow.ai.domain.dto.AiKbQaRequest;
import com.teamflow.ai.intent.IntentResult;
import com.teamflow.ai.intent.LlmIntentResolver;
import com.teamflow.ai.intent.RuleBasedIntentResolver;
import com.teamflow.ai.support.AiJson;
import com.teamflow.ai.tool.LlmToolInvocation;
import com.teamflow.ai.tool.ToolOutcome;
import com.teamflow.ai.tool.ToolRegistry;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11 对话编排：意图识别 → 工具路由（以调用者身份）→ 回答与引用 → 落会话轨迹。
 *
 * <p>工具执行前由 ToolRegistry 校验权限；无权限时回答明确说明且不读取数据（R2）。
 * 默认规则实现；配置 {@code teamflow.ai.resolver=llm} 且 {@code llm-enabled=true} 时尝试
 * Spring AI ChatClient，失败或未配置模型自动回落规则实现（D-2026-10-27-12 结论二）。</p>
 */
@Service
public class AiConversationService {

    private static final int TITLE_MAX = 60;

    private final AiPermissionService permissionService;
    private final AiSessionService sessionService;
    private final RuleBasedIntentResolver ruleResolver;
    private final LlmIntentResolver llmResolver;
    private final ToolRegistry toolRegistry;
    private final AiToolCallService toolCallService;
    private final AiJson aiJson;
    private final AiProperties properties;

    public AiConversationService(AiPermissionService permissionService, AiSessionService sessionService,
                                 RuleBasedIntentResolver ruleResolver, LlmIntentResolver llmResolver,
                                 ToolRegistry toolRegistry, AiToolCallService toolCallService,
                                 AiJson aiJson, AiProperties properties) {
        this.permissionService = permissionService;
        this.sessionService = sessionService;
        this.ruleResolver = ruleResolver;
        this.llmResolver = llmResolver;
        this.toolRegistry = toolRegistry;
        this.toolCallService = toolCallService;
        this.aiJson = aiJson;
        this.properties = properties;
    }

    public Map<String, Object> chat(AiChatRequest request) {
        AiCaller caller = permissionService.caller();
        permissionService.require(caller, "ai:chat:use");
        String message = request == null || request.getMessage() == null ? "" : request.getMessage().trim();
        if (message.isEmpty()) {
            throw new BusinessException("消息不能为空");
        }
        long began = System.nanoTime();
        Long sessionId = request.getSessionId();
        if (sessionId == null) {
            sessionId = sessionService.create(caller, message, "chat");
        } else {
            sessionService.requireOwned(sessionId, caller);
        }
        sessionService.append(caller, sessionId, "user", message, Map.of());

        IntentResult intent = resolve(message, caller);
        ToolOutcome outcome = null;
        List<LlmToolInvocation> llmInvocations = intent.invocations() == null ? List.of() : intent.invocations();
        List<Map<String, Object>> citations = new ArrayList<>();
        String answer;
        String status = "ok";
        String executor;

        if (intent.directAnswer() != null && !intent.directAnswer().isBlank()) {
            executor = "llm";
            answer = intent.directAnswer();
            for (LlmToolInvocation invocation : llmInvocations) {
                if (!invocation.outcome().allowed()) {
                    status = "denied";
                }
                citations.addAll(invocation.outcome().citations());
            }
        } else if (intent.toolName() != null && !intent.toolName().isBlank()) {
            executor = "rule";
            long toolBegan = System.nanoTime();
            outcome = toolRegistry.execute(intent.toolName(), intent.params() == null ? Map.of() : intent.params(), caller);
            long toolTook = (System.nanoTime() - toolBegan) / 1_000_000L;
            if (!outcome.allowed()) {
                status = "denied";
            }
            answer = outcome.allowed() ? outcome.summary() : deniedAnswer(outcome);
            if (outcome.citations() != null) {
                citations.addAll(outcome.citations());
            }
            String toolLabel = labelOf(outcome.toolName());
            long assistantMessageId = sessionService.append(caller, sessionId, "assistant", answer,
                    assistantExtra(intent, outcome, status, (System.nanoTime() - began) / 1_000_000L, citations, executor));
            toolCallService.record(sessionId, assistantMessageId, caller, outcome, toolLabel, toolTook);
            sessionService.touch(caller, sessionId, 1, null);
            return response(sessionId, assistantMessageId, answer, intent, outcome, citations, status, executor,
                    (System.nanoTime() - began) / 1_000_000L);
        } else {
            executor = "rule";
            answer = unknownAnswer();
        }

        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        long assistantMessageId = sessionService.append(caller, sessionId, "assistant", answer,
                assistantExtra(intent, outcome, status, elapsedMs, citations, executor));
        for (LlmToolInvocation invocation : llmInvocations) {
            toolCallService.record(sessionId, assistantMessageId, caller, invocation.outcome(),
                    labelOf(invocation.toolName()), elapsedMs);
        }
        sessionService.touch(caller, sessionId, 1, null);
        return response(sessionId, assistantMessageId, answer, intent, outcome, citations, status, executor, elapsedMs);
    }

    public Map<String, Object> qa(AiKbQaRequest request) {
        AiCaller caller = permissionService.caller();
        permissionService.require(caller, "ai:kb:ask");
        String question = request == null || request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BusinessException("问题不能为空");
        }
        long began = System.nanoTime();
        Long sessionId = sessionService.create(caller, question, "qa");
        sessionService.append(caller, sessionId, "user", question, Map.of());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyword", question);
        if (request.getSpaceId() != null) {
            params.put("spaceId", request.getSpaceId());
        }
        if (request.getLimit() != null) {
            params.put("limit", request.getLimit());
        }
        ToolOutcome outcome = toolRegistry.execute("knowledge.qa", params, caller);
        String answer = outcome.allowed() ? outcome.summary() : deniedAnswer(outcome);
        List<Map<String, Object>> citations = outcome.citations() == null ? List.of() : outcome.citations();
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        Map<String, Object> extra = assistantExtra(
                IntentResult.of(RuleBasedIntentResolver.KNOWLEDGE_QA, "知识库问答", "knowledge.qa", params, 0.9, "知识库问答入口"),
                outcome, outcome.allowed() ? "ok" : "denied", elapsedMs, citations, "rule");
        long messageId = sessionService.append(caller, sessionId, "assistant", answer, extra);
        toolCallService.record(sessionId, messageId, caller, outcome, labelOf(outcome.toolName()), elapsedMs);
        sessionService.touch(caller, sessionId, 1, null);

        Map<String, Object> data = outcome.data() instanceof Map<?, ?> map ? castMap(map) : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("messageId", messageId);
        result.put("answer", answer);
        result.put("citations", citations);
        result.put("engine", data.get("engine"));
        result.put("elapsedMs", data.get("elapsedMs") == null ? elapsedMs : data.get("elapsedMs"));
        result.put("total", data.get("total"));
        result.put("status", outcome.allowed() ? "ok" : "denied");
        result.put("tool", toolMap(outcome));
        return result;
    }

    public PageResult<Map<String, Object>> listSessions(PageQuery query) {
        AiCaller caller = permissionService.caller();
        permissionService.require(caller, "ai:chat:use");
        return sessionService.list(caller, query);
    }

    public Map<String, Object> createSession(String title) {
        AiCaller caller = permissionService.caller();
        permissionService.require(caller, "ai:chat:use");
        Long id = sessionService.create(caller, title, "chat");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        return result;
    }

    public List<Map<String, Object>> messages(Long sessionId) {
        AiCaller caller = permissionService.caller();
        permissionService.require(caller, "ai:chat:use");
        return sessionService.messages(caller, sessionId);
    }

    // ------------------------------------------------------------------

    private IntentResult resolve(String message, AiCaller caller) {
        if ("llm".equalsIgnoreCase(properties.getResolver()) && llmResolver.available()) {
            IntentResult llmResult = llmResolver.resolve(message, caller);
            if (llmResult != null) {
                return llmResult;
            }
        }
        return ruleResolver.resolve(message, caller);
    }

    private Map<String, Object> assistantExtra(IntentResult intent, ToolOutcome outcome, String status,
                                               long elapsedMs, List<Map<String, Object>> citations, String executor) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("intentCode", intent.intentCode());
        extra.put("intentLabel", intent.intentLabel());
        extra.put("toolName", outcome == null ? intent.toolName() : outcome.toolName());
        extra.put("toolParams", aiJson.toJson(outcome == null ? intent.params() : outcome.params()));
        extra.put("toolResult", outcome == null ? null : aiJson.toJson(outcome.data()));
        extra.put("citations", citations == null || citations.isEmpty() ? null : aiJson.toJson(citations));
        extra.put("modelName", "llm".equals(executor) ? "spring-ai-chatclient(ollama,未验证)" : "rule-based-intent");
        extra.put("status", status);
        extra.put("elapsedMs", (int) Math.min(Integer.MAX_VALUE, elapsedMs));
        extra.put("tokenEstimate", aiJson.estimateTokens(intent.directAnswer()));
        return extra;
    }

    private Map<String, Object> response(Long sessionId, Long messageId, String answer, IntentResult intent,
                                         ToolOutcome outcome, List<Map<String, Object>> citations,
                                         String status, String executor, long elapsedMs) {
        Map<String, Object> intentMap = new LinkedHashMap<>();
        intentMap.put("code", intent.intentCode());
        intentMap.put("label", intent.intentLabel());
        intentMap.put("reason", intent.reason());
        intentMap.put("params", intent.params());
        intentMap.put("confidence", intent.confidence());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("messageId", messageId);
        result.put("answer", answer);
        result.put("intent", intentMap);
        result.put("tool", outcome == null ? null : toolMap(outcome));
        result.put("citations", citations == null ? List.of() : citations);
        result.put("status", status);
        result.put("executor", executor);
        result.put("elapsedMs", elapsedMs);
        return result;
    }

    private Map<String, Object> toolMap(ToolOutcome outcome) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", outcome.toolName());
        tool.put("params", outcome.params());
        tool.put("allowed", outcome.allowed());
        tool.put("permissionCode", outcome.permissionCode());
        tool.put("summary", outcome.summary());
        return tool;
    }

    private String labelOf(String toolName) {
        return toolRegistry.get(toolName) == null ? toolName : toolRegistry.get(toolName).label();
    }

    private String deniedAnswer(ToolOutcome outcome) {
        return outcome.summary() + "。如需查看该类数据，请向管理员申请对应权限；本次未读取任何业务数据。";
    }

    private String unknownAnswer() {
        return "我还不确定要查什么。可以这样问：\n"
                + "- 帮我查张三本周的任务\n"
                + "- 查张三的绩效\n"
                + "- 本周有哪些会议\n"
                + "- 联调窗口提前到哪一周（知识库问答）\n"
                + "- 生成日报 / 生成周报";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
