package com.teamflow.ai.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 工具回调轨迹的临时容器（一次模型调用内的 @Tool 触发记录）。
 */
public class LlmInvocationHolder {

    private final List<LlmToolInvocation> invocations = new ArrayList<>();

    public void add(LlmToolInvocation invocation) {
        invocations.add(invocation);
    }

    public List<LlmToolInvocation> invocations() {
        return List.copyOf(invocations);
    }
}
