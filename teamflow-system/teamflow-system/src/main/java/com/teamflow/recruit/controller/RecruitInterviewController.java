package com.teamflow.recruit.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.recruit.domain.dto.RecruitFeedbackWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitInterviewUpdateRequest;
import com.teamflow.recruit.domain.dto.RecruitInterviewWriteRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.service.RecruitInterviewService;
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
 * M08 面试接口（{@code /api/recruit/interviews}，需求 2.1.3）。
 *
 * <p>轮次编排 + 面试官分配 + 结构化评价（R4：每维度独立评分与评语）。日程能力归 M06（M3 排期），
 * 本期只存面试时间/地点。</p>
 */
@RestController
@RequestMapping("/api/recruit/interviews")
public class RecruitInterviewController {

    private final RecruitInterviewService service;

    public RecruitInterviewController(RecruitInterviewService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("recruit:interview:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long candidateId,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long interviewerId
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null),
                candidateId, jobId, status, interviewerId));
    }

    @GetMapping("/{id}")
    @RequiresPermission("recruit:interview:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("recruit:interview:add")
    @OperLog(module = "招聘面试", action = "安排面试")
    public R<RecruitIdResponse> schedule(@RequestBody RecruitInterviewWriteRequest request) {
        return R.success(service.schedule(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("recruit:interview:edit")
    @OperLog(module = "招聘面试", action = "修改面试")
    public R<Map<String, Object>> update(@PathVariable Long id,
                                         @RequestBody RecruitInterviewUpdateRequest request) {
        return R.success(service.update(id, request));
    }

    /** 提交结构化评价（多维度、每维度评分 + 评语；同维度重复提交覆盖）。 */
    @PostMapping("/{id}/feedbacks")
    @RequiresPermission("recruit:interview:edit")
    @OperLog(module = "招聘面试", action = "提交评价")
    public R<Map<String, Object>> submitFeedback(@PathVariable Long id,
                                                 @RequestBody RecruitFeedbackWriteRequest request) {
        return R.success(service.submitFeedback(id, request));
    }
}
