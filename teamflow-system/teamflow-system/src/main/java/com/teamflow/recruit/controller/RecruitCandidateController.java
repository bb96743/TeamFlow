package com.teamflow.recruit.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.recruit.domain.dto.RecruitCandidateStageRequest;
import com.teamflow.recruit.domain.dto.RecruitCandidateWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitResumeAttachRequest;
import com.teamflow.recruit.domain.vo.RecruitCandidateCreateResponse;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.service.RecruitCandidateService;
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
 * M08 候选人接口（{@code /api/recruit/candidates}，需求 2.1.2）。
 *
 * <p>列表默认脱敏联系方式（R6）；简历只接收 {@code file_id}（文件须先经 G-1 {@code /api/system/files} 上传）。</p>
 */
@RestController
@RequestMapping("/api/recruit/candidates")
public class RecruitCandidateController {

    private final RecruitCandidateService service;

    public RecruitCandidateController(RecruitCandidateService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("recruit:candidate:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String channel
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, jobId, stage, channel));
    }

    @GetMapping("/{id}")
    @RequiresPermission("recruit:candidate:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    /** 候选人入库（R1 查重：命中返回已有记录，duplicated=true）。 */
    @PostMapping
    @RequiresPermission("recruit:candidate:add")
    @OperLog(module = "候选人", action = "入库")
    public R<RecruitCandidateCreateResponse> create(@RequestBody RecruitCandidateWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("recruit:candidate:edit")
    @OperLog(module = "候选人", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody RecruitCandidateWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    /** 阶段推进（R5 状态机；淘汰必须写原因；入职只能走 Offer 入职接口）。 */
    @PutMapping("/{id}/stage")
    @RequiresPermission("recruit:candidate:edit")
    @OperLog(module = "候选人", action = "阶段推进")
    public R<Map<String, Object>> changeStage(@PathVariable Long id,
                                              @RequestBody RecruitCandidateStageRequest request) {
        return R.success(service.changeStage(id, request));
    }

    @GetMapping("/{id}/resumes")
    @RequiresPermission("recruit:candidate:list")
    public R<List<Map<String, Object>>> resumes(@PathVariable Long id) {
        return R.success(service.listResumes(id));
    }

    /** 关联简历版本：只接收 file_id（文件已由 G-1 上传），引用计数 +1。 */
    @PostMapping("/{id}/resumes")
    @RequiresPermission("recruit:resume:add")
    @OperLog(module = "候选人", action = "关联简历")
    public R<RecruitIdResponse> attachResume(@PathVariable Long id,
                                             @RequestBody RecruitResumeAttachRequest request) {
        return R.success(service.attachResume(id, request));
    }
}
