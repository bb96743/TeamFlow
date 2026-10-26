package com.teamflow.ai.intent;

import com.teamflow.ai.domain.AiCaller;

/**
 * 意图识别器：把自然语言映射到工具与槽位。
 *
 * <p>两种实现：规则实现（默认，无外部依赖）与 Spring AI ChatClient 实现（配置启用）。
 * 两者都只负责「识别」，工具执行统一由 ToolRegistry 以调用者身份完成。</p>
 */
public interface IntentResolver {

    String name();

    IntentResult resolve(String message, AiCaller caller);
}
