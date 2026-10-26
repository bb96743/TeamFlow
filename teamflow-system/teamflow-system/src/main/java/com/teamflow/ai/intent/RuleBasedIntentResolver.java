package com.teamflow.ai.intent;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.tool.AiToolSupport;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则意图识别（默认实现，无任何外部依赖）。
 *
 * <p>关键词 + 槽位规则完成意图分类：日报/周报、任务、绩效、目标、会议、知识问答；
 * 姓名槽位从员工目录匹配，时间槽位在工具执行时换算成区间。无法判断时返回能力提示，不编造工具调用。</p>
 */
@Component
public class RuleBasedIntentResolver implements IntentResolver {

    public static final String TASK_QUERY = "task_query";
    public static final String PERF_QUERY = "perf_query";
    public static final String OKR_QUERY = "okr_query";
    public static final String MEETING_QUERY = "meeting_query";
    public static final String KNOWLEDGE_QA = "knowledge_qa";
    public static final String DAILY_REPORT = "daily_report";
    public static final String WEEKLY_REPORT = "weekly_report";
    public static final String UNKNOWN = "unknown";

    private static final List<String> GREETINGS = List.of("你好", "您好", "hi", "hello", "谢谢", "在吗");
    private static final List<String> KNOWLEDGE_STOPWORDS = List.of(
            "请", "帮我", "帮忙", "查一下", "查询", "查查", "找一下", "找找", "看看", "看一下",
            "知识库", "知识", "文档", "页面", "内容", "里面", "关于", "回答", "一下",
            "是什么", "怎么", "如何", "哪一天", "哪一周", "什么时候", "多少", "吗", "呢",
            "？", "?", "。", "，", ",", "、", "：", ":", "的", "了");

    private final AiToolSupport support;

    public RuleBasedIntentResolver(AiToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "rule";
    }

    @Override
    public IntentResult resolve(String message, AiCaller caller) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return IntentResult.unknown("消息为空");
        }
        String lower = text.toLowerCase();
        Map<String, Object> user = support.findUser(text);
        String employeeName = user == null ? "" : support.displayName(user);
        String range = detectRange(text);

        if (containsAny(text, "日报", "daily report", "工作日报")) {
            return IntentResult.of(DAILY_REPORT, "生成日报草稿", "report.generate",
                    params(Map.of("type", "daily", "date", LocalDate.now().toString())), 0.9, "关键词命中：日报");
        }
        if (containsAny(text, "周报", "weekly report", "工作周报")) {
            return IntentResult.of(WEEKLY_REPORT, "生成周报草稿", "report.generate",
                    params(Map.of("type", "weekly")), 0.9, "关键词命中：周报");
        }
        if (containsAny(text, "绩效", "评分", "考核", "performance", "perf")) {
            return IntentResult.of(PERF_QUERY, "查询绩效结果", "perf.query",
                    params(Map.of("employeeName", employeeName)), 0.86,
                    employeeName.isBlank() ? "关键词命中：绩效（未识别员工）" : "关键词命中：绩效；员工：" + employeeName);
        }
        if (containsAny(lower, "okr", "目标", "关键结果", "key result", "kr")) {
            Map<String, Object> slots = new LinkedHashMap<>();
            slots.put("employeeName", employeeName);
            if (!employeeName.isBlank()) {
                slots.put("keyword", "");
            } else {
                slots.put("keyword", normalizeForKeyword(text));
            }
            return IntentResult.of(OKR_QUERY, "查询目标与关键结果", "okr.query", slots, 0.84,
                    "关键词命中：目标/OKR" + (employeeName.isBlank() ? "" : "；员工：" + employeeName));
        }
        if (containsAny(text, "任务", "待办", "task")) {
            return IntentResult.of(TASK_QUERY, "查询任务", "task.query",
                    params(Map.of("employeeName", employeeName, "range", range)), 0.9,
                    employeeName.isBlank() ? "关键词命中：任务；范围：" + range
                            : "关键词命中：任务；员工：" + employeeName + "；范围：" + range);
        }
        if (containsAny(text, "会议", "日程", "meeting", "日历")) {
            return IntentResult.of(MEETING_QUERY, "查询会议与日程", "meeting.query",
                    params(Map.of("range", range)), 0.82, "关键词命中：会议/日程；范围：" + range);
        }
        if (containsAny(text, "知识", "文档", "规范", "流程", "sop", "制度", "怎么做", "如何")) {
            return IntentResult.of(KNOWLEDGE_QA, "知识库问答", "knowledge.qa",
                    params(Map.of("keyword", normalizeForKeyword(text))), 0.8, "关键词命中：知识/文档");
        }
        if (text.endsWith("?") || text.endsWith("？") || containsAny(text, "什么", "为什么", "哪", "是否")) {
            return IntentResult.of(KNOWLEDGE_QA, "知识库问答", "knowledge.qa",
                    params(Map.of("keyword", normalizeForKeyword(text))), 0.6, "疑问句兜底：知识库检索");
        }
        if (containsAny(lower, GREETINGS.toArray(String[]::new))) {
            return IntentResult.unknown("问候语");
        }
        // 极短寒暄/无法判断：返回能力提示，不调用工具
        return IntentResult.unknown("未命中任何工具规则");
    }

    private Map<String, Object> params(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(key, value);
            }
        });
        return result;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String detectRange(String text) {
        if (text.contains("上周") || text.contains("上个星期")) {
            return "last_week";
        }
        if (text.contains("今天") || text.contains("今日")) {
            return "today";
        }
        if (text.contains("本月") || text.contains("这个月")) {
            return "this_month";
        }
        return "this_week";
    }

    /** 去掉提问与礼貌词，保留可检索的关键短语。 */
    private String normalizeForKeyword(String text) {
        String cleaned = text;
        for (String stopword : KNOWLEDGE_STOPWORDS) {
            cleaned = cleaned.replace(stopword, " ");
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? text.trim() : cleaned;
    }
}
