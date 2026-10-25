package com.teamflow.task.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.mapper.FlowDefinitionMapper;
import com.teamflow.task.mapper.FlowInstanceMapper;
import com.teamflow.task.mapper.FlowNodeInstanceMapper;
import com.teamflow.task.mapper.FlowTaskMapper;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M03 流程轨迹写入服务（R2）：任务创建 / 指派 / 每次状态流转都写
 * {@code flow_instance} + {@code flow_node_instance}，记录操作人、前后状态、时间、意见。
 *
 * <p>自研轻量引擎（D-2026-10-25-01）：一个任务只有<b>一条</b>持续进行的状态流转实例
 * （{@code instance_type='status'}，定义 {@code task_status_linear}），流转节点按时间追加；
 * 任务关闭或删除时实例置 {@code completed}/{@code terminated}，数据保留（R6 不物理删除）。</p>
 */
@Service
public class TaskFlowTraceService {

    /** 任务状态流转默认流程定义编码（V7 已种子化）。 */
    public static final String STATUS_DEF_CODE = "task_status_linear";
    /** 单节点审批默认流程定义编码（V7 已种子化）。 */
    public static final String APPROVE_DEF_CODE = "task_approve_single";

    private final FlowDefinitionMapper definitionMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowNodeInstanceMapper nodeMapper;
    private final FlowTaskMapper flowTaskMapper;

    public TaskFlowTraceService(
            FlowDefinitionMapper definitionMapper,
            FlowInstanceMapper instanceMapper,
            FlowNodeInstanceMapper nodeMapper,
            FlowTaskMapper flowTaskMapper
    ) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.flowTaskMapper = flowTaskMapper;
    }

    public Map<String, Object> requireDefinition(String defCode) {
        Map<String, Object> definition = definitionMapper.selectDefinitionByCode(defCode);
        if (definition == null) {
            throw new BusinessException("流程定义不存在：" + defCode + "（V7 迁移脚本应已种子化）");
        }
        return definition;
    }

    /**
     * 任务创建时的初始轨迹：建立状态流转实例 + 记录创建节点（fromStatus=null）。
     */
    public void recordCreation(
            Long taskId, String taskTitle, String initialStatus, Long operatorId, String operatorName, String opinion
    ) {
        Map<String, Object> instance = ensureStatusInstance(taskId, taskTitle, initialStatus, operatorId, operatorName);
        Long instanceId = numberAsLong(instance.get("id"));
        recordNode(instanceId, "create", "创建", null, initialStatus, "completed", operatorId, operatorName, opinion);
    }

    /**
     * 记录一次状态流转（R2 核心）：更新实例当前节点，必要时完结实例，并追加节点实例。
     *
     * @param task       任务行（读取 id / taskTitle）
     * @param fromStatus 变更前状态（创建场景可为 null）
     * @param toStatus   变更后状态
     */
    public void recordStatusTransition(
            Map<String, Object> task, String fromStatus, String toStatus,
            Long operatorId, String operatorName, String opinion
    ) {
        Long taskId = numberAsLong(task.get("id"));
        String taskTitle = task.get("taskTitle") == null ? null : String.valueOf(task.get("taskTitle"));
        Map<String, Object> instance = ensureStatusInstance(taskId, taskTitle, toStatus, operatorId, operatorName);
        Long instanceId = numberAsLong(instance.get("id"));

        recordNode(instanceId, toStatus, TaskStatusMachine.label(toStatus), fromStatus, toStatus,
                "completed", operatorId, operatorName, opinion);

        if (TaskStatusMachine.isTerminal(toStatus)) {
            instanceMapper.updateInstance(instanceId, "completed", toStatus, LocalDateTime.now(), operatorName);
        } else {
            instanceMapper.updateInstance(instanceId, "running", toStatus, null, operatorName);
        }
    }

    /** 记录非状态变更类动作（指派/转派）：前后状态相同，仍留痕便于追溯"谁在何时指派给谁"。 */
    public void recordAssignment(
            Map<String, Object> task, String status, Long operatorId, String operatorName, String opinion
    ) {
        Long taskId = numberAsLong(task.get("id"));
        String taskTitle = task.get("taskTitle") == null ? null : String.valueOf(task.get("taskTitle"));
        Map<String, Object> instance = ensureStatusInstance(taskId, taskTitle, status, operatorId, operatorName);
        Long instanceId = numberAsLong(instance.get("id"));
        recordNode(instanceId, "assign", "指派", status, status, "completed", operatorId, operatorName, opinion);
    }

    /** 任务逻辑删除时终止其进行中的状态实例（轨迹保留，不物理删除）。 */
    public void terminateStatusInstance(Long taskId, String operatorName) {
        Map<String, Object> instance = instanceMapper.selectRunningStatusInstance(taskId);
        if (instance == null) {
            return;
        }
        Long instanceId = numberAsLong(instance.get("id"));
        recordNode(instanceId, "delete", "删除", null, null, "completed", null, operatorName, "任务逻辑删除，流程实例终止");
        instanceMapper.updateInstance(instanceId, "terminated", "delete", LocalDateTime.now(), operatorName);
    }

    /** 追加一条节点实例。 */
    public void recordNode(
            Long instanceId, String nodeKey, String nodeName, String fromStatus, String toStatus,
            String nodeStatus, Long operatorId, String operatorName, String opinion
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("instanceId", instanceId);
        row.put("nodeKey", nodeKey);
        row.put("nodeName", nodeName);
        row.put("fromStatus", fromStatus);
        row.put("toStatus", toStatus);
        row.put("nodeStatus", nodeStatus);
        row.put("operatorId", operatorId);
        row.put("operatorName", operatorName);
        row.put("opinion", opinion);
        row.put("operatedTime", LocalDateTime.now());
        row.put("remark", null);
        nodeMapper.insertNode(row, operatorName == null ? "system" : operatorName);
    }

    /** 实例详情：实例 + 节点轨迹 + 审批待办（供审批中心/任务详情展示）。 */
    public Map<String, Object> instanceDetail(Long instanceId) {
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("流程实例不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(instance);
        detail.put("nodes", nodeMapper.selectNodesByInstance(instanceId));
        detail.put("flowTasks", flowTaskMapper.selectTasksByInstance(instanceId));
        return detail;
    }

    /** 任务的全部流程轨迹（状态实例 + 审批实例的节点按时间顺序合并）。 */
    public List<Map<String, Object>> taskTraces(Long taskId) {
        return nodeMapper.selectNodesByTaskId(taskId);
    }

    /**
     * 取得任务的状态流转实例；不存在则按默认定义创建（任务创建时一定会建，此处为容错兜底）。
     */
    private Map<String, Object> ensureStatusInstance(
            Long taskId, String taskTitle, String currentNode, Long startBy, String operator
    ) {
        Map<String, Object> existing = instanceMapper.selectRunningStatusInstance(taskId);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> definition = requireDefinition(STATUS_DEF_CODE);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("definitionId", numberAsLong(definition.get("id")));
        row.put("defCode", STATUS_DEF_CODE);
        row.put("instanceType", "status");
        row.put("bizId", taskId);
        row.put("bizTitle", taskTitle);
        row.put("targetStatus", null);
        row.put("status", "running");
        row.put("currentNode", currentNode);
        row.put("startBy", startBy);
        row.put("startTime", LocalDateTime.now());
        row.put("remark", "任务状态流转实例（自研轻量状态机，D-2026-10-25-01）");
        instanceMapper.insertInstance(row, operator == null ? "system" : operator);
        return instanceMapper.selectRunningStatusInstance(taskId);
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
