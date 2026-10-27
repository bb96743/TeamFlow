package com.teamflow.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M11 AI 助手配置（前缀 {@code teamflow.ai}）。
 *
 * <p>默认走规则实现（无外部依赖）；配置了模型时可通过 {@code resolver=llm} + {@code llm-enabled=true}
 * 切换到 Spring AI {@code ChatClient} 的意图识别。真实模型推理未在本机验证（见 L0 D-2026-10-27-12）。</p>
 */
@ConfigurationProperties(prefix = "teamflow.ai")
public class AiProperties {

    /** 意图识别实现：rule 规则（默认）/ llm 大模型。 */
    private String resolver = "rule";

    /** 是否启用 LLM 路径；关闭时即使 resolver=llm 也回落规则实现。 */
    private boolean llmEnabled = false;

    /** Ollama 服务地址。 */
    private String ollamaBaseUrl = "http://localhost:11434";

    /** Ollama 对话模型名。 */
    private String ollamaModel = "qwen2.5:7b";

    /** 单次模型调用超时（秒），仅记录在提示词/配置说明中，实际超时由 HTTP 客户端控制。 */
    private int timeoutSeconds = 20;

    /** 工具调用最大轮次（防止无限循环）。 */
    private int maxToolRounds = 3;

    /** 知识问答最多返回的引用条数。 */
    private int maxCitations = 5;

    /** 进入模型上下文的片段总字符上限（成本控制）。 */
    private int maxContextChars = 4000;

    /** 智能提醒默认提前天数（任务到期前 N 天开始提醒）。 */
    private int reminderDaysAhead = 3;

    /** 任务分配建议的候选人数上限（一次批量取负载的用户数量）。 */
    private int suggestionCandidateLimit = 20;

    public String getResolver() { return resolver; }
    public void setResolver(String resolver) { this.resolver = resolver; }

    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }

    public String getOllamaBaseUrl() { return ollamaBaseUrl; }
    public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }

    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = ollamaModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }

    public int getMaxCitations() { return maxCitations; }
    public void setMaxCitations(int maxCitations) { this.maxCitations = maxCitations; }

    public int getMaxContextChars() { return maxContextChars; }
    public void setMaxContextChars(int maxContextChars) { this.maxContextChars = maxContextChars; }

    public int getReminderDaysAhead() { return reminderDaysAhead; }
    public void setReminderDaysAhead(int reminderDaysAhead) { this.reminderDaysAhead = reminderDaysAhead; }

    public int getSuggestionCandidateLimit() { return suggestionCandidateLimit; }
    public void setSuggestionCandidateLimit(int suggestionCandidateLimit) { this.suggestionCandidateLimit = suggestionCandidateLimit; }
}
