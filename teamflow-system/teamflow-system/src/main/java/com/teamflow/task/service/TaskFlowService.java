package com.teamflow.task.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.task.domain.dto.FlowDefinitionWriteRequest;
import com.teamflow.task.domain.dto.TaskApprovalDecisionRequest;
import com.teamflow.task.domain.dto.TaskApprovalRequest;
import com.teamflow.task.domain.vo.TaskIdResponse;
import com.teamflow.task.mapper.FlowDefinitionMapper;
import com.teamflow.task.mapper.FlowInstanceMapper;
import com.teamflow.task.mapper.FlowTaskMapper;
import com.teamflow.task.mapper.TaskMapper;
import com.teamflow.task.mapper.TaskPlatformMapper;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M03 流程服务：流程定义维护、流程实例查询、**线性单节点审批**。
 *
 * <p>自研轻量引擎（D-2026-10-25-01）：只做「发起审批 → 单人审批 → 通过后执行状态流转」，
 * 会签 / 并行网关 / 加签属 P2 的 3.1 审批中心，不在本期范围。审批通过后复用
 * {@link TaskStatusService}，因此轨迹（flow_node_instance）与领域事件（TaskStatusChangedEvent）不会漏。</p>
 */
@Service
public class TaskFlowService extends TaskServiceSupport {

    private static final Set<String> BUSINESS_TYPES = Set.of("task_status", "task_approve");

    private final FlowDefinitionMapper definitionMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowTaskMapper flowTaskMapper;
    private final TaskMapper taskMapper;
    private final TaskPlatformMapper platformMapper;
    private final TaskFlowTraceService traceService;
    private final TaskStatusService statusService;
    private final TaskTodoPushService todoPushService;

    public TaskFlowService(
            FlowDefinitionMapper definitionMapper,
            FlowInstanceMapper instanceMapper,
            FlowTaskMapper flowTaskMapper,
            TaskMapper taskMapper,
            TaskPlatformMapper platformMapper,
            TaskFlowTraceService traceService,
            TaskStatusService statusService,
            TaskTodoPushService todoPushService
    ) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.flowTaskMapper = flowTaskMapper;
        this.taskMapper = taskMapper;
        this.platformMapper = platformMapper;
        this.traceService = traceService;
        this.statusService = statusService;
        this.todoPushService = todoPushService;
    }

    // ------------------------------------------------------------------
    // 流程定义
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> listDefinitions(PageQuery query, String keyword, String businessType) {
        Page page = pageOf(query);
        String type = trimToNull(businessType);
        long total = definitionMapper.countDefinitions(trimToNull(keyword), type);
        List<Map<String, Object>> rows = definitionMapper.selectDefinitions(
                trimToNull(keyword), type, page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @Transactional
    public TaskIdResponse createDefinition(FlowDefinitionWriteRequest request) {
        String code = required(request.getDefCode(), "流程定义编码不能为空");
        String name = required(request.getDefName(), "流程定义名称不能为空");
        String businessType = defaultString(request.getBusinessType(), "task_status");
        if (!BUSINESS_TYPES.contains(businessType)) {
            throw new BusinessException("流程定义业务类型只能为 task_status 或 task_approve");
        }
        if (definitionMapper.countByCode(code, null) > 0) {
            throw new BusinessException("流程定义编码已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("defCode", code);
        row.put("defName", name);
        row.put("businessType", businessType);
        row.put("nodesJson", trimToNull(request.getNodesJson()));
        row.put("status", request.getStatus() == null ? 1 : request.getStatus());
        row.put("remark", trimToNull(request.getRemark()));
        definitionMapper.insertDefinition(row, currentUsername());
        return new TaskIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateDefinition(Long id, FlowDefinitionWriteRequest request) {
        Map<String, Object> existing = definitionMapper.selectDefinitionById(id);
        if (existing == null) {
            throw new BusinessException("流程定义不存在");
        }
        String code = request.getDefCode() == null
                ? String.valueOf(existing.get("defCode")) : required(request.getDefCode(), "流程定义编码不能为空");
        if (definitionMapper.countByCode(code, id) > 0) {
            throw new BusinessException("流程定义编码已存在");
        }
        String businessType = request.getBusinessType() == null
                ? String.valueOf(existing.get("businessType")) : request.getBusinessType();
        if (!BUSINESS_TYPES.contains(businessType)) {
            throw new BusinessException("流程定义业务类型只能为 task_status 或 task_approve");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("defCode", code);
        row.put("defName", request.getDefName() == null
                ? String.valueOf(existing.get("defName")) : required(request.getDefName(), "流程定义名称不能为空"));
        row.put("businessType", businessType);
        row.put("nodesJson", request.getNodesJson() == null ? existing.get("nodesJson") : trimToNull(request.getNodesJson()));
        row.put("status", request.getStatus() == null ? numberAsLong(existing.get("status")).intValue() : request.getStatus());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        definitionMapper.updateDefinition(id, row, currentUsername());
    }

    // ------------------------------------------------------------------
    // 流程实例
    // ------------------------------------------------------------------

    @DataScope
    public PageResult<Map<String, Object>> listInstances(PageQuery query, String instanceType, String status, Long bizId) {
        Page page = pageOf(query);
        String type = trimToNull(instanceType);
        String state = trimToNull(status);
        long total = instanceMapper.countInstances(type, state, bizId, DataScopeContext.current());
        List<Map<String, Object>> rows = instanceMapper.selectInstances(
                type, state, bizId, DataScopeContext.current(), page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> instanceDetail(Long instanceId) {
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("流程实例不存在");
        }
        Long bizId = numberAsLong(instance.get("bizId"));
        if (taskMapper.selectTask(bizId, DataScopeContext.current()) == null) {
            throw new BusinessException("流程实例不存在或无权访问");
        }
        Map<String, Object> detail = traceService.instanceDetail(instanceId);
        detail.put("targetStatusLabel", TaskStatusMachine.label(
                detail.get("targetStatus") == null ? null : String.valueOf(detail.get("targetStatus"))));
        return detail;
    }

    // ------------------------------------------------------------------
    // 线性单节点审批
    // ------------------------------------------------------------------

    /**
     * 发起单节点审批：登记审批实例与待办（{@code flow_task}），此时<b>不改任务状态</b>。
     */
    @Transactional
    public TaskIdResponse startApproval(Long taskId, TaskApprovalRequest request) {
        Map<String, Object> task = statusService.requireTask(taskId);
        String currentStatus = String.valueOf(task.get("status"));
        String targetStatus = required(request.getTargetStatus(), "审批目标状态不能为空");
        statusService.requireLegalTransition(currentStatus, targetStatus);

        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        Long approverId = request.getApproverId() == null
                ? numberAsLong(task.get("creatorId")) : request.getApproverId();
        if (approverId == null || platformMapper.countActiveUser(approverId) == 0) {
            throw new BusinessException("审批人不存在或已停用");
        }
        Map<String, Object> definition = traceService.requireDefinition(TaskFlowTraceService.APPROVE_DEF_CODE);
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("definitionId", numberAsLong(definition.get("id")));
        instance.put("defCode", TaskFlowTraceService.APPROVE_DEF_CODE);
        instance.put("instanceType", "approval");
        instance.put("bizId", taskId);
        instance.put("bizTitle", String.valueOf(task.get("taskTitle")));
        instance.put("targetStatus", targetStatus);
        instance.put("status", "running");
        instance.put("currentNode", "approve");
        instance.put("startBy", operatorId);
        instance.put("startTime", LocalDateTime.now());
        instance.put("remark", "任务单节点审批（" + TaskStatusMachine.label(currentStatus)
                + " → " + TaskStatusMachine.label(targetStatus) + "）");
        instanceMapper.insertInstance(instance, username);
        Long instanceId = numberAsLong(instance.get("id"));

        Map<String, Object> flowTask = new LinkedHashMap<>();
        flowTask.put("instanceId", instanceId);
        flowTask.put("bizId", taskId);
        flowTask.put("nodeKey", "approve");
        flowTask.put("taskTitle", "任务审批：" + task.get("taskTitle"));
        flowTask.put("assigneeId", approverId);
        flowTask.put("status", "pending");
        flowTask.put("remark", trimToNull(request.getOpinion()));
        flowTaskMapper.insertFlowTask(flowTask, username);
        // R1（M10）：审批节点投递收件箱待办（bizId = flow_task.id，处理时由 M03 handler 驱动审批决策）
        todoPushService.pushApprovalTodo(
                numberAsLong(flowTask.get("id")), approverId, String.valueOf(task.get("taskTitle")), instanceId);
        return new TaskIdResponse(instanceId);
    }

    /**
     * 审批处理：通过 → 复用状态流转（留痕 + 发事件）；驳回 → 只结束实例，任务状态不变。
     */
    @Transactional
    public Map<String, Object> decideApproval(Long instanceId, TaskApprovalDecisionRequest request) {
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("流程实例不存在");
        }
        if (!"approval".equals(String.valueOf(instance.get("instanceType")))) {
            throw new BusinessException("该流程实例不是审批实例");
        }
        if (!"running".equals(String.valueOf(instance.get("status")))) {
            throw new BusinessException("审批已处理完成，不能重复审批");
        }
        if (request.getApproved() == null) {
            throw new BusinessException("请指定审批结果：approved=true 通过 / false 驳回");
        }
        Map<String, Object> pending = flowTaskMapper.selectPendingByInstance(instanceId);
        if (pending == null) {
            throw new BusinessException("没有待处理的审批任务");
        }
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        Long approverId = numberAsLong(pending.get("assigneeId"));
        if (approverId != null && operatorId != null && !approverId.equals(operatorId)) {
            throw new BusinessException("无权审批该任务：审批人为用户 " + approverId);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        String opinion = trimToNull(request.getOpinion());
        String currentStatus = String.valueOf(
                statusService.requireTask(numberAsLong(instance.get("bizId"))).get("status"));
        String targetStatus = String.valueOf(instance.get("targetStatus"));

        flowTaskMapper.updateDecision(numberAsLong(pending.get("id")),
                approved ? "approved" : "rejected", opinion, now, username);
        traceService.recordNode(instanceId, "approve", "审批",
                currentStatus, approved ? targetStatus : currentStatus,
                approved ? "completed" : "rejected", operatorId, username,
                opinion == null ? (approved ? "审批通过" : "审批驳回") : opinion);
        instanceMapper.updateInstance(instanceId, approved ? "completed" : "terminated", "approve", now, username);

        if (approved) {
            statusService.transitionFromApproval(
                    numberAsLong(instance.get("bizId")), targetStatus, opinion, operatorId, username);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("approved", approved);
        result.put("bizId", numberAsLong(instance.get("bizId")));
        result.put("targetStatus", approved ? targetStatus : null);
        result.put("instanceStatus", approved ? "completed" : "terminated");
        return result;
    }
}
