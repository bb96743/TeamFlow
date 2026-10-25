package com.teamflow.perf.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M05 绩效周期状态机（**本模块唯一一份状态枚举 + 允许迁移表**，M05 RULE §六 R5）。
 *
 * <p><b>为什么集中在这里</b>：与 M03 的 {@code TaskStatusMachine} 同构——状态码、中文标签、
 * 允许迁移表只允许存在一份且声明式外置，Controller / Service <b>禁止</b>散落
 * {@code if (status == X)} 判断，一律调用 {@link #canTransit} / {@link #requireTransition}。</p>
 *
 * <p><b>状态机</b>（锁定不可逆，R5）：</p>
 * <pre>
 * draft(草稿) → active(进行中) → scoring(评分中) → archived(已归档) → locked(已锁定，终态)
 * </pre>
 * <ul>
 *   <li>{@code archived} 表示结果已固化、只读；{@code locked} 表示周期彻底锁定，任何修改只能走申诉；</li>
 *   <li>{@code locked} 无出边（不可逆）；不存在"解锁"入口。</li>
 * </ul>
 */
public final class PerfCycleStatusMachine {

    /** 草稿：可编辑周期/模板绑定，可做归集预览。 */
    public static final String DRAFT = "draft";
    /** 进行中：周期已开启（模板权重校验通过），可归集、可自评。 */
    public static final String ACTIVE = "active";
    /** 评分中：可自评/上级评，仍可重算归集。 */
    public static final String SCORING = "scoring";
    /** 已归档：结果固化、只读；仅允许锁定。 */
    public static final String ARCHIVED = "archived";
    /** 已锁定：终态，任何修改必须走 perf_appeal。 */
    public static final String LOCKED = "locked";

    private static final Map<String, String> LABELS;

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(DRAFT, "草稿");
        labels.put(ACTIVE, "进行中");
        labels.put(SCORING, "评分中");
        labels.put(ARCHIVED, "已归档");
        labels.put(LOCKED, "已锁定");
        LABELS = Collections.unmodifiableMap(labels);

        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put(DRAFT, new LinkedHashSet<>(List.of(ACTIVE)));
        transitions.put(ACTIVE, new LinkedHashSet<>(List.of(SCORING)));
        transitions.put(SCORING, new LinkedHashSet<>(List.of(ARCHIVED)));
        transitions.put(ARCHIVED, new LinkedHashSet<>(List.of(LOCKED)));
        transitions.put(LOCKED, Set.of());
        Map<String, Set<String>> readonly = new LinkedHashMap<>();
        transitions.forEach((from, targets) -> readonly.put(from, Collections.unmodifiableSet(targets)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(readonly);
    }

    private PerfCycleStatusMachine() {
    }

    /** 全部合法状态码（有序）。 */
    public static Set<String> statuses() {
        return ALLOWED_TRANSITIONS.keySet();
    }

    /** 是否为合法状态码。 */
    public static boolean isStatus(String status) {
        return status != null && LABELS.containsKey(status);
    }

    /** 状态中文标签；未知状态原样返回，避免响应出现 null。 */
    public static String label(String status) {
        return status == null ? "" : LABELS.getOrDefault(status, status);
    }

    /** 该状态是否可编辑（草稿/进行中/评分中）。 */
    public static boolean isEditable(String status) {
        return DRAFT.equals(status) || ACTIVE.equals(status) || SCORING.equals(status);
    }

    /** 该状态是否已锁定（终态，只读）。 */
    public static boolean isLocked(String status) {
        return LOCKED.equals(status);
    }

    /** 该状态是否允许归集/重算（草稿可预演、进行中与评分中可重算；归档/锁定后不得改动结果）。 */
    public static boolean isCollectable(String status) {
        return DRAFT.equals(status) || ACTIVE.equals(status) || SCORING.equals(status);
    }

    /** 状态迁移是否合法（唯一判定入口）。 */
    public static boolean canTransit(String fromStatus, String toStatus) {
        if (!isStatus(fromStatus) || !isStatus(toStatus)) {
            return false;
        }
        return ALLOWED_TRANSITIONS.get(fromStatus).contains(toStatus);
    }

    /** 某状态允许迁移到的目标状态（有序副本）。 */
    public static List<String> allowedTargets(String fromStatus) {
        if (!isStatus(fromStatus)) {
            return List.of();
        }
        return new java.util.ArrayList<>(ALLOWED_TRANSITIONS.get(fromStatus));
    }

    /**
     * 校验迁移合法性：非法时抛出可读的业务异常文案（含前后状态中文标签与当前状态允许的目标）。
     */
    public static void requireTransition(String fromStatus, String toStatus) {
        if (!isStatus(toStatus)) {
            throw new IllegalArgumentException("绩效周期状态不合法：「" + toStatus + "」，合法状态为 "
                    + String.join("、", statuses()));
        }
        if (!isStatus(fromStatus)) {
            throw new IllegalArgumentException("绩效周期当前状态不合法：「" + fromStatus + "」");
        }
        if (!canTransit(fromStatus, toStatus)) {
            throw new IllegalArgumentException("绩效周期状态不允许从「" + label(fromStatus) + "」变更为「"
                    + label(toStatus) + "」，当前状态允许变更为：「"
                    + String.join("、", allowedTargets(fromStatus).stream().map(PerfCycleStatusMachine::label).toList())
                    + "」");
        }
    }

    /** 把唯一一份状态机数据导出为可查询结构（供前端渲染与联调自查）。 */
    public static Map<String, Object> definition() {
        List<Map<String, Object>> states = new java.util.ArrayList<>();
        LABELS.forEach((code, label) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("label", label);
            state.put("editable", isEditable(code));
            state.put("collectable", isCollectable(code));
            states.add(state);
        });
        Map<String, Object> transitions = new LinkedHashMap<>();
        ALLOWED_TRANSITIONS.forEach((from, targets) -> transitions.put(from, allowedTargets(from)));
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("definitionCode", "perf_cycle_linear");
        definition.put("singleSourceOfTruth", PerfCycleStatusMachine.class.getName());
        definition.put("states", states);
        definition.put("transitions", transitions);
        return definition;
    }
}
