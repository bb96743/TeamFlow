package com.teamflow.knowledge.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.knowledge.domain.dto.KnowledgeAclWriteRequest;
import com.teamflow.knowledge.domain.dto.KnowledgeNodeWriteRequest;
import com.teamflow.knowledge.domain.dto.KnowledgeSpaceWriteRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.service.KnowledgeAccessService;
import com.teamflow.knowledge.service.KnowledgeCatalogService;
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
 * M07 知识空间 / 目录 / 访问授权接口（1.2.1）。
 *
 * <p>每个接口均有 {@code @RequiresPermission}；写接口加 {@code @OperLog}；
 * 资源级可见性与继承覆盖由服务层按授权链判定（越权返回 403）。</p>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeCatalogController {

    private final KnowledgeCatalogService catalogService;
    private final KnowledgeAccessService accessService;

    public KnowledgeCatalogController(KnowledgeCatalogService catalogService, KnowledgeAccessService accessService) {
        this.catalogService = catalogService;
        this.accessService = accessService;
    }

    // ---------------- 空间 ----------------

    @GetMapping("/spaces")
    @RequiresPermission("knowledge:space:list")
    public R<PageResult<Map<String, Object>>> listSpaces(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return R.success(catalogService.listSpaces(query, keyword, status));
    }

    @GetMapping("/spaces/{id}")
    @RequiresPermission("knowledge:space:list")
    public R<Map<String, Object>> spaceDetail(@PathVariable Long id) {
        return R.success(catalogService.spaceDetail(id));
    }

    @GetMapping("/spaces/{id}/tree")
    @RequiresPermission("knowledge:space:list")
    public R<Map<String, Object>> tree(@PathVariable Long id) {
        return R.success(catalogService.tree(id));
    }

    @PostMapping("/spaces")
    @RequiresPermission("knowledge:space:add")
    @OperLog(module = "知识空间", action = "新增")
    public R<KnowledgeIdResponse> createSpace(@RequestBody KnowledgeSpaceWriteRequest request) {
        return R.success(catalogService.createSpace(request));
    }

    @PutMapping("/spaces/{id}")
    @RequiresPermission("knowledge:space:edit")
    @OperLog(module = "知识空间", action = "修改")
    public R<Void> updateSpace(@PathVariable Long id, @RequestBody KnowledgeSpaceWriteRequest request) {
        catalogService.updateSpace(id, request);
        return R.success(null);
    }

    @DeleteMapping("/spaces/{id}")
    @RequiresPermission("knowledge:space:remove")
    @OperLog(module = "知识空间", action = "删除")
    public R<Void> deleteSpace(@PathVariable Long id) {
        catalogService.deleteSpace(id);
        return R.success(null);
    }

    // ---------------- 目录 ----------------

    @GetMapping("/spaces/{spaceId}/nodes")
    @RequiresPermission("knowledge:node:list")
    public R<List<Map<String, Object>>> listNodes(@PathVariable Long spaceId) {
        return R.success(catalogService.listNodes(spaceId));
    }

    @GetMapping("/nodes/{id}")
    @RequiresPermission("knowledge:node:list")
    public R<Map<String, Object>> nodeDetail(@PathVariable Long id) {
        return R.success(catalogService.nodeDetail(id));
    }

    @PostMapping("/nodes")
    @RequiresPermission("knowledge:node:add")
    @OperLog(module = "知识目录", action = "新增")
    public R<KnowledgeIdResponse> createNode(@RequestBody KnowledgeNodeWriteRequest request) {
        return R.success(catalogService.createNode(request));
    }

    @PutMapping("/nodes/{id}")
    @RequiresPermission("knowledge:node:edit")
    @OperLog(module = "知识目录", action = "修改")
    public R<Void> updateNode(@PathVariable Long id, @RequestBody KnowledgeNodeWriteRequest request) {
        catalogService.updateNode(id, request);
        return R.success(null);
    }

    @DeleteMapping("/nodes/{id}")
    @RequiresPermission("knowledge:node:remove")
    @OperLog(module = "知识目录", action = "删除")
    public R<Void> deleteNode(@PathVariable Long id) {
        catalogService.deleteNode(id);
        return R.success(null);
    }

    // ---------------- 访问授权（覆盖/拒绝） ----------------

    @GetMapping("/acls")
    @RequiresPermission("knowledge:acl:edit")
    public R<List<Map<String, Object>>> listAcl(
            @RequestParam String resourceType,
            @RequestParam Long resourceId
    ) {
        return R.success(accessService.listAcl(resourceType, resourceId));
    }

    @PostMapping("/acls")
    @RequiresPermission("knowledge:acl:edit")
    @OperLog(module = "知识访问授权", action = "授权")
    public R<Map<String, Object>> grant(@RequestBody KnowledgeAclWriteRequest request) {
        return R.success(accessService.grant(request));
    }

    @DeleteMapping("/acls/{id}")
    @RequiresPermission("knowledge:acl:edit")
    @OperLog(module = "知识访问授权", action = "取消授权")
    public R<Void> revoke(@PathVariable Long id) {
        accessService.revoke(id);
        return R.success(null);
    }
}
