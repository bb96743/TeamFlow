package com.teamflow.knowledge.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.knowledge.domain.dto.KnowledgePageWriteRequest;
import com.teamflow.knowledge.domain.dto.KnowledgeVersionRollbackRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.service.KnowledgePageService;
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
 * M07 页面与版本接口（1.2.1 / 1.2.3 / 1.2.4 / 1.2.5）。
 *
 * <p>页面类型 {@code sop}/{@code decision} 用于流程说明库与决策记录（页面 + 标签表达，不另建表）。
 * 保存即生成版本快照；回滚用历史版本内容生成新版本，不覆盖历史。</p>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgePageController {

    private final KnowledgePageService pageService;

    public KnowledgePageController(KnowledgePageService pageService) {
        this.pageService = pageService;
    }

    @GetMapping("/pages")
    @RequiresPermission("knowledge:page:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String pageType,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return R.success(pageService.list(query, spaceId, nodeId, pageType, tag, status, keyword));
    }

    @GetMapping("/pages/{id}")
    @RequiresPermission("knowledge:page:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(pageService.detail(id));
    }

    @PostMapping("/pages")
    @RequiresPermission("knowledge:page:add")
    @OperLog(module = "知识页面", action = "新增")
    public R<KnowledgeIdResponse> create(@RequestBody KnowledgePageWriteRequest request) {
        return R.success(pageService.create(request));
    }

    @PutMapping("/pages/{id}")
    @RequiresPermission("knowledge:page:edit")
    @OperLog(module = "知识页面", action = "修改")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody KnowledgePageWriteRequest request) {
        return R.success(pageService.update(id, request));
    }

    @DeleteMapping("/pages/{id}")
    @RequiresPermission("knowledge:page:remove")
    @OperLog(module = "知识页面", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        pageService.delete(id);
        return R.success(null);
    }

    @GetMapping("/pages/{id}/versions")
    @RequiresPermission("knowledge:page:list")
    public R<List<Map<String, Object>>> versions(@PathVariable Long id) {
        return R.success(pageService.versions(id));
    }

    @GetMapping("/pages/{id}/versions/{versionNo}")
    @RequiresPermission("knowledge:page:list")
    public R<Map<String, Object>> versionDetail(@PathVariable Long id, @PathVariable Integer versionNo) {
        return R.success(pageService.versionDetail(id, versionNo));
    }

    @PostMapping("/pages/{id}/versions/{versionNo}/rollback")
    @RequiresPermission("knowledge:page:edit")
    @OperLog(module = "知识页面", action = "版本回滚")
    public R<Map<String, Object>> rollback(
            @PathVariable Long id,
            @PathVariable Integer versionNo,
            @RequestBody(required = false) KnowledgeVersionRollbackRequest request
    ) {
        return R.success(pageService.rollback(id, versionNo, request));
    }
}
