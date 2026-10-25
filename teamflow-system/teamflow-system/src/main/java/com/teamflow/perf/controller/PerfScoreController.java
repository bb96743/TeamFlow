package com.teamflow.perf.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfManagerScoreRequest;
import com.teamflow.perf.domain.dto.PerfNotApplicableRequest;
import com.teamflow.perf.domain.dto.PerfSelfScoreRequest;
import com.teamflow.perf.service.PerfScoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M05 评分接口（{@code /api/perf/scores}）：评分明细、自评、上级评、例外状态标记。
 *
 * <p>R1：可归集类只读（提交可归集项会被拒绝）；主观类必填评语。
 * D-06.5：not_applicable 标记由具备上级评分权限的管理者/HR 执行，必须填写原因。</p>
 */
@RestController
@RequestMapping("/api/perf/scores")
public class PerfScoreController {

    private final PerfScoreService service;

    public PerfScoreController(PerfScoreService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("perf:score:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long cycleId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        return R.success(service.list(cycleId, employeeId, new PageQuery(pageNum, pageSize, null, null)));
    }

    @PutMapping("/self")
    @RequiresPermission("perf:score:self")
    @OperLog(module = "绩效评分", action = "本人自评")
    public R<Map<String, Object>> saveSelf(@RequestBody PerfSelfScoreRequest request) {
        return R.success(service.saveSelf(request));
    }

    @PutMapping("/manager")
    @RequiresPermission("perf:score:submit")
    @OperLog(module = "绩效评分", action = "上级评分")
    public R<Map<String, Object>> saveManager(@RequestBody PerfManagerScoreRequest request) {
        return R.success(service.saveManager(request));
    }

    @PutMapping("/not-applicable")
    @RequiresPermission("perf:score:submit")
    @OperLog(module = "绩效评分", action = "标记不适用")
    public R<Map<String, Object>> markNotApplicable(@RequestBody PerfNotApplicableRequest request) {
        return R.success(service.markNotApplicable(request));
    }
}
