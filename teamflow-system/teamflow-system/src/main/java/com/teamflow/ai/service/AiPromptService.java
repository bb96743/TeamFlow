package com.teamflow.ai.service;

import com.teamflow.ai.mapper.AiPromptMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * M11 提示词服务：提示词版本化落库（R4），应用按编码读取生效的最大版本。
 *
 * <p>数据库未就绪或某编码缺失时使用内置兜底文案，保证对话可用；
 * 修改提示词应新增版本，不覆盖历史。</p>
 */
@Service
public class AiPromptService {

    public static final String INTENT_SYSTEM = "intent_system";
    public static final String KB_ANSWER_SYSTEM = "kb_answer_system";
    public static final String DAILY_REPORT_SYSTEM = "daily_report_system";
    public static final String WEEKLY_REPORT_SYSTEM = "weekly_report_system";

    private final AiPromptMapper promptMapper;

    public AiPromptService(AiPromptMapper promptMapper) {
        this.promptMapper = promptMapper;
    }

    public String content(String promptCode) {
        Map<String, Object> row = promptMapper.selectActivePrompt(promptCode);
        if (row != null && row.get("content") != null) {
            return String.valueOf(row.get("content"));
        }
        return fallback(promptCode);
    }

    public String version(String promptCode) {
        Map<String, Object> row = promptMapper.selectActivePrompt(promptCode);
        return row == null ? "内置" : "v" + row.get("versionNo");
    }

    /** 渲染 {{变量}} 占位；未提供的变量原样保留，便于排查模板问题。 */
    public String render(String promptCode, Map<String, String> variables) {
        String template = content(promptCode);
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String fallback(String promptCode) {
        return switch (promptCode) {
            case INTENT_SYSTEM -> "判断用户意图，输出 intent 与员工姓名/关键词/时间范围。";
            case KB_ANSWER_SYSTEM -> "只依据给定检索片段回答，并标注引用序号；不足时明确说未找到依据。";
            case DAILY_REPORT_SYSTEM -> "基于给定事实生成日报草稿，不编造，缺失信息写待补充。";
            case WEEKLY_REPORT_SYSTEM -> "基于给定事实生成周报草稿，不编造，缺失信息写待补充。";
            default -> "";
        };
    }
}
