package com.teamflow.insight.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.insight.domain.dto.InsightMetricWriteRequest;
import com.teamflow.insight.domain.vo.InsightIdResponse;
import com.teamflow.insight.service.InsightMetricService;
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
 * M13 指标字典接口（{@code /api/insight/metrics}，需求 7.1）。
 *
 * <p>未登记口径的指标不得上线：新增/编辑为 {@code online} 时校验口径要素齐全且已命中 M13 指标实现登记；
 * 看板接口只认 online 口径（下线即停止出数）。</p>
 */
@RestController
@RequestMapping("/api/insight/metrics")
public class InsightMetricController {

    private final InsightMetricService service;

    public InsightMetricController(InsightMetricService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("insight:metric:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(new PageQuery(pageNum, pageSize, null, null), keyword, category, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("insight:metric:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("insight:metric:add")
    @OperLog(module = "指标字典", action = "新增")
    public R<InsightIdResponse> create(@RequestBody InsightMetricWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("insight:metric:edit")
    @OperLog(module = "指标字典", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody InsightMetricWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    /** 删除走 remove 权限码；已上线指标必须先下线（R1：避免上线口径凭空消失）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("insight:metric:remove")
    @OperLog(module = "指标字典", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
