package com.teamflow.ai.intent;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.service.AiPromptService;
import com.teamflow.ai.tool.AiLlmToolFacade;
import com.teamflow.ai.tool.LlmInvocationHolder;
import com.teamflow.ai.tool.LlmToolInvocation;
import com.teamflow.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 意图识别与工具调用（Spring AI 2.0 {@code ChatClient}，配置了模型才启用）。
 *
 * <p>模型通过 {@link AiLlmToolFacade} 暴露的 {@code @Tool} 白名单取数，工具统一经 ToolRegistry
 * 以当前用户身份执行；模型只负责理解与组织语言。本机无模型/无 Key，真实推理未验证，
 * 任何异常都会回落到规则实现（见 L0 D-2026-10-27-12 结论二与诚实边界）。</p>
 */
@Component
public class LlmIntentResolver implements IntentResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentResolver.class);

    private final AiProperties properties;
    private final AiPromptService promptService;
    private final ToolRegistry toolRegistry;
    private volatile ChatModel chatModel;

    public LlmIntentResolver(AiProperties properties, AiPromptService promptService, ToolRegistry toolRegistry) {
        this.properties = properties;
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "llm";
    }

    public boolean available() {
        return properties.isLlmEnabled();
    }

    /** 模型不可用或调用失败时返回 null，由调用方回落到规则实现。 */
    @Override
    public IntentResult resolve(String message, AiCaller caller) {
        if (!available()) {
            return null;
        }
        try {
            LlmInvocationHolder holder = new LlmInvocationHolder();
            AiLlmToolFacade facade = new AiLlmToolFacade(toolRegistry, caller, holder);
            String systemPrompt = promptService.render(AiPromptService.INTENT_SYSTEM, Map.of(
                    "tools", toolCatalog(),
                    "caller", caller.displayName(),
                    "maxRounds", String.valueOf(properties.getMaxToolRounds())
            ));
            ChatClient client = ChatClient.builder(chatModel()).defaultTools(facade).build();
            String answer = client.prompt().system(systemPrompt).user(message).call().content();
            if (answer == null || answer.isBlank()) {
                return null;
            }
            List<LlmToolInvocation> invocations = holder.invocations();
            String toolName = invocations.isEmpty() ? null : invocations.get(invocations.size() - 1).toolName();
            return new IntentResult(
                    "llm",
                    "大模型识别",
                    toolName,
                    Map.of(),
                    0.7,
                    "LLM 路径（本机无模型，未验证）",
                    answer,
                    invocations
            );
        } catch (RuntimeException exception) {
            log.warn("LLM 意图识别失败，回落到规则实现：{}", exception.getMessage());
            return null;
        }
    }

    private ChatModel chatModel() {
        ChatModel current = chatModel;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (chatModel == null) {
                OllamaApi api = OllamaApi.builder().baseUrl(properties.getOllamaBaseUrl()).build();
                OllamaChatOptions options = OllamaChatOptions.builder()
                        .model(resolveModel(properties.getOllamaModel()))
                        .build();
                chatModel = OllamaChatModel.builder().ollamaApi(api).options(options).build();
            }
            return chatModel;
        }
    }

    /** Ollama 2.0 的选项模型是枚举；配置名命中枚举时使用之，否则回退到默认已知模型并记录。 */
    private OllamaModel resolveModel(String configured) {
        if (configured != null && !configured.isBlank()) {
            for (OllamaModel model : OllamaModel.values()) {
                if (configured.equalsIgnoreCase(model.id())
                        || configured.equalsIgnoreCase(model.getName())
                        || configured.equalsIgnoreCase(model.name())) {
                    return model;
                }
            }
            log.warn("配置的 Ollama 模型 {} 未在 Spring AI 枚举中登记，回退到 QWEN_2_5_7B", configured);
        }
        return OllamaModel.QWEN_2_5_7B;
    }

    private String toolCatalog() {
        StringBuilder builder = new StringBuilder();
        toolRegistry.all().forEach(tool -> builder.append("- ").append(tool.name())
                .append("：").append(tool.label()).append("\n"));
        return builder.toString();
    }
}
