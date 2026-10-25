package com.teamflow.task.service;

import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.personal.todo.TodoPushCommand;
import com.teamflow.personal.service.TodoPushService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * M03 对 M10 待办收件箱的**投递适配**（L0 §9.1 {@code D-2026-10-26-07}、M10 RULE R1）。
 *
 * <p><b>边界</b>：本类只调用 M10 的统一投递服务 {@link TodoPushService}（同一 Spring 进程内的服务级入口，
 * 与 {@code POST /api/personal/todos/internal/push} 共用同一实现），<b>绝不直写 {@code todo_item}</b>；
 * M03 也不读 M10 的表。收件箱处理时由 M10 反过来调用 M03 注册的 {@code TodoBizHandler}，
 * 两个方向都只依赖契约/接口，不形成实现环。</p>
 *
 * <p>本期投递两类：
 * <ul>
 *   <li>{@code task_assign}：任务被指派/转派后，给新责任人一条「开始执行」待办（actionType=start）；</li>
 *   <li>{@code task_approval}：审批节点创建后，给审批人一条审批待办（bizId 用 {@code flow_task.id}，
 *       actionType=approve），收件箱处理时由 M03 handler 驱动审批决策。</li>
 * </ul>
 */
@Service
public class TaskTodoPushService {

    private final TodoPushService todoPushService;

    public TaskTodoPushService(TodoPushService todoPushService) {
        this.todoPushService = todoPushService;
    }

    /**
     * 投递任务指派待办。
     *
     * @param taskId     任务 ID
     * @param assigneeId 新责任人（待办归属）
     * @param taskTitle  任务标题（展示用）
     * @param dueDate    任务截止日期（可空，按当天 23:59:59 转为待办截止时间）
     * @param priority   任务优先级（与待办优先级取值一致）
     */
    public void pushAssignTodo(Long taskId, Long assigneeId, String taskTitle, LocalDate dueDate, String priority) {
        if (assigneeId == null) {
            return;
        }
        String title = taskTitle == null || taskTitle.isBlank() ? "任务 #" + taskId : taskTitle;
        todoPushService.push(new TodoPushCommand(
                TodoBizTypes.TASK_ASSIGN,
                taskId,
                assigneeId,
                "start",
                "任务指派：" + title,
                "/task/list?taskId=" + taskId,
                dueDate == null ? null : dueDate.atTime(23, 59, 59),
                priority,
                null));
    }

    /**
     * 投递审批待办（接 M03 已有的 {@code flow_task}）。
     *
     * @param flowTaskId 审批待办 ID（{@code flow_task.id}，M10 处理时原样回传给 handler）
     * @param approverId 审批人（待办归属）
     * @param taskTitle  任务标题
     * @param instanceId 审批流程实例 ID（回源链接用）
     */
    public void pushApprovalTodo(Long flowTaskId, Long approverId, String taskTitle, Long instanceId) {
        if (flowTaskId == null || approverId == null) {
            return;
        }
        String title = taskTitle == null || taskTitle.isBlank() ? "任务 #?" : taskTitle;
        todoPushService.push(new TodoPushCommand(
                TodoBizTypes.TASK_APPROVAL,
                flowTaskId,
                approverId,
                "approve",
                "任务审批：" + title,
                "/task/approvals?instanceId=" + instanceId,
                null,
                "high",
                null));
    }

    /** 兼容 Map 形式取值的内部辅助（避免调用方重复装箱逻辑）。 */
    static LocalDate dueDateOf(Map<String, Object> task) {
        Object value = task == null ? null : task.get("dueDate");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() >= 10 ? LocalDate.parse(text.substring(0, 10)) : null;
    }
}
