package com.teamflow.personal.todo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M10 待办的 {@code bizType} **集中登记表**（L0 §9.1 {@code D-2026-10-26-07} 配套要求①）。
 *
 * <p>投递方必须使用这里登记的取值；未登记的 {@code bizType} 在投递时即被拒绝（防止拼写错误造成
 * 永远无法处理的脏待办）。登记了但**没有 handler** 的类型（如 {@code meeting_invite}）：
 * 允许投递入库，处理时由 M10 拒绝并提示「该类型暂不支持在收件箱直接处理」。</p>
 */
public final class TodoBizTypes {

    /** M03 任务指派（责任人收到「开始执行」待办）。 */
    public static final String TASK_ASSIGN = "task_assign";
    /** M03 审批待办（接 M03 已有 flow_task；handler 驱动审批决策）。 */
    public static final String TASK_APPROVAL = "task_approval";
    /** M06 会议邀请（M06 未开工：**仅预留登记位**，本期无 handler）。 */
    public static final String MEETING_INVITE = "meeting_invite";
    /**
     * M09 评论/消息 @提及提醒（L0 §9.1 D-2026-10-26-11 结论四：通知只投递不代劳）。
     *
     * <p>与 {@link #MEETING_INVITE} 同类：已登记、**无 handler** —— 允许投递入库，
     * 在收件箱直接处理时由 M10 拒绝并提示「该类型暂不支持在收件箱直接处理」，
     * 用户点 actionUrl 回源到评论/消息位置。M10 不引入任何 M09 实现依赖。</p>
     */
    public static final String COLLAB_MENTION = "collab_mention";

    private static final Set<String> KNOWN = new LinkedHashSet<>(Set.of(
            TASK_ASSIGN, TASK_APPROVAL, MEETING_INVITE, COLLAB_MENTION));

    private TodoBizTypes() {
    }

    public static Set<String> known() {
        return Set.copyOf(KNOWN);
    }

    public static boolean isKnown(String bizType) {
        return bizType != null && KNOWN.contains(bizType);
    }

    /** 未登记 handler 的预留类型（仅入库、不可在收件箱处理）。 */
    public static Set<String> reservedWithoutHandler() {
        return Set.of(MEETING_INVITE);
    }
}
