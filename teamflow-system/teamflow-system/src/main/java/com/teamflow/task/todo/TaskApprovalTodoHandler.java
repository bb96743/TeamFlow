package com.teamflow.task.todo;

import com.teamflow.personal.todo.TodoAction;
import com.teamflow.personal.todo.TodoBizHandler;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.task.domain.dto.TaskApprovalDecisionRequest;
import com.teamflow.task.mapper.FlowTaskMapper;
import com.teamflow.task.service.TaskFlowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M03 的审批待办回写（M10 扩展点实现，接 M03 已有的 {@code flow_task}）。
 *
 * <p><b>数据约定</b>：待办的 {@code bizId} 是 {@code flow_task.id}（不是任务 ID）；handler 反查
 * {@code flow_task} → 流程实例 → 驱动 M03 的审批决策（通过），从而复用 M03 既有的
 * 状态流转、节点轨迹与领域事件，不产生第二套审批逻辑。</p>
 *
 * <p><b>R3 强约束</b>：忽略（{@code IGNORE}）、待办不存在、已驳回、审批人不是当前用户时返回 {@code false}，
 * M10 **不会关闭待办**。审批已通过的陈旧待办返回 {@code true}（业务已到位，幂等关闭）。</p>
 */
@Component
public class TaskApprovalTodoHandler implements TodoBizHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskApprovalTodoHandler.class);

    private final FlowTaskMapper flowTaskMapper;
    private final TaskFlowService flowService;

    public TaskApprovalTodoHandler(FlowTaskMapper flowTaskMapper, TaskFlowService flowService) {
        this.flowTaskMapper = flowTaskMapper;
        this.flowService = flowService;
    }

    @Override
    public String bizType() {
        return TodoBizTypes.TASK_APPROVAL;
    }

    @Override
    public boolean handle(Long bizId, Long userId, TodoAction action) {
        if (action != TodoAction.COMPLETE) {
            // 审批待办不允许"忽略"：忽略等于不做决定，违反 M10 RULE R3
            return false;
        }
        Map<String, Object> flowTask = flowTaskMapper.selectFlowTaskById(bizId);
        if (flowTask == null) {
            log.info("审批待办处理失败：flow_task 不存在或已删除 flowTaskId={}", bizId);
            return false;
        }
        Long approverId = numberAsLong(flowTask.get("assigneeId"));
        if (approverId != null && !approverId.equals(userId)) {
            log.info("审批待办处理失败：审批人不是当前用户 flowTaskId={} approverId={} userId={}",
                    bizId, approverId, userId);
            return false;
        }
        String status = String.valueOf(flowTask.get("status"));
        if ("pending".equals(status)) {
            Long instanceId = numberAsLong(flowTask.get("instanceId"));
            TaskApprovalDecisionRequest decision = new TaskApprovalDecisionRequest();
            decision.setApproved(true);
            decision.setOpinion("从待办收件箱审批通过");
            flowService.decideApproval(instanceId, decision);
            return true;
        }
        if ("approved".equals(status)) {
            log.info("审批待办处理：审批已通过，直接关闭陈旧待办 flowTaskId={}", bizId);
            return true;
        }
        // rejected 等状态：审批未通过，关闭待办会掩盖事实，返回 false 保持待办未处理
        log.info("审批待办处理失败：审批状态为 {} flowTaskId={}", status, bizId);
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
