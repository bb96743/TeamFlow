package com.teamflow.project.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14 项目状态机（**项目状态与里程碑状态的唯一来源**，M14 RULE §六）。
 *
 * <p>与 M03/M08 的写法一致：状态码、中文标签与允许迁移表集中声明，Service 只调用
 * {@link #requireProjectTransition}/{@link #requireMilestoneTransition}，禁止散落
 * {@code if (status == X)} 判断；迁移表通过 {@link #definition()} 对外导出。</p>
 *
 * <p><b>项目状态（L0 2.5.1「立项」）</b>：`draft 立项草稿 → active 进行中 → closed 已结项 → archived 已归档`，
 * 其中 archived 为终态、不可逆；除该主线外不允许跳跃或回退（如 draft→closed 被拒）。</p>
 * <p><b>里程碑状态</b>：`planned 计划中 → in_progress 进行中 → completed 已完成`；
 * planned/in_progress 可 `cancelled 已取消`；completed / cancelled 为终态。</p>
 */
public final class ProjectStatusMachine {

    // ================= 项目状态 =================
    public static final String PROJECT_DRAFT = "draft";
    public static final String PROJECT_ACTIVE = "active";
    public static final String PROJECT_CLOSED = "closed";
    public static final String PROJECT_ARCHIVED = "archived";

    // ================= 里程碑状态 =================
    public static final String MILESTONE_PLANNED = "planned";
    public static final String MILESTONE_IN_PROGRESS = "in_progress";
    public static final String MILESTONE_COMPLETED = "completed";
    public static final String MILESTONE_CANCELLED = "cancelled";

    // ================= 项目内角色 =================
    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_VIEWER = "viewer";
    private static final Set<String> MEMBER_ROLES = Set.of(ROLE_OWNER, ROLE_MEMBER, ROLE_VIEWER);

    private static final Map<String, String> PROJECT_LABELS;
    private static final Map<String, Set<String>> PROJECT_TRANSITIONS;
    private static final Map<String, String> MILESTONE_LABELS;
    private static final Map<String, Set<String>> MILESTONE_TRANSITIONS;

    static {
        Map<String, String> projectLabels = new LinkedHashMap<>();
        projectLabels.put(PROJECT_DRAFT, "立项草稿");
        projectLabels.put(PROJECT_ACTIVE, "进行中");
        projectLabels.put(PROJECT_CLOSED, "已结项");
        projectLabels.put(PROJECT_ARCHIVED, "已归档");
        PROJECT_LABELS = Collections.unmodifiableMap(projectLabels);

        Map<String, Set<String>> projectTransitions = new LinkedHashMap<>();
        projectTransitions.put(PROJECT_DRAFT, setOf(PROJECT_ACTIVE));
        projectTransitions.put(PROJECT_ACTIVE, setOf(PROJECT_CLOSED));
        projectTransitions.put(PROJECT_CLOSED, setOf(PROJECT_ARCHIVED));
        projectTransitions.put(PROJECT_ARCHIVED, Set.of());
        PROJECT_TRANSITIONS = Collections.unmodifiableMap(projectTransitions);

        Map<String, String> milestoneLabels = new LinkedHashMap<>();
        milestoneLabels.put(MILESTONE_PLANNED, "计划中");
        milestoneLabels.put(MILESTONE_IN_PROGRESS, "进行中");
        milestoneLabels.put(MILESTONE_COMPLETED, "已完成");
        milestoneLabels.put(MILESTONE_CANCELLED, "已取消");
        MILESTONE_LABELS = Collections.unmodifiableMap(milestoneLabels);

        Map<String, Set<String>> milestoneTransitions = new LinkedHashMap<>();
        milestoneTransitions.put(MILESTONE_PLANNED, setOf(MILESTONE_IN_PROGRESS, MILESTONE_CANCELLED));
        milestoneTransitions.put(MILESTONE_IN_PROGRESS, setOf(MILESTONE_COMPLETED, MILESTONE_CANCELLED));
        milestoneTransitions.put(MILESTONE_COMPLETED, Set.of());
        milestoneTransitions.put(MILESTONE_CANCELLED, Set.of());
        MILESTONE_TRANSITIONS = Collections.unmodifiableMap(milestoneTransitions);
    }

    private ProjectStatusMachine() {
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    // ================= 项目 =================

    public static boolean isProjectStatus(String status) {
        return status != null && PROJECT_LABELS.containsKey(status);
    }

    public static String projectLabel(String status) {
        return status == null ? "" : PROJECT_LABELS.getOrDefault(status, status);
    }

    public static boolean isProjectEditable(String status) {
        return PROJECT_DRAFT.equals(status) || PROJECT_ACTIVE.equals(status);
    }

    public static boolean canTransitProject(String from, String to) {
        if (!isProjectStatus(from) || !isProjectStatus(to)) {
            return false;
        }
        return PROJECT_TRANSITIONS.get(from).contains(to);
    }

    public static List<String> projectNextStatuses(String from) {
        return isProjectStatus(from) ? new ArrayList<>(PROJECT_TRANSITIONS.get(from)) : List.of();
    }

    public static void requireProjectTransition(String from, String to) {
        if (!isProjectStatus(to)) {
            throw new IllegalArgumentException("项目状态不合法：「" + to + "」，合法状态为 "
                    + String.join("、", PROJECT_LABELS.keySet()));
        }
        if (!isProjectStatus(from)) {
            throw new IllegalArgumentException("项目当前状态不合法：「" + from + "」");
        }
        if (!canTransitProject(from, to)) {
            throw new IllegalArgumentException("项目状态不允许从「" + projectLabel(from) + "」变更为「"
                    + projectLabel(to) + "」，当前状态允许变更为：「"
                    + String.join("、", projectNextStatuses(from).stream().map(ProjectStatusMachine::projectLabel).toList())
                    + "」");
        }
    }

    // ================= 里程碑 =================

    public static boolean isMilestoneStatus(String status) {
        return status != null && MILESTONE_LABELS.containsKey(status);
    }

    public static String milestoneLabel(String status) {
        return status == null ? "" : MILESTONE_LABELS.getOrDefault(status, status);
    }

    public static boolean canTransitMilestone(String from, String to) {
        if (!isMilestoneStatus(from) || !isMilestoneStatus(to)) {
            return false;
        }
        return MILESTONE_TRANSITIONS.get(from).contains(to);
    }

    public static List<String> milestoneNextStatuses(String from) {
        return isMilestoneStatus(from) ? new ArrayList<>(MILESTONE_TRANSITIONS.get(from)) : List.of();
    }

    public static void requireMilestoneTransition(String from, String to) {
        if (!isMilestoneStatus(to)) {
            throw new IllegalArgumentException("里程碑状态不合法：「" + to + "」，合法状态为 "
                    + String.join("、", MILESTONE_LABELS.keySet()));
        }
        if (!isMilestoneStatus(from)) {
            throw new IllegalArgumentException("里程碑当前状态不合法：「" + from + "」");
        }
        if (!canTransitMilestone(from, to)) {
            throw new IllegalArgumentException("里程碑状态不允许从「" + milestoneLabel(from) + "」变更为「"
                    + milestoneLabel(to) + "」，当前状态允许变更为：「"
                    + String.join("、", milestoneNextStatuses(from).stream().map(ProjectStatusMachine::milestoneLabel).toList())
                    + "」");
        }
    }

    // ================= 角色 =================

    public static boolean isMemberRole(String role) {
        return role != null && MEMBER_ROLES.contains(role);
    }

    public static String memberRoleLabel(String role) {
        return switch (role == null ? "" : role) {
            case ROLE_OWNER -> "负责人";
            case ROLE_MEMBER -> "参与人";
            case ROLE_VIEWER -> "观察者";
            default -> role;
        };
    }

    // ================= 导出 =================

    public static Map<String, Object> definition() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("project", block(PROJECT_LABELS, PROJECT_TRANSITIONS));
        definition.put("milestone", block(MILESTONE_LABELS, MILESTONE_TRANSITIONS));
        definition.put("memberRoles", MEMBER_ROLES);
        definition.put("singleSourceOfTruth", ProjectStatusMachine.class.getName());
        return definition;
    }

    private static Map<String, Object> block(Map<String, String> labels, Map<String, Set<String>> transitions) {
        List<Map<String, Object>> states = new ArrayList<>();
        labels.forEach((code, label) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("label", label);
            state.put("terminal", transitions.get(code).isEmpty());
            states.add(state);
        });
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("states", states);
        block.put("transitions", transitions);
        return block;
    }
}
