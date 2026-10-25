package com.teamflow.task.todo;

import com.teamflow.personal.todo.TodoAction;
import com.teamflow.personal.todo.TodoBizHandler;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.task.domain.dto.TaskStatusRequest;
import com.teamflow.task.mapper.TaskMapper;
import com.teamflow.task.service.TaskStatusService;
import com.teamflow.task.status.TaskStatusMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M03 的任务指派待办回写（M10 扩展点实现，L0 §9.1 {@code D-2026-10-26-07}）。
 *
 * <p><b>语义</b>：收件箱里的 {@code task_assign} 待办表示「任务已指派给你，请开始执行」。
 * 在收件箱点「完成」= 把任务从「待办」推进到「进行中」；任务必须存在、责任人必须是当前用户。</p>
 *
 * <p><b>R3 强约束</b>：忽略（{@code IGNORE}）或任务已被删除/责任人不是本人时返回 {@code false}，
 * M10 因此**不会关闭待办**——杜绝"只关待办不改业务"。</p>
 */
@Component
public class TaskAssignTodoHandler implements TodoBizHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskAssignTodoHandler.class);

    private final TaskMapper taskMapper;
    private final TaskStatusService statusService;

    public TaskAssignTodoHandler(TaskMapper taskMapper, TaskStatusService statusService) {
        this.taskMapper = taskMapper;
        this.statusService = statusService;
    }

    @Override
    public String bizType() {
        return TodoBizTypes.TASK_ASSIGN;
    }

    @Override
    public boolean handle(Long bizId, Long userId, TodoAction action) {
        if (action != TodoAction.COMPLETE) {
            // 指派待办不允许"忽略"：忽略等于不改变任务状态，违反 M10 RULE R3
            return false;
        }
        Map<String, Object> task = taskMapper.selectTaskById(bizId);
        if (task == null) {
            log.info("任务指派待办处理失败：任务不存在或已删除 taskId={}", bizId);
            return false;
        }
        Long assigneeId = numberAsLong(task.get("assigneeId"));
        if (assigneeId == null || !assigneeId.equals(userId)) {
            log.info("任务指派待办处理失败：责任人不是当前用户 taskId={} assigneeId={} userId={}",
                    bizId, assigneeId, userId);
            return false;
        }
        String status = String.valueOf(task.get("status"));
        if (TaskStatusMachine.TODO.equals(status)) {
            TaskStatusRequest request = new TaskStatusRequest();
            request.setToStatus(TaskStatusMachine.IN_PROGRESS);
            request.setOpinion("从待办收件箱开始执行");
            statusService.transition(bizId, request);
            return true;
        }
        if (TaskStatusMachine.IN_PROGRESS.equals(status)
                || TaskStatusMachine.DONE.equals(status)
                || TaskStatusMachine.CLOSED.equals(status)) {
            // 已经越过「开始执行」：业务状态已到位，允许关闭陈旧待办（幂等，不重复改状态）
            log.info("任务指派待办处理：任务已是后续状态，直接关闭待办 taskId={} status={}", bizId, status);
            return true;
        }
        // 待认领池等状态无法从收件箱开始（责任人可能已被收回）
        log.info("任务指派待办处理失败：当前状态不允许开始执行 taskId={} status={}", bizId, status);
        return false;
    }

    private Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
