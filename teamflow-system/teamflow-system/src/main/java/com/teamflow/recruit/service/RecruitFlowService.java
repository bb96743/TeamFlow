package com.teamflow.recruit.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.task.service.ApprovalFlowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M08 审批流程适配器：**复用 M03 流程引擎**（M08 RULE §二「不做流程引擎」）。
 *
 * <p><b>调用方式（D-2026-10-26-09 结论一）</b>：本类只委托 M03 的通用审批 Service
 * {@link ApprovalFlowService}，**不再直连流程引擎 Mapper**（无任何 task 模块 mapper 依赖），符合 L0 §4.3
 * 「跨模块只走 Service」。招聘侧只负责业务类型白名单（{@code job_approval} / {@code offer_approval}）
 * 与业务状态推进；流程实例/待办/轨迹的读写全部由 M03 负责。</p>
 *
 * <p><b>边界</b>：不建任何流程表、不建第二套引擎；审批状态与业务状态由 M08 自己维护在
 * {@code recruit_job.approval_*} / {@code recruit_offer.approval_*}，流程表只存引擎事实。
 * 对外接口与响应字段保持不变。</p>
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

    private final ApprovalFlowService approvalFlowService;

    public RecruitFlowService(
            RecruitPlatformMapper platformMapper,
            ApprovalFlowService approvalFlowService
    ) {
        super(platformMapper);
        this.approvalFlowService = approvalFlowService;
    }

    /**
     * 发起招聘单节点审批（委托 M03 通用审批 Service）。
     *
     * @param instanceType {@link #TYPE_JOB_APPROVAL} 或 {@link #TYPE_OFFER_APPROVAL}
     * @param bizId        业务单据 ID（职位 ID / Offer ID）
     * @param bizTitle     业务标题（写入流程实例便于审批人识别）
     * @param approverId   审批人用户 ID（须为启用用户）
     * @return 流程实例 ID
     */
    @Transactional
    public Long start(String instanceType, Long bizId, String bizTitle, Long approverId) {
        if (!ALLOWED_TYPES.contains(instanceType)) {
            throw new BusinessException("招聘审批类型不合法：" + instanceType);
        }
        return approvalFlowService.start(instanceType, DEF_CODE, bizId, bizTitle, approverId);
    }

    /**
     * 审批处理（委托 M03 通用审批 Service）：审批人校验、待办结论、节点轨迹、实例结束均由 M03 负责；
     * 本方法只额外保证「实例类型 = 招聘侧业务类型」，避免误操作其他业务的审批实例。
     *
     * @return {instanceId, approved, instanceStatus, operatorName, operatedTime}
     */
    @Transactional
    public Map<String, Object> decide(String instanceType, Long instanceId, boolean approved, String opinion) {
        if (!ALLOWED_TYPES.contains(instanceType)) {
            throw new BusinessException("招聘审批类型不合法：" + instanceType);
        }
        Map<String, Object> detail = approvalFlowService.instanceDetail(instanceId);
        String actualType = String.valueOf(detail.get("instanceType"));
        if (!instanceType.equals(actualType)) {
            throw new BusinessException("审批实例类型不匹配：期望 " + instanceType + "，实际 " + actualType);
        }
        Map<String, Object> decision = approvalFlowService.decide(instanceId, approved ? "approve" : "reject", opinion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("approved", approved);
        result.put("instanceStatus", decision.get("instanceStatus"));
        result.put("operatorName", decision.get("operatorName"));
        result.put("operatedTime", decision.get("operatedTime"));
        return result;
    }

    /**
     * 审批轨迹（委托 M03 通用审批 Service 的实例详情），响应结构保持 M08 既有字段不变。
     */
    public Map<String, Object> history(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        Map<String, Object> detail;
        try {
            detail = approvalFlowService.instanceDetail(instanceId);
        } catch (BusinessException exception) {
            return null;
        }
        if (detail == null) {
            return null;
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("instanceId", instanceId);
        history.put("instanceType", detail.get("instanceType"));
        history.put("instanceStatus", detail.get("status"));
        history.put("defCode", detail.get("defCode"));
        history.put("startBy", detail.get("startBy"));
        history.put("startTime", detail.get("startTime"));
        history.put("endTime", detail.get("endTime"));
        history.put("nodes", detail.get("nodes"));
        return history;
    }
}
