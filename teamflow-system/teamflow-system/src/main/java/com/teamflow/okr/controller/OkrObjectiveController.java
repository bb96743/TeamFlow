package com.teamflow.okr.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.okr.domain.dto.OkrAlignWriteRequest;
import com.teamflow.okr.domain.dto.OkrObjectiveWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.service.OkrAlignService;
import com.teamflow.okr.service.OkrObjectiveService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * M02 目标接口（{@code /api/okr/objectives}）、对齐关系与对齐度检查（1.1.1 / 1.1.5）。
 *
 * <p>对齐度检查复用 {@code okr:objective:list} 权限；对齐关系写入复用目标编辑权限。</p>
 */
@RestController
@RequestMapping("/api/okr")
public class OkrObjectiveController {

    private final OkrObjectiveService objectiveService;
    private final OkrAlignService alignService;

    public OkrObjectiveController(OkrObjectiveService objectiveService, OkrAlignService alignService) {
        this.objectiveService = objectiveService;
        this.alignService = alignService;
    }

    @GetMapping("/objectives")
    @RequiresPermission("okr:objective:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return R.success(objectiveService.list(query, periodId, level, ownerId, status, keyword));
    }

    @GetMapping("/objectives/tree")
    @RequiresPermission("okr:objective:list")
    public R<List<Map<String, Object>>> tree(@RequestParam(required = false) Long periodId) {
        return R.success(objectiveService.tree(periodId));
    }

    @GetMapping("/objectives/{id}")
    @RequiresPermission("okr:objective:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(objectiveService.detail(id));
    }

    @PostMapping("/objectives")
    @RequiresPermission("okr:objective:add")
    @OperLog(module = "目标管理", action = "新增")
    public R<OkrIdResponse> create(@RequestBody OkrObjectiveWriteRequest request) {
        return R.success(objectiveService.create(request));
    }

    @PutMapping("/objectives/{id}")
    @RequiresPermission("okr:objective:edit")
    @OperLog(module = "目标管理", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody OkrObjectiveWriteRequest request) {
        objectiveService.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/objectives/{id}")
    @RequiresPermission("okr:objective:remove")
    @OperLog(module = "目标管理", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        objectiveService.delete(id);
        return R.success(null);
    }

    @GetMapping("/objectives/{id}/aligns")
    @RequiresPermission("okr:objective:list")
    public R<List<Map<String, Object>>> aligns(@PathVariable Long id) {
        return R.success(alignService.list(id));
    }

    @PostMapping("/objectives/{id}/aligns")
    @RequiresPermission("okr:objective:edit")
    @OperLog(module = "目标管理", action = "新增对齐关系")
    public R<OkrIdResponse> createAlign(@PathVariable Long id, @RequestBody OkrAlignWriteRequest request) {
        return R.success(alignService.create(id, request));
    }

    @DeleteMapping("/aligns/{alignId}")
    @RequiresPermission("okr:objective:edit")
    @OperLog(module = "目标管理", action = "删除对齐关系")
    public R<Void> deleteAlign(@PathVariable Long alignId) {
        alignService.delete(alignId);
        return R.success(null);
    }

    @GetMapping("/align-check")
    @RequiresPermission("okr:objective:list")
    public R<Map<String, Object>> alignCheck(@RequestParam(required = false) Long periodId) {
        return R.success(objectiveService.alignCheck(periodId));
    }
}
