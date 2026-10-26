package com.teamflow.recruit.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.recruit.domain.dto.RecruitApprovalDecisionRequest;
import com.teamflow.recruit.domain.dto.RecruitOfferRespondRequest;
import com.teamflow.recruit.domain.dto.RecruitOfferTemplateWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitOfferWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitOnboardRequest;
import com.teamflow.recruit.domain.dto.RecruitSubmitApprovalRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.service.RecruitOfferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M08 Offer 与入职接口（{@code /api/recruit/offer-templates}、{@code /api/recruit/offers}，需求 2.1.4）。
 *
 * <p>Offer 审批复用 M03 流程实例（类型 offer_approval）；入职调用 M00 用户创建能力写入 {@code sys_user}，
 * **不建用户表**且幂等（同一候选人重复入职返回同一用户，R7）。</p>
 */
@RestController
@RequestMapping("/api/recruit")
public class RecruitOfferController {

    private final RecruitOfferService service;

    public RecruitOfferController(RecruitOfferService service) {
        this.service = service;
    }

    // ---------------- Offer 模板 ----------------

    @GetMapping("/offer-templates")
    @RequiresPermission("recruit:offer:list")
    public R<List<Map<String, Object>>> templates(@RequestParam(required = false) String status) {
        return R.success(service.listTemplates(status));
    }

    @PostMapping("/offer-templates")
    @RequiresPermission("recruit:offer:add")
    @OperLog(module = "Offer 模板", action = "新增")
    public R<RecruitIdResponse> createTemplate(@RequestBody RecruitOfferTemplateWriteRequest request) {
        return R.success(service.createTemplate(request));
    }

    @PutMapping("/offer-templates/{id}")
    @RequiresPermission("recruit:offer:edit")
    @OperLog(module = "Offer 模板", action = "修改")
    public R<Void> updateTemplate(@PathVariable Long id, @RequestBody RecruitOfferTemplateWriteRequest request) {
        service.updateTemplate(id, request);
        return R.success(null);
    }

    // ---------------- Offer ----------------

    @GetMapping("/offers")
    @RequiresPermission("recruit:offer:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long candidateId,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), candidateId, jobId, status));
    }

    @GetMapping("/offers/{id}")
    @RequiresPermission("recruit:offer:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping("/offers")
    @RequiresPermission("recruit:offer:add")
    @OperLog(module = "Offer", action = "新增")
    public R<RecruitIdResponse> create(@RequestBody RecruitOfferWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/offers/{id}")
    @RequiresPermission("recruit:offer:edit")
    @OperLog(module = "Offer", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody RecruitOfferWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    /** 提交 Offer 审批（生成 M03 流程实例 offer_approval）。 */
    @PostMapping("/offers/{id}/submit-approval")
    @RequiresPermission("recruit:offer:edit")
    @OperLog(module = "Offer", action = "提交审批")
    public R<Map<String, Object>> submitApproval(@PathVariable Long id,
                                                 @RequestBody RecruitSubmitApprovalRequest request) {
        return R.success(service.submitApproval(id, request.getApproverId()));
    }

    @PutMapping("/offers/{id}/approval")
    @RequiresPermission("recruit:offer:approve")
    @OperLog(module = "Offer", action = "审批")
    public R<Map<String, Object>> decideApproval(@PathVariable Long id,
                                                 @RequestBody RecruitApprovalDecisionRequest request) {
        return R.success(service.decideApproval(id, Boolean.TRUE.equals(request.getApproved()), request.getOpinion()));
    }

    @PostMapping("/offers/{id}/send")
    @RequiresPermission("recruit:offer:edit")
    @OperLog(module = "Offer", action = "发放")
    public R<Map<String, Object>> send(@PathVariable Long id) {
        return R.success(service.send(id));
    }

    /** 登记候选人答复（接受 / 拒绝；拒绝需原因）。 */
    @PutMapping("/offers/{id}/respond")
    @RequiresPermission("recruit:offer:edit")
    @OperLog(module = "Offer", action = "登记答复")
    public R<Map<String, Object>> respond(@PathVariable Long id, @RequestBody RecruitOfferRespondRequest request) {
        return R.success(service.respond(id, request));
    }

    /** 入职转用户（幂等：同一候选人重复调用返回同一 sys_user，不建用户表）。 */
    @PostMapping("/offers/{id}/onboard")
    @RequiresPermission("recruit:offer:onboard")
    @OperLog(module = "Offer", action = "入职转用户")
    public R<Map<String, Object>> onboard(@PathVariable Long id,
                                          @RequestBody(required = false) RecruitOnboardRequest request) {
        return R.success(service.onboard(id, request));
    }
}
