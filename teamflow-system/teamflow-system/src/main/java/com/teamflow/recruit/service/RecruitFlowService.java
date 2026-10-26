package com.teamflow.recruit.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.task.mapper.FlowDefinitionMapper;
import com.teamflow.task.mapper.FlowInstanceMapper;
import com.teamflow.task.mapper.FlowNodeInstanceMapper;
import com.teamflow.task.mapper.FlowTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M08 审批流程适配器：**复用 M03 的 {@code flow_*} 流程引擎**（M08 RULE §二「不做流程引擎」）。
 *
 * <p><b>为什么直接用 M03 的 Mapper Bean</b>：M03 现有 {@code TaskFlowService} 只支持「任务」审批
 * （{@code startApproval(taskId,...)} 强绑 M03 任务状态机），未提供通用的"非任务业务审批" Service。
 * 本模块本轮**不得修改其他模块业务代码**，因此按「复用同一套流程表、不新建流程引擎」的最小侵入方式，
 * 直接注入 M03 的 {@code FlowDefinitionMapper / FlowInstanceMapper / FlowTaskMapper / FlowNodeInstanceMapper}，
 * 用**同一套流程定义/实例/待办/轨迹表**承载招聘审批（实例类型 {@code job_approval} / {@code offer_approval}）。</p>
 *
 * <p><b>边界</b>：不建任何流程表、不建第二套引擎；审批状态与业务状态由 M08 自己维护在
 * {@code recruit_job.approval_*} / {@code recruit_offer.approval_*}，流程表只存引擎事实。
 * 建议 M03 后续提供通用审批 Service（已登记在 M08 RULE §九 与 CR 已知问题），届时本类改为经 M03 Service 调用。</p>
 */
@Service
public class RecruitFlowService extends RecruitServiceSupport {

    /** 复用 M03 种子的单节点审批流程定义（V7 种入：def_code=task_approve_single）。 */
    public static final String DEF_CODE = "task_approve_single";
    /** 职位需求审批实例类型（flow_instance.instance_type，≤16 字符）。 */
    public static final String TYPE_JOB_APPROVAL = "job_approval";
    /** Offer 审批实例类型（flow_instance.instance_type，≤16 字符）。 */
    public static final String TYPE_OFFER_APPROVAL = "offer_approval";
    private static final Set<String> ALLOWED_TYPES = Set.of(TYPE_JOB_APPROVAL, TYPE_OFFER_APPROVAL);

    private final FlowDefinitionMapper definitionMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowTaskMapper flowTaskMapper;
    private final FlowNodeInstanceMapper nodeMapper;

    public RecruitFlowService(
            RecruitPlatformMapper platformMapper,
            FlowDefinitionMapper definitionMapper,
            FlowInstanceMapper instanceMapper,
            FlowTaskMapper flowTaskMapper,
            FlowNodeInstanceMapper nodeMapper
    ) {
        super(platformMapper);
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.flowTaskMapper = flowTaskMapper;
        this.nodeMapper = nodeMapper;
    }

    /**
     * 发起单节点审批（复用 M03 流程实例 + 待办）。
     *
     * @param instanceType {@link #TYPE_JOB_APPROVAL} 或 {@link #TYPE_OFFER_APPROVAL}
     * @param bizId        业务单据 ID（职位 ID / Offer ID）
     * @param bizTitle     业务标题（写入流程实例便于排障）
     * @param approverId   审批人用户 ID（须为启用用户）
     * @return 流程实例 ID
     */
    @Transactional
    public Long start(String instanceType, Long bizId, String bizTitle, Long approverId) {
        if (!ALLOWED_TYPES.contains(instanceType)) {
            throw new BusinessException("招聘审批类型不合法：" + instanceType);
        }
        if (bizId == null) {
            throw new BusinessException("审批业务 ID 不能为空");
        }
        if (approverId == null || platformMapper.countActiveUser(approverId) == 0) {
            throw new BusinessException("审批人不存在或已停用");
        }
        Map<String, Object> definition = definitionMapper.selectDefinitionByCode(DEF_CODE);
        if (definition == null) {
            throw new BusinessException("审批流程定义缺失（M03 flow_definition: " + DEF_CODE + "），无法发起招聘审批");
        }
        String username = currentUsername();
        Long operatorId = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("definitionId", numberAsLong(definition.get("id")));
        instance.put("defCode", DEF_CODE);
        instance.put("instanceType", instanceType);
        instance.put("bizId", bizId);
        instance.put("bizTitle", bizTitle);
        instance.put("targetStatus", "approved");
        instance.put("status", "running");
        instance.put("currentNode", "approve");
        instance.put("startBy", operatorId);
        instance.put("startTime", now);
        instance.put("remark", "招聘审批（复用 M03 流程实例）：" + bizTitle);
        instanceMapper.insertInstance(instance, username);
        Long instanceId = numberAsLong(instance.get("id"));

        Map<String, Object> flowTask = new LinkedHashMap<>();
        flowTask.put("instanceId", instanceId);
        flowTask.put("bizId", bizId);
        flowTask.put("nodeKey", "approve");
        flowTask.put("taskTitle", "招聘审批：" + bizTitle);
        flowTask.put("assigneeId", approverId);
        flowTask.put("status", "pending");
        flowTask.put("remark", null);
        flowTaskMapper.insertFlowTask(flowTask, username);
        return instanceId;
    }

    /**
     * 审批处理：校验实例类型/状态/审批人，落待办决定与节点轨迹，并结束实例。
     *
     * @return {instanceId, approved, operatorName}
     */
    @Transactional
    public Map<String, Object> decide(String instanceType, Long instanceId, boolean approved, String opinion) {
        if (!ALLOWED_TYPES.contains(instanceType)) {
            throw new BusinessException("招聘审批类型不合法：" + instanceType);
        }
        if (instanceId == null) {
            throw new BusinessException("审批实例 ID 不能为空");
        }
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }
        if (!instanceType.equals(String.valueOf(instance.get("instanceType")))) {
            throw new BusinessException("审批实例类型不匹配：期望 " + instanceType + "，实际 " + instance.get("instanceType"));
        }
        if (!"running".equals(String.valueOf(instance.get("status")))) {
            throw new BusinessException("审批已处理完成，不能重复审批");
        }
        Map<String, Object> pending = flowTaskMapper.selectPendingByInstance(instanceId);
        if (pending == null) {
            throw new BusinessException("没有待处理的审批任务");
        }
        String username = currentUsername();
        Long operatorId = currentUserId();
        Long approverId = numberAsLong(pending.get("assigneeId"));
        if (approverId != null && operatorId != null && !approverId.equals(operatorId)) {
            throw new BusinessException("无权审批该招聘单据：审批人为用户 " + approverId);
        }
        LocalDateTime now = LocalDateTime.now();
        flowTaskMapper.updateDecision(numberAsLong(pending.get("id")), approved ? "approved" : "rejected",
                trimToNull(opinion), now, username);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("instanceId", instanceId);
        node.put("nodeKey", "approve");
        node.put("nodeName", "招聘审批");
        node.put("fromStatus", "pending");
        node.put("toStatus", approved ? "approved" : "rejected");
        node.put("nodeStatus", approved ? "completed" : "rejected");
        node.put("operatorId", operatorId);
        node.put("operatorName", username);
        node.put("opinion", trimToNull(opinion) == null ? (approved ? "审批通过" : "审批驳回") : opinion);
        node.put("operatedTime", now);
        node.put("remark", null);
        nodeMapper.insertNode(node, username);
        instanceMapper.updateInstance(instanceId, approved ? "completed" : "terminated", "approve", now, username);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("approved", approved);
        result.put("instanceStatus", approved ? "completed" : "terminated");
        result.put("operatorName", username);
        result.put("operatedTime", now.toString());
        return result;
    }

    /** 审批轨迹（复用 M03 流程实例 + 节点轨迹表，只读）。 */
    public Map<String, Object> history(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        Map<String, Object> instance = instanceMapper.selectInstanceById(instanceId);
        if (instance == null) {
            return null;
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("instanceId", instanceId);
        history.put("instanceType", instance.get("instanceType"));
        history.put("instanceStatus", instance.get("status"));
        history.put("defCode", instance.get("defCode"));
        history.put("startBy", instance.get("startBy"));
        history.put("startTime", instance.get("startTime"));
        history.put("endTime", instance.get("endTime"));
        List<Map<String, Object>> nodes = nodeMapper.selectNodesByInstance(instanceId);
        history.put("nodes", nodes);
        return history;
    }
}
