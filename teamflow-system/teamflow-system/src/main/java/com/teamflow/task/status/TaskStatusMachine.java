package com.teamflow.task.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M03 任务状态机（**全模块唯一一份状态枚举 + 允许迁移表**，M03 RULE §六 R1）。
 *
 * <p><b>为什么集中在这里</b>：L0 §9.1 {@code D-2026-10-25-01} 裁定 M03 用自研轻量状态机、不引 Flowable，
 * 并要求「状态枚举与允许迁移表只允许存在一份且外置为数据（便于将来换引擎）」。因此：</p>
 * <ul>
 *   <li>各状态以常量 + 中文标签**声明式**登记在 {@link #LABELS}；</li>
 *   <li>允许迁移以**声明式迁移表** {@link #ALLOWED_TRANSITIONS} 登记；</li>
 *   <li>Controller / Service <b>禁止</b>再写 {@code if (status == X)} 之类的流转合法性判断，
 *       一律调用 {@link #canTransit(String, String)}，非法迁移由 {@link #requireTransition(String, String)}
 *       统一抛出带明确前后状态文案的业务异常；</li>
 *   <li>{@link #definition()} 把同一份数据暴露为可查询结构（{@code GET /api/task/flow/status-machine}），
 *       将来若换 BPMN 引擎，可直接把这份数据搬到 {@code flow_definition.nodes_json}，无需改调用方。</li>
 * </ul>
 *
 * <p>本期只做 M1 需要的线性流转；会签 / 并行网关 / 加签属 P2 的 3.1 审批中心，不在本类范围。</p>
 */
public final class TaskStatusMachine {

    /** 待认领：仅用于「无责任人任务只允许存在于待认领池」（M03 RULE §六 R4）。 */
    public static final String POOL = "pool";
    /** 待办。 */
    public static final String TODO = "todo";
    /** 进行中。 */
    public static final String IN_PROGRESS = "in_progress";
    /** 已完成（M02 的 KR 回写只认这个终态之一，值必须与 {@code TaskStatusChangedEvent.STATUS_DONE} 一致）。 */
    public static final String DONE = "done";
    /** 已关闭（终态，不可再流转；保留数据用于绩效归集与审计）。 */
    public static final String CLOSED = "closed";

    /** 状态的唯一登记表：状态码 → 中文标签。顺序即对外展示顺序。 */
    private static final Map<String, String> LABELS;

    /** 允许迁移的唯一登记表：from → 允许的 to 集合（空集表示终态）。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(POOL, "待认领");
        labels.put(TODO, "待办");
        labels.put(IN_PROGRESS, "进行中");
        labels.put(DONE, "已完成");
        labels.put(CLOSED, "已关闭");
        LABELS = Collections.unmodifiableMap(labels);

        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put(POOL, Set.of(TODO));
        transitions.put(TODO, new LinkedHashSet<>(List.of(IN_PROGRESS, CLOSED)));
        transitions.put(IN_PROGRESS, new LinkedHashSet<>(List.of(TODO, DONE, CLOSED)));
        transitions.put(DONE, new LinkedHashSet<>(List.of(IN_PROGRESS, CLOSED)));
        transitions.put(CLOSED, Set.of());
        Map<String, Set<String>> readonly = new LinkedHashMap<>();
        transitions.forEach((from, targets) -> readonly.put(from, Collections.unmodifiableSet(targets)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(readonly);
    }

    private TaskStatusMachine() {
    }

    /** 全部合法状态码（有序）。 */
    public static Set<String> statuses() {
        return ALLOWED_TRANSITIONS.keySet();
    }

    /** 是否为合法状态码。 */
    public static boolean isStatus(String status) {
        return status != null && LABELS.containsKey(status);
    }

    /** 状态中文标签；未知状态原样返回，避免日志/响应出现 null。 */
    public static String label(String status) {
        return status == null ? "" : LABELS.getOrDefault(status, status);
    }

    /** 该状态是否为终态（不允许再向外迁移）。 */
    public static boolean isTerminal(String status) {
        return isStatus(status) && ALLOWED_TRANSITIONS.get(status).isEmpty();
    }

    /** 该状态是否要求必须有责任人（除待认领池外，其余状态都要求）。 */
    public static boolean requiresAssignee(String status) {
        return !POOL.equals(status);
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
        return new ArrayList<>(ALLOWED_TRANSITIONS.get(fromStatus));
    }

    /**
     * 校验迁移合法性：非法时抛出可读的业务异常文案（含前后状态中文标签与当前状态允许的目标）。
     *
     * @throws IllegalArgumentException 前端/接口层不应直接捕获，由调用方转成业务异常
     */
    public static void requireTransition(String fromStatus, String toStatus) {
        if (!isStatus(toStatus)) {
            throw new IllegalArgumentException("任务状态不合法：「" + toStatus + "」，合法状态为 "
                    + String.join("、", statuses()));
        }
        if (!isStatus(fromStatus)) {
            throw new IllegalArgumentException("任务当前状态不合法：「" + fromStatus + "」");
        }
        if (!canTransit(fromStatus, toStatus)) {
            throw new IllegalArgumentException("任务状态不允许从「" + label(fromStatus) + "」变更为「"
                    + label(toStatus) + "」，当前状态允许变更为：「"
                    + String.join("、", allowedTargets(fromStatus).stream().map(TaskStatusMachine::label).toList())
                    + "」");
        }
    }

    /**
     * 把唯一一份状态机数据导出为可查询结构（{@code GET /api/task/flow/status-machine}），
     * 供前端渲染、集成方自助查询、未来换引擎时迁移。
     */
    public static Map<String, Object> definition() {
        List<Map<String, Object>> states = new ArrayList<>();
        LABELS.forEach((code, label) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("label", label);
            state.put("terminal", isTerminal(code));
            state.put("requiresAssignee", requiresAssignee(code));
            states.add(state);
        });
        Map<String, Object> transitions = new LinkedHashMap<>();
        ALLOWED_TRANSITIONS.forEach((from, targets) -> transitions.put(from, allowedTargets(from)));
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("definitionCode", "task_status_linear");
        definition.put("singleSourceOfTruth", TaskStatusMachine.class.getName());
        definition.put("states", states);
        definition.put("transitions", transitions);
        return definition;
    }
}
