package com.teamflow.task.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.domain.dto.TaskStatusRequest;
import com.teamflow.task.mapper.TaskMapper;
import com.teamflow.task.mapper.TaskPlatformMapper;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M03 任务状态流转服务（R1 / R2 / R3 的唯一落地点）。
 *
 * <p><b>R1 合法性</b>：唯一判定入口是 {@link TaskStatusMachine#requireTransition(String, String)}，
 * 本类与 Controller 都不写 {@code if (status == X)}；非法迁移统一返回带前后中文状态的明确错误。</p>
 * <p><b>R2 留痕</b>：每次流转写 {@code flow_instance} + {@code flow_node_instance}（操作人/前后状态/时间/意见）。</p>
 * <p><b>R3 事件</b>：在<b>同一业务事务内</b>发布 {@code TaskStatusChangedEvent}；M02 在事务提交后消费，
 * 事务回滚不会投递。本类不调用任何 M02 服务、不写 {@code okr_*} 表。</p>
 * <p><b>R4 责任人</b>：无责任人任务只能停留在待认领池，任何离开待认领池的流转都会被拒绝。</p>
 */
@Service
public class TaskStatusService extends TaskServiceSupport {

    private final TaskMapper taskMapper;
    private final TaskHierarchyService hierarchyService;
    private final TaskFlowTraceService traceService;
    private final TaskEventPublisher eventPublisher;
    private final TaskPlatformMapper platformMapper;

    public TaskStatusService(
            TaskMapper taskMapper,
            TaskHierarchyService hierarchyService,
            TaskFlowTraceService traceService,
            TaskEventPublisher eventPublisher,
            TaskPlatformMapper platformMapper
    ) {
        this.taskMapper = taskMapper;
        this.hierarchyService = hierarchyService;
        this.traceService = traceService;
        this.eventPublisher = eventPublisher;
        this.platformMapper = platformMapper;
    }

    /**
     * 显式状态流转（{@code PUT /api/task/tasks/{id}/status}）。
     */
    @Transactional
    public Map<String, Object> transition(Long taskId, TaskStatusRequest request) {
        Map<String, Object> task = requireTask(taskId);
        String toStatus = required(request.getToStatus(), "目标状态不能为空");
        String opinion = trimToNull(request.getOpinion());
        return applyTransition(task, toStatus, currentOperatorId(), currentUsername(), opinion);
    }

    /**
     * 审批通过后的状态流转（{@code PUT /api/task/flow/instances/{id}/approve} 内部调用）。
     *
     * <p>复用同一 {@link #applyTransition}，因此轨迹与领域事件都不会漏。</p>
     */
    @Transactional
    public Map<String, Object> transitionFromApproval(
            Long taskId, String toStatus, String opinion, Long operatorId, String operatorName
    ) {
        Map<String, Object> task = requireTask(taskId);
        return applyTransition(task, toStatus, operatorId, operatorName, opinion);
    }

    /**
     * 执行一次状态流转：校验 → 更新 → 汇总 → 留痕 → 发事件（同一事务）。
     */
    public Map<String, Object> applyTransition(
            Map<String, Object> task, String toStatus, Long operatorId, String operatorName, String opinion
    ) {
        Long taskId = numberAsLong(task.get("id"));
        String fromStatus = task.get("status") == null ? null : String.valueOf(task.get("status"));

        // ① 合法性：全模块唯一判定入口（R1）
        requireLegalTransition(fromStatus, toStatus);

        // ② 责任人规则（R4）：离开待认领池必须先有责任人
        Long assigneeId = numberAsLong(task.get("assigneeId"));
        if (TaskStatusMachine.requiresAssignee(toStatus) && assigneeId == null) {
            throw new BusinessException("无责任人任务只能存在于待认领池（M03 RULE R4），"
                    + "请先认领或指派任务后再流转到「" + TaskStatusMachine.label(toStatus) + "」");
        }

        // ③ 更新任务状态（完成时间由状态机语义维护）
        LocalDateTime doneTime = TaskStatusMachine.DONE.equals(toStatus) ? LocalDateTime.now() : null;
        taskMapper.updateTaskStatus(taskId, toStatus, doneTime, operatorName);

        // ④ 子任务完成度汇总到父任务（DoD）
        hierarchyService.adjustParentAggregation(taskId, fromStatus, toStatus, operatorName);

        // ⑤ R2 留痕：flow_instance + flow_node_instance
        traceService.recordStatusTransition(task, fromStatus, toStatus, operatorId, operatorName, opinion);

        // ⑥ R3 发事件：必须与业务更新在同一事务内（订阅端 AFTER_COMMIT 消费）
        Long krId = numberAsLong(task.get("krId"));
        eventPublisher.publishStatusChanged(
                taskId, krId, hierarchyService.rootTaskId(taskId), hierarchyService.isRoot(taskId),
                fromStatus, toStatus, operatorId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", taskId);
        result.put("fromStatus", fromStatus);
        result.put("toStatus", toStatus);
        result.put("toStatusLabel", TaskStatusMachine.label(toStatus));
        result.put("operatorName", operatorName);
        result.put("opinion", opinion);
        result.put("krId", krId);
        result.put("isRoot", hierarchyService.isRoot(taskId));
        result.put("rootTaskId", hierarchyService.rootTaskId(taskId));
        return result;
    }

    /** 校验并转成业务异常（控制器/服务层不需要感知 IllegalArgumentException）。 */
    public void requireLegalTransition(String fromStatus, String toStatus) {
        try {
            TaskStatusMachine.requireTransition(fromStatus, toStatus);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ex.getMessage());
        }
    }

    /** 内部取任务（不受数据范围限制；写操作前的存在性校验）。 */
    public Map<String, Object> requireTask(Long taskId) {
        if (taskId == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        Map<String, Object> task = taskMapper.selectTaskById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在或已删除");
        }
        return task;
    }

    private Long currentOperatorId() {
        return platformMapper.selectUserIdByUsername(currentUsername());
    }
}
