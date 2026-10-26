package com.teamflow.project.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14 项目风险状态机（**风险状态的唯一来源**，M14 RULE §六）。
 *
 * <p>状态主线：{@code identified 已识别 → assessed 已评估 → responding 应对中 → closed 已关闭}；
 * identified / assessed / responding 可转 {@code cancelled 已取消}；closed 与 cancelled 为终态。
 * Service 只调用 {@link #requireTransition}，禁止散落 {@code if (status == X)}；迁移表通过
 * {@link #definition()} 对外导出，前端据此渲染可流转目标。</p>
 */
public final class ProjectRiskStatusMachine {

    public static final String RISK_IDENTIFIED = "identified";
    public static final String RISK_ASSESSED = "assessed";
    public static final String RISK_RESPONDING = "responding";
    public static final String RISK_CLOSED = "closed";
    public static final String RISK_CANCELLED = "cancelled";

    public static final Set<String> LEVELS = Set.of("low", "medium", "high", "critical");
    public static final Set<String> TYPES = Set.of("schedule", "cost", "quality", "resource", "other");

    private static final Map<String, String> LABELS;
    private static final Map<String, Set<String>> TRANSITIONS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(RISK_IDENTIFIED, "已识别");
        labels.put(RISK_ASSESSED, "已评估");
        labels.put(RISK_RESPONDING, "应对中");
        labels.put(RISK_CLOSED, "已关闭");
        labels.put(RISK_CANCELLED, "已取消");
        LABELS = Collections.unmodifiableMap(labels);

        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put(RISK_IDENTIFIED, setOf(RISK_ASSESSED, RISK_CANCELLED));
        transitions.put(RISK_ASSESSED, setOf(RISK_RESPONDING, RISK_CANCELLED));
        transitions.put(RISK_RESPONDING, setOf(RISK_CLOSED, RISK_CANCELLED));
        transitions.put(RISK_CLOSED, Set.of());
        transitions.put(RISK_CANCELLED, Set.of());
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private ProjectRiskStatusMachine() {
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    public static boolean isStatus(String status) {
        return status != null && LABELS.containsKey(status);
    }

    public static String label(String status) {
        return status == null ? "" : LABELS.getOrDefault(status, status);
    }

    public static String levelLabel(String level) {
        return switch (level == null ? "" : level) {
            case "low" -> "低";
            case "medium" -> "中";
            case "high" -> "高";
            case "critical" -> "严重";
            default -> level;
        };
    }

    public static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "schedule" -> "进度";
            case "cost" -> "成本";
            case "quality" -> "质量";
            case "resource" -> "资源";
            case "other" -> "其他";
            default -> type;
        };
    }

    public static boolean canTransit(String from, String to) {
        if (!isStatus(from) || !isStatus(to)) {
            return false;
        }
        return TRANSITIONS.get(from).contains(to);
    }

    public static List<String> nextStatuses(String from) {
        return isStatus(from) ? new ArrayList<>(TRANSITIONS.get(from)) : List.of();
    }

    public static void requireTransition(String from, String to) {
        if (!isStatus(to)) {
            throw new IllegalArgumentException("风险状态不合法：「" + to + "」，合法状态为 "
                    + String.join("、", LABELS.keySet()));
        }
        if (!isStatus(from)) {
            throw new IllegalArgumentException("风险当前状态不合法：「" + from + "」");
        }
        if (!canTransit(from, to)) {
            throw new IllegalArgumentException("风险状态不允许从「" + label(from) + "」变更为「" + label(to)
                    + "」，当前状态允许变更为：「"
                    + String.join("、", nextStatuses(from).stream().map(ProjectRiskStatusMachine::label).toList())
                    + "」");
        }
    }

    /** 状态机定义（唯一来源导出，供前端渲染）。 */
    public static Map<String, Object> definition() {
        List<Map<String, Object>> states = new ArrayList<>();
        LABELS.forEach((code, label) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("label", label);
            state.put("terminal", TRANSITIONS.get(code).isEmpty());
            states.add(state);
        });
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("states", states);
        definition.put("transitions", TRANSITIONS);
        definition.put("levels", List.of("low", "medium", "high", "critical"));
        definition.put("types", List.of("schedule", "cost", "quality", "resource", "other"));
        definition.put("singleSourceOfTruth", ProjectRiskStatusMachine.class.getName());
        return definition;
    }
}
