package com.teamflow.task.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.task.mapper.FlowDefinitionMapper;
import com.teamflow.task.mapper.FlowInstanceMapper;
import com.teamflow.task.mapper.FlowTaskMapper;
import com.teamflow.task.mapper.TaskPlatformMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * M03 **通用审批服务**（L0 §9.1 {@code D-2026-10-26-09} 结论一）：承载「非任务业务」的单节点审批，
 * 与 {@code task_*} 表、任务状态机**完全解耦**。
 *
 * <p><b>为什么需要它</b>：主数据/招聘等模块的「需求审批 / Offer 审批」只需「发起 → 单人审批 → 结束」
 * 的流程实例，不应各自直连流程引擎 Mapper（违反 L0 §4.3「跨模块只走 Service」），也不应绑到任务状态机。
 * 本服务是 M03 流程引擎对**其它业务模块**的唯一审批出口；M08 的 {@code RecruitFlowService} 已改为委托本服务。</p>
 *
 * <p><b>边界</b>：不写任何业务表（业务状态由调用方在 {@link #decide} 返回后自行推进）；
 * 不新建流程表、不改变 {@code flow_*} 结构与既有任务审批语义（{@link TaskFlowService} 保持不变）。</p>
 *
 * <p><b>数据范围</b>：{@link #instanceDetail}/{@link #findByBiz}/{@link #myPending} **不叠加登录用户数据范围**——
 * 审批人必须能看到自己待办对应的业务标题；业务对象本身的访问权由调用方保证（实例创建后只有调用方与审批人可见其业务单据）。</p>
 */
@Service
public class ApprovalFlowService extends TaskServiceSupport {

    /** 实例类型命名规范：小写字母开头，1–16 位小写字母/数字/下划线（对应 {@code flow_instance.instance_type} 长度）。 */
    private static final Pattern INSTANCE_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");
    private static final String NODE_KEY = "approve";
    private static final String NODE_NAME = "审批";

    private final FlowInstanceMapper instanceMapper;
    private final FlowTaskMapper flowTaskMapper;
    private final TaskPlatformMapper platformMapper;
    private final TaskFlowTraceService traceService;

    public ApprovalFlowService(
            FlowInstanceMapper instanceMapper,
            FlowTaskMapper flowTaskMapper,
            TaskPlatformMapper platformMapper,
            TaskFlowTraceService traceService
    ) {
        this.instanceMapper = instanceMapper;
        this.flowTaskMapper = flowTaskMapper;
        this.platformMapper = platformMapper;
        this.traceService = traceService;
    }

    /**
     * 发起单节点通用审批：写一条 {@code flow_instance}（running）+ 一条 {@code flow_task}（pending），
     * 此时**不改任何业务状态**（业务状态由调用方在 {@link #decide} 后推进）。
     *
     * @param instanceType 实例类型（按命名规范校验，1–16 位小写字母/数字/下划线；如 {@code job_approval}）
     * @param defCode      流程定义编码（须存在且启用；单节点审批用种子 {@code task_approve_single}）
     * @param bizId        业务单据 ID（引擎只存引用，不解释业务）
     * @param bizTitle     业务标题（写入实例/待办，便于审批人识别）
     * @param approverId   审批人用户 ID（须为启用用户）
     * @return 流程实例 ID
     */
    @OperLog(module = "通用审批", action = "发起审批")
    @Transactional
    public Long start(String instanceType, String defCode, Long bizId, String bizTitle, Long approverId) {
        String type = requireInstanceType(instanceType);
        String code = required(defCode, "流程定义编码不能为空");
        if (bizId == null) {
            throw new BusinessException("审批业务 ID 不能为空");
        }
        String title = required(bizTitle, "审批业务标题不能为空");
        Map<String, Object> definition = traceService.requireDefinition(code);
        Integer definitionStatus = numberAsInteger(definition.get("status"));
        if (definitionStatus == null || definitionStatus != 1) {
            throw new BusinessException("流程定义已停用，不能发起审批：" + code);
        }
        if (approverId == null || platformMapper.countActiveUser(approverId) == 0) {
            throw new BusinessException("审批人不存在或已停用");
        }
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("definitionId", numberAsLong(definition.get("id")));
        instance.put("defCode", code);
        instance.put("instanceType", type);
        instance.put("bizId", bizId);
        instance.put("bizTitle", title);
        instance.put("targetStatus", "approved");
        instance.put("status", "running");
        instance.put("currentNode", NODE_KEY);
        instance.put("startBy", operatorId);
        instance.put("startTime", now);
        instance.put("remark", "通用业务审批（M03 ApprovalFlowService）：" + title);
        instanceMapper.insertInstance(instance, username);
        Long instanceId = numberAsLong(instance.get("id"));

        Map<String, Object> flowTask = new LinkedHashMap<>();
        flowTask.put("instanceId", instanceId);
        flowTask.put("bizId", bizId);
        flowTask.put("nodeKey", NODE_KEY);
        flowTask.put("taskTitle", "审批：" + title);
        flowTask.put("assigneeId", approverId);
        flowTask.put("status", "pending");
        flowTask.put("remark", null);
        flowTaskMapper.insertFlowTask(flowTask, username);
        return instanceId;
    }

    /**
     * 审批处理（通过 / 驳回）：校验实例状态与「当前登录用户 = 待办审批人」，写待办结论与节点轨迹并结束实例。
     *
     * @param instanceId 流程实例 ID
     * @param decision   审批结果字符串（{@code approve/approved/pass/通过} 视为通过；{@code reject/rejected/驳回} 视为驳回）
     * @param opinion    审批意见（可空，落待办与轨迹）
     * @return 审批结果（instanceId / approved / bizId / bizTitle / instanceType / instanceStatus / operatorName / operatedTime）
     */
    @OperLog(module = "通用审批", action = "审批")
    @Transactional
    public Map<String, Object> decide(Long instanceId, String decision, String opinion) {
        boolean approved = parseDecision(decision);
        if (instanceId == null) {
            throw new BusinessException("审批实例 ID 不能为空");
        }
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }
        if (!"running".equals(String.valueOf(instance.get("status")))) {
            throw new BusinessException("审批已处理完成，不能重复审批");
        }
        Map<String, Object> pending = flowTaskMapper.selectPendingByInstance(instanceId);
        if (pending == null) {
            throw new BusinessException("没有待处理的审批任务");
        }
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        Long approverId = numberAsLong(pending.get("assigneeId"));
        if (approverId != null && operatorId != null && !approverId.equals(operatorId)) {
            throw new BusinessException("无权审批该业务单据：审批人为用户 " + approverId);
        }
        String normalizedOpinion = trimToNull(opinion);
        LocalDateTime now = LocalDateTime.now();

        flowTaskMapper.updateDecision(numberAsLong(pending.get("id")),
                approved ? "approved" : "rejected", normalizedOpinion, now, username);
        traceService.recordNode(instanceId, NODE_KEY, NODE_NAME, "pending",
                approved ? "approved" : "rejected", approved ? "completed" : "rejected",
                operatorId, username,
                normalizedOpinion == null ? (approved ? "审批通过" : "审批驳回") : normalizedOpinion);
        String instanceStatus = approved ? "completed" : "terminated";
        instanceMapper.updateInstance(instanceId, instanceStatus, NODE_KEY, now, username);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("approved", approved);
        result.put("bizId", numberAsLong(instance.get("bizId")));
        result.put("bizTitle", instance.get("bizTitle"));
        result.put("instanceType", instance.get("instanceType"));
        result.put("instanceStatus", instanceStatus);
        result.put("operatorName", username);
        result.put("operatedTime", now.toString());
        return result;
    }

    /**
     * 实例详情：实例 + 节点轨迹 + 全部待办（含 pending）。**不叠加数据范围**（见类注释）。
     *
     * @param instanceId 流程实例 ID
     * @return 实例详情（含 {@code nodes} 与 {@code flowTasks}）
     */
    public Map<String, Object> instanceDetail(Long instanceId) {
        if (instanceId == null) {
            throw new BusinessException("审批实例 ID 不能为空");
        }
        return traceService.instanceDetail(instanceId);
    }

    /**
     * 某审批人的待办清单（只返回流程引擎事实，不返回业务明细）。**不叠加数据范围**。
     *
     * @param approverId 审批人用户 ID；为空时取当前登录用户
     */
    public List<Map<String, Object>> myPending(Long approverId) {
        Long target = approverId;
        if (target == null) {
            target = platformMapper.selectUserIdByUsername(currentUsername());
        }
        if (target == null) {
            throw new BusinessException("审批人不能为空");
        }
        return flowTaskMapper.selectPendingByAssignee(target);
    }

    /**
     * 按「实例类型 + 业务 ID」查流程实例（同一业务可能有多条历史实例，按 ID 倒序）。**不叠加数据范围**。
     */
    public List<Map<String, Object>> findByBiz(String instanceType, Long bizId) {
        String type = requireInstanceType(instanceType);
        if (bizId == null) {
            throw new BusinessException("审批业务 ID 不能为空");
        }
        return instanceMapper.selectInstances(type, null, bizId, DataScopeCriteria.all(), 0, 50);
    }

    // ------------------------------------------------------------------

    private String requireInstanceType(String instanceType) {
        String type = trimToNull(instanceType);
        if (type == null || !INSTANCE_TYPE_PATTERN.matcher(type).matches()) {
            throw new BusinessException("审批实例类型不合法：「" + instanceType
                    + "」，须为 1–16 位小写字母/数字/下划线（如 job_approval）");
        }
        return type;
    }

    private boolean parseDecision(String decision) {
        String text = trimToNull(decision);
        if (text == null) {
            throw new BusinessException("审批结果不能为空：approve 通过 / reject 驳回");
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "approve", "approved", "pass", "通过" -> true;
            case "reject", "rejected", "驳回", "不通过" -> false;
            default -> throw new BusinessException("审批结果不合法：「" + decision + "」，只能为 approve 或 reject");
        };
    }
}
