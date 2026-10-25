package com.teamflow.personal.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.personal.domain.dto.ReviewLogWriteRequest;
import com.teamflow.personal.domain.vo.PersonalIdResponse;
import com.teamflow.personal.service.ReviewLogService;
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
 * M10 复盘与成长日志接口（{@code /api/personal/reviews}），需求 2.3.4。
 *
 * <p>默认私有存本模块；公开归档 M07 属后续阶段（本期只预留字段）。</p>
 */
@RestController
@RequestMapping("/api/personal/reviews")
public class ReviewLogController {

    private final ReviewLogService reviewLogService;

    public ReviewLogController(ReviewLogService reviewLogService) {
        this.reviewLogService = reviewLogService;
    }

    @GetMapping
    @RequiresPermission("personal:review:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query, @RequestParam(required = false) String reviewType) {
        return R.success(reviewLogService.list(query, reviewType));
    }

    @GetMapping("/{id}")
    @RequiresPermission("personal:review:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(reviewLogService.detail(id));
    }

    @PostMapping
    @RequiresPermission("personal:review:add")
    @OperLog(module = "工作复盘", action = "新增")
    public R<PersonalIdResponse> create(@RequestBody ReviewLogWriteRequest request) {
        return R.success(new PersonalIdResponse(reviewLogService.create(request)));
    }

    @PutMapping("/{id}")
    @RequiresPermission("personal:review:edit")
    @OperLog(module = "工作复盘", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody ReviewLogWriteRequest request) {
        reviewLogService.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("personal:review:remove")
    @OperLog(module = "工作复盘", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        reviewLogService.delete(id);
        return R.success(null);
    }
}
