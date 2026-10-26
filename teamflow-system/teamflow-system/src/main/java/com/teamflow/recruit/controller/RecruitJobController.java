package com.teamflow.recruit.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.recruit.domain.dto.RecruitApprovalDecisionRequest;
import com.teamflow.recruit.domain.dto.RecruitJobWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitStatusRequest;
import com.teamflow.recruit.domain.dto.RecruitSubmitApprovalRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.service.RecruitJobService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M08 职位接口（{@code /api/recruit/jobs}，需求 2.1.1）。
 *
 * <p>需求审批复用 M03 流程实例（不另建引擎）：提交审批 → 生成 {@code flow_instance}（类型 job_approval）
 * → 审批通过后职位进入招聘中。</p>
 */
@RestController
@RequestMapping("/api/recruit/jobs")
public class RecruitJobController {

    private final RecruitJobService service;

    public RecruitJobController(RecruitJobService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("recruit:job:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long deptId
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, status, deptId));
    }

    @GetMapping("/{id}")
    @RequiresPermission("recruit:job:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("recruit:job:add")
    @OperLog(module = "招聘职位", action = "新增")
    public R<RecruitIdResponse> create(@RequestBody RecruitJobWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("recruit:job:edit")
    @OperLog(module = "招聘职位", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody RecruitJobWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("recruit:job:remove")
    @OperLog(module = "招聘职位", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    /** 职位状态流转（招聘中 → 暂停 / 关闭；审批不走这里）。 */
    @PutMapping("/{id}/status")
    @RequiresPermission("recruit:job:edit")
    @OperLog(module = "招聘职位", action = "状态流转")
    public R<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody RecruitStatusRequest request) {
        return R.success(service.updateStatus(id, request.getStatus()));
    }

    /** 提交需求审批（生成 M03 流程实例 job_approval）。 */
    @PostMapping("/{id}/submit-approval")
    @RequiresPermission("recruit:job:edit")
    @OperLog(module = "招聘职位", action = "提交需求审批")
    public R<Map<String, Object>> submitApproval(@PathVariable Long id,
                                                 @RequestBody RecruitSubmitApprovalRequest request) {
        return R.success(service.submitApproval(id, request.getApproverId()));
    }

    /** 需求审批处理（只能由流程待办里的审批人操作）。 */
    @PutMapping("/{id}/approval")
    @RequiresPermission("recruit:job:approve")
    @OperLog(module = "招聘职位", action = "需求审批")
    public R<Map<String, Object>> decideApproval(@PathVariable Long id,
                                                 @RequestBody RecruitApprovalDecisionRequest request) {
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        return R.success(service.decideApproval(id, approved, request.getOpinion()));
    }
}
