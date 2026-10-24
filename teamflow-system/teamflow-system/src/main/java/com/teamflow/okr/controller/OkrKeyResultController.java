package com.teamflow.okr.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.okr.domain.dto.OkrKeyResultProgressRequest;
import com.teamflow.okr.domain.dto.OkrKeyResultWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.service.OkrKeyResultService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M02 关键结果接口（{@code /api/okr/objectives/{id}/krs}），需求 1.1.2。
 *
 * <p>进度更新与进度留痕按 M02 RULE R1 落地；任务驱动型回写入口（{@code /api/okr/progress}）
 * 属 M03 事件接入范围，本期不开放。</p>
 */
@RestController
@RequestMapping("/api/okr")
public class OkrKeyResultController {

    private final OkrKeyResultService service;

    public OkrKeyResultController(OkrKeyResultService service) {
        this.service = service;
    }

    @GetMapping("/objectives/{objectiveId}/krs")
    @RequiresPermission("okr:kr:list")
    public R<List<Map<String, Object>>> list(@PathVariable Long objectiveId) {
        return R.success(service.list(objectiveId));
    }

    @GetMapping("/objectives/{objectiveId}/krs/{krId}")
    @RequiresPermission("okr:kr:list")
    public R<Map<String, Object>> detail(@PathVariable Long objectiveId, @PathVariable Long krId) {
        return R.success(service.detail(objectiveId, krId));
    }

    @PostMapping("/objectives/{objectiveId}/krs")
    @RequiresPermission("okr:kr:add")
    @OperLog(module = "关键结果", action = "新增")
    public R<OkrIdResponse> create(@PathVariable Long objectiveId, @RequestBody OkrKeyResultWriteRequest request) {
        return R.success(service.create(objectiveId, request));
    }

    @PutMapping("/objectives/{objectiveId}/krs/{krId}")
    @RequiresPermission("okr:kr:edit")
    @OperLog(module = "关键结果", action = "修改")
    public R<Void> update(
            @PathVariable Long objectiveId,
            @PathVariable Long krId,
            @RequestBody OkrKeyResultWriteRequest request
    ) {
        service.update(objectiveId, krId, request);
        return R.success(null);
    }

    @DeleteMapping("/objectives/{objectiveId}/krs/{krId}")
    @RequiresPermission("okr:kr:remove")
    @OperLog(module = "关键结果", action = "删除")
    public R<Void> delete(@PathVariable Long objectiveId, @PathVariable Long krId) {
        service.delete(objectiveId, krId);
        return R.success(null);
    }

    @PutMapping("/objectives/{objectiveId}/krs/{krId}/progress")
    @RequiresPermission("okr:kr:edit")
    @OperLog(module = "关键结果", action = "更新进度")
    public R<Map<String, Object>> updateProgress(
            @PathVariable Long objectiveId,
            @PathVariable Long krId,
            @RequestBody OkrKeyResultProgressRequest request
    ) {
        return R.success(service.updateProgress(objectiveId, krId, request));
    }

    @GetMapping("/objectives/{objectiveId}/krs/{krId}/progress-logs")
    @RequiresPermission("okr:kr:list")
    public R<List<Map<String, Object>>> progressLogs(@PathVariable Long objectiveId, @PathVariable Long krId) {
        return R.success(service.progressLogs(objectiveId, krId));
    }
}
