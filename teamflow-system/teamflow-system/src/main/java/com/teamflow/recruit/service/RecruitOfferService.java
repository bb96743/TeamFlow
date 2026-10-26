package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.recruit.domain.dto.RecruitOfferRespondRequest;
import com.teamflow.recruit.domain.dto.RecruitOfferTemplateWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitOfferWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitOnboardRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.mapper.RecruitCandidateMapper;
import com.teamflow.recruit.mapper.RecruitJobMapper;
import com.teamflow.recruit.mapper.RecruitOfferMapper;
import com.teamflow.recruit.mapper.RecruitOfferTemplateMapper;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.recruit.stage.RecruitStatusMachine;
import com.teamflow.system.domain.dto.UserWriteRequest;
import com.teamflow.system.service.SystemManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M08 Offer 与入职服务（2.1.4）：Offer 模板、审批链（复用 M03 流程实例）、答复登记、入职转用户。
 *
 * <p><b>不建用户表</b>：入职时调用 M00 {@link SystemManagementService#createUser(UserWriteRequest)} 写入
 * {@code sys_user}，本模块只在 {@code recruit_candidate.hired_user_id} 存引用；<b>R7 幂等</b>：
 * 同一候选人已有 {@code hired_user_id} 时直接返回该用户，不重复创建。</p>
 * <p><b>不自建流程引擎</b>：Offer 审批经 {@link RecruitFlowService} 复用 M03 {@code flow_*}（类型 offer_approval）。</p>
 */
@Service
public class RecruitOfferService extends RecruitServiceSupport {

    private final RecruitOfferMapper offerMapper;
    private final RecruitOfferTemplateMapper templateMapper;
    private final RecruitCandidateMapper candidateMapper;
    private final RecruitJobMapper jobMapper;
    private final RecruitFlowService flowService;
    private final SystemManagementService systemManagementService;

    public RecruitOfferService(
            RecruitPlatformMapper platformMapper,
            RecruitOfferMapper offerMapper,
            RecruitOfferTemplateMapper templateMapper,
            RecruitCandidateMapper candidateMapper,
            RecruitJobMapper jobMapper,
            RecruitFlowService flowService,
            SystemManagementService systemManagementService
    ) {
        super(platformMapper);
        this.offerMapper = offerMapper;
        this.templateMapper = templateMapper;
        this.candidateMapper = candidateMapper;
        this.jobMapper = jobMapper;
        this.flowService = flowService;
        this.systemManagementService = systemManagementService;
    }

    // ------------------------------------------------------------------
    // Offer 模板
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listTemplates(String status) {
        return templateMapper.selectTemplates(trimToNull(status));
    }

    @Transactional
    public RecruitIdResponse createTemplate(RecruitOfferTemplateWriteRequest request) {
        String code = required(request.getTemplateCode(), "模板编码不能为空");
        if (templateMapper.countTemplateCode(code, null) > 0) {
            throw new BusinessException("模板编码已存在：" + code);
        }
        String status = defaultString(request.getStatus(), "enabled");
        if (!List.of("enabled", "disabled").contains(status)) {
            throw new BusinessException("模板状态只能为 enabled / disabled");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateCode", code);
        row.put("templateName", required(request.getTemplateName(), "模板名称不能为空"));
        row.put("content", required(request.getContent(), "模板正文不能为空"));
        row.put("status", status);
        row.put("remark", trimToNull(request.getRemark()));
        templateMapper.insertTemplate(row, currentUsername());
        return new RecruitIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateTemplate(Long id, RecruitOfferTemplateWriteRequest request) {
        Map<String, Object> existing = requireTemplate(id);
        String status = request.getStatus() == null ? String.valueOf(existing.get("status")) : request.getStatus();
        if (!List.of("enabled", "disabled").contains(status)) {
            throw new BusinessException("模板状态只能为 enabled / disabled");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateName", request.getTemplateName() == null
                ? existing.get("templateName") : required(request.getTemplateName(), "模板名称不能为空"));
        row.put("content", request.getContent() == null
                ? existing.get("content") : required(request.getContent(), "模板正文不能为空"));
        row.put("status", status);
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        templateMapper.updateTemplate(id, row, currentUsername());
    }

    // ------------------------------------------------------------------
    // Offer
    // ------------------------------------------------------------------

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, Long candidateId, Long jobId, String status) {
        Page page = pageOf(query);
        long total = offerMapper.countOffers(candidateId, jobId, trimToNull(status), DataScopeContext.current());
        List<Map<String, Object>> rows = offerMapper.selectOffers(candidateId, jobId, trimToNull(status),
                DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = offerMapper.selectOffer(id, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("Offer 不存在或无权访问");
        }
        decorate(row);
        Long templateId = numberAsLong(row.get("templateId"));
        row.put("template", templateId == null ? null : templateMapper.selectTemplateById(templateId));
        Long instanceId = numberAsLong(row.get("approvalInstanceId"));
        row.put("approvalHistory", instanceId == null ? null : flowService.history(instanceId));
        return row;
    }

    /**
     * 创建 Offer：候选人须处于「面试」阶段；同一候选人只允许一份 Offer；
     * 创建后候选人阶段自动推进到「Offer」（R5 状态机判定）。
     */
    @Transactional
    public RecruitIdResponse create(RecruitOfferWriteRequest request) {
        Map<String, Object> candidate = requireCandidate(request.getCandidateId());
        String stage = String.valueOf(candidate.get("currentStage"));
        if (!RecruitStatusMachine.CANDIDATE_INTERVIEW.equals(stage)) {
            throw new BusinessException("候选人当前阶段为「" + RecruitStatusMachine.candidateStageLabel(stage)
                    + "」，只有「面试」阶段可以创建 Offer");
        }
        if (offerMapper.selectByCandidate(numberAsLong(candidate.get("id"))) != null) {
            throw new BusinessException("该候选人已有 Offer（同一候选人仅一份），请直接编辑或走审批");
        }
        if (request.getTemplateId() != null) {
            requireTemplate(request.getTemplateId());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidateId", numberAsLong(candidate.get("id")));
        row.put("jobId", numberAsLong(candidate.get("jobId")));
        row.put("templateId", request.getTemplateId());
        row.put("salary", request.getSalary() == null ? null : scale2(request.getSalary()));
        row.put("expectedOnboardDate", request.getExpectedOnboardDate() == null
                ? null : parseDate(request.getExpectedOnboardDate(), "期望入职日期").toString());
        row.put("status", RecruitStatusMachine.OFFER_DRAFT);
        row.put("approvalState", RecruitStatusMachine.APPROVAL_NONE);
        row.put("remark", trimToNull(request.getRemark()));
        offerMapper.insertOffer(row, currentUsername());

        requireTransition(() -> RecruitStatusMachine.requireCandidateTransition(stage,
                RecruitStatusMachine.CANDIDATE_OFFER));
        candidateMapper.updateStage(numberAsLong(candidate.get("id")), RecruitStatusMachine.CANDIDATE_OFFER,
                LocalDateTime.now(), null, currentUsername());
        return new RecruitIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, RecruitOfferWriteRequest request) {
        Map<String, Object> existing = requireOffer(id);
        String status = String.valueOf(existing.get("status"));
        if (!RecruitStatusMachine.OFFER_DRAFT.equals(status)) {
            throw new BusinessException("只有草稿状态的 Offer 可以修改（当前："
                    + RecruitStatusMachine.offerStatusLabel(status) + "）");
        }
        if (request.getTemplateId() != null) {
            requireTemplate(request.getTemplateId());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateId", request.getTemplateId() == null
                ? existing.get("templateId") : request.getTemplateId());
        row.put("salary", request.getSalary() == null
                ? existing.get("salary") : scale2(request.getSalary()));
        row.put("expectedOnboardDate", request.getExpectedOnboardDate() == null
                ? existing.get("expectedOnboardDate") : parseDate(request.getExpectedOnboardDate(), "期望入职日期").toString());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        offerMapper.updateOffer(id, row, currentUsername());
    }

    /** 提交 Offer 审批：draft/rejected → pending_approval，并生成 M03 流程实例（类型 offer_approval）。 */
    @Transactional
    public Map<String, Object> submitApproval(Long id, Long approverId) {
        Map<String, Object> existing = requireOffer(id);
        String initialStatus = String.valueOf(existing.get("status"));
        if (RecruitStatusMachine.OFFER_REJECTED.equals(initialStatus)) {
            // 驳回后允许修订再提交：先回到草稿（状态机内声明 rejected → draft）
            requireTransition(() -> RecruitStatusMachine.requireOfferTransition(initialStatus, RecruitStatusMachine.OFFER_DRAFT));
            offerMapper.updateOfferStatus(id, RecruitStatusMachine.OFFER_DRAFT,
                    RecruitStatusMachine.APPROVAL_REJECTED, numberAsLong(existing.get("approvalInstanceId")),
                    null, null, null, currentUsername());
        } else if (!RecruitStatusMachine.OFFER_DRAFT.equals(initialStatus)) {
            throw new BusinessException("只有草稿（或驳回后修订）的 Offer 可以提交审批（当前："
                    + RecruitStatusMachine.offerStatusLabel(initialStatus) + "）");
        }
        requireTransition(() -> RecruitStatusMachine.requireOfferTransition(RecruitStatusMachine.OFFER_DRAFT,
                RecruitStatusMachine.OFFER_PENDING_APPROVAL));
        Long candidateId = numberAsLong(existing.get("candidateId"));
        Map<String, Object> candidate = candidateMapper.selectCandidateById(candidateId);
        String bizTitle = "Offer 审批：" + (candidate == null ? candidateId : candidate.get("nameSnapshot"));
        Long instanceId = flowService.start(RecruitFlowService.TYPE_OFFER_APPROVAL, id, bizTitle, approverId);
        offerMapper.updateOfferStatus(id, RecruitStatusMachine.OFFER_PENDING_APPROVAL,
                RecruitStatusMachine.APPROVAL_PENDING, instanceId, null, null, null, currentUsername());
        return viewOf(id);
    }

    /** Offer 审批处理：通过 → approved；驳回 → rejected（可修订后重新提交）。 */
    @Transactional
    public Map<String, Object> decideApproval(Long id, boolean approved, String opinion) {
        Map<String, Object> existing = requireOffer(id);
        String status = String.valueOf(existing.get("status"));
        if (!RecruitStatusMachine.OFFER_PENDING_APPROVAL.equals(status)) {
            throw new BusinessException("Offer 不在审批中，不能处理审批（当前："
                    + RecruitStatusMachine.offerStatusLabel(status) + "）");
        }
        Long instanceId = numberAsLong(existing.get("approvalInstanceId"));
        if (instanceId == null) {
            throw new BusinessException("Offer 缺少审批流程实例，无法处理");
        }
        flowService.decide(RecruitFlowService.TYPE_OFFER_APPROVAL, instanceId, approved, opinion);
        if (approved) {
            offerMapper.updateOfferStatus(id, RecruitStatusMachine.OFFER_APPROVED,
                    RecruitStatusMachine.APPROVAL_APPROVED, instanceId, null, null, null, currentUsername());
        } else {
            offerMapper.updateOfferStatus(id, RecruitStatusMachine.OFFER_REJECTED,
                    RecruitStatusMachine.APPROVAL_REJECTED, instanceId, null, null, null, currentUsername());
        }
        return viewOf(id);
    }

    /** Offer 发放：approved → sent（记录发送时间）。 */
    @Transactional
    public Map<String, Object> send(Long id) {
        Map<String, Object> existing = requireOffer(id);
        String status = String.valueOf(existing.get("status"));
        requireTransition(() -> RecruitStatusMachine.requireOfferTransition(status, RecruitStatusMachine.OFFER_SENT));
        offerMapper.updateOfferStatus(id, RecruitStatusMachine.OFFER_SENT,
                String.valueOf(existing.get("approvalState")), numberAsLong(existing.get("approvalInstanceId")),
                LocalDateTime.now(), null, null, currentUsername());
        return viewOf(id);
    }

    /** 登记候选人答复：sent → accepted / declined（拒绝需填原因）。 */
    @Transactional
    public Map<String, Object> respond(Long id, RecruitOfferRespondRequest request) {
        Map<String, Object> existing = requireOffer(id);
        String status = String.valueOf(existing.get("status"));
        if (request == null || request.getAccepted() == null) {
            throw new BusinessException("请指定候选人答复：accepted=true 接受 / false 拒绝");
        }
        boolean accepted = Boolean.TRUE.equals(request.getAccepted());
        String target = accepted ? RecruitStatusMachine.OFFER_ACCEPTED : RecruitStatusMachine.OFFER_DECLINED;
        requireTransition(() -> RecruitStatusMachine.requireOfferTransition(status, target));
        String reason = trimToNull(request.getReason());
        if (!accepted && reason == null) {
            throw new BusinessException("候选人拒绝 Offer 必须填写原因（留痕用于渠道/漏斗分析）");
        }
        offerMapper.updateOfferStatus(id, target, String.valueOf(existing.get("approvalState")),
                numberAsLong(existing.get("approvalInstanceId")), null, LocalDateTime.now(), reason, currentUsername());
        return viewOf(id);
    }

    /**
     * 入职转用户（R7 幂等）：Offer 须为「已接受」；首次调用创建 {@code sys_user} 并写
     * {@code recruit_candidate.hired_user_id}，重复调用返回同一用户（不产生第二个用户）。
     */
    @Transactional
    public Map<String, Object> onboard(Long id, RecruitOnboardRequest request) {
        Map<String, Object> existing = requireOffer(id);
        Long candidateId = numberAsLong(existing.get("candidateId"));
        Map<String, Object> candidate = requireCandidate(candidateId);
        Long hiredUserId = numberAsLong(candidate.get("hiredUserId"));
        if (hiredUserId != null) {
            Map<String, Object> user = platformMapper.selectUserBrief(hiredUserId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("offerId", id);
            result.put("candidateId", candidateId);
            result.put("userId", hiredUserId);
            result.put("username", user == null ? null : user.get("username"));
            result.put("duplicated", true);
            result.put("message", "该候选人已入职，返回既有用户（R7 幂等）");
            return result;
        }
        String status = String.valueOf(existing.get("status"));
        if (!RecruitStatusMachine.OFFER_ACCEPTED.equals(status)) {
            throw new BusinessException("只有候选人已接受（accepted）的 Offer 才能办理入职（当前："
                    + RecruitStatusMachine.offerStatusLabel(status) + "）");
        }
        Long jobId = numberAsLong(existing.get("jobId"));
        Map<String, Object> job = jobMapper.selectJobById(jobId);
        Long deptId = request == null || request.getDeptId() == null
                ? (job == null ? null : numberAsLong(job.get("deptId")))
                : request.getDeptId();
        if (deptId != null && platformMapper.countDept(deptId) == 0) {
            throw new BusinessException("入职部门不存在");
        }
        String username = request == null || trimToNull(request.getUsername()) == null
                ? "cand" + candidateId : request.getUsername().trim();
        UserWriteRequest userRequest = new UserWriteRequest();
        userRequest.setUsername(username);
        userRequest.setNickname(String.valueOf(candidate.get("nameSnapshot")));
        userRequest.setPhone(candidate.get("phone") == null ? null : String.valueOf(candidate.get("phone")));
        userRequest.setEmail(candidate.get("email") == null ? null : String.valueOf(candidate.get("email")));
        userRequest.setDeptId(deptId);
        userRequest.setStatus(1);
        userRequest.setRoleIds(request == null ? null : request.getRoleIds());
        userRequest.setPostIds(request == null || request.getPostId() == null
                ? null : List.of(request.getPostId()));

        Long userId = numberAsLong(systemManagementService.createUser(userRequest).id());
        LocalDateTime now = LocalDateTime.now();
        candidateMapper.markHired(candidateId, userId, now, currentUsername());
        offerMapper.markOnboarded(id, currentUsername());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offerId", id);
        result.put("candidateId", candidateId);
        result.put("userId", userId);
        result.put("username", username);
        result.put("duplicated", false);
        result.put("message", "入职成功：已创建用户并关联候选人（不建用户表）");
        return result;
    }

    /** 写操作返回视图：内部直接读（避免 @DataScope 自调用丢失数据范围上下文）。 */
    private Map<String, Object> viewOf(Long id) {
        Map<String, Object> row = requireOffer(id);
        decorate(row);
        Long templateId = numberAsLong(row.get("templateId"));
        row.put("template", templateId == null ? null : templateMapper.selectTemplateById(templateId));
        Long instanceId = numberAsLong(row.get("approvalInstanceId"));
        row.put("approvalHistory", instanceId == null ? null : flowService.history(instanceId));
        return row;
    }
    public Map<String, Object> requireOffer(Long id) {
        if (id == null) {
            throw new BusinessException("Offer ID 不能为空");
        }
        Map<String, Object> row = offerMapper.selectOfferById(id);
        if (row == null) {
            throw new BusinessException("Offer 不存在或已删除");
        }
        return row;
    }

    private Map<String, Object> requireTemplate(Long id) {
        Map<String, Object> row = templateMapper.selectTemplateById(id);
        if (row == null) {
            throw new BusinessException("Offer 模板不存在");
        }
        return row;
    }

    private Map<String, Object> requireCandidate(Long candidateId) {
        if (candidateId == null) {
            throw new BusinessException("候选人 ID 不能为空");
        }
        Map<String, Object> candidate = candidateMapper.selectCandidateById(candidateId);
        if (candidate == null) {
            throw new BusinessException("候选人不存在或已删除");
        }
        return candidate;
    }

    private void decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        row.put("statusLabel", RecruitStatusMachine.offerStatusLabel(status));
        row.put("nextStatuses", RecruitStatusMachine.offerNextStatuses(status));
        row.put("approvalStateLabel", switch (String.valueOf(row.get("approvalState"))) {
            case "none" -> "未提交";
            case "pending" -> "审批中";
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            default -> String.valueOf(row.get("approvalState"));
        });
    }
}
