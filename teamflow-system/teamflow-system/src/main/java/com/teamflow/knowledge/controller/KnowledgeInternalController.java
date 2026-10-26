package com.teamflow.knowledge.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;
import com.teamflow.knowledge.domain.vo.PageContentSlice;
import com.teamflow.knowledge.service.KnowledgeArchiveService;
import com.teamflow.knowledge.service.KnowledgeContentProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * M07 内部只读端口（供 M11 RAG 切片、跨模块归档联调）。
 *
 * <p>权限码 {@code knowledge:internal:read} 不授予一般角色（与 M09/M10 内部接口同一做法）。
 * 同进程模块（M06/M14/M11）优先直接注入 Service：
 * {@code KnowledgeArchiveService} / {@code KnowledgeContentProvider}。</p>
 */
@RestController
@RequestMapping("/api/knowledge/internal")
public class KnowledgeInternalController {

    private final KnowledgeContentProvider contentProvider;
    private final KnowledgeArchiveService archiveService;

    public KnowledgeInternalController(KnowledgeContentProvider contentProvider, KnowledgeArchiveService archiveService) {
        this.contentProvider = contentProvider;
        this.archiveService = archiveService;
    }

    /** 按页面 ID 取正文切片（M11 RAG 只读端口）。 */
    @GetMapping("/pages/{id}/content")
    @RequiresPermission("knowledge:internal:read")
    public R<PageContentSlice> content(
            @PathVariable Long id,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit
    ) {
        return R.success(contentProvider.sliceContent(id, offset, limit));
    }

    /** 跨进程归档入口（同进程请直接调用归档 Service；本接口用于联调/未来跨进程调用）。 */
    @PostMapping("/archive")
    @RequiresPermission("knowledge:internal:read")
    @OperLog(module = "知识归档", action = "内部归档")
    public R<KnowledgeArchiveResult> archive(@RequestBody KnowledgeArchiveRequest request) {
        return R.success(archiveService.archive(request));
    }

    /** 查询某业务单据已归档的页面 ID（未归档返回 null）。 */
    @GetMapping("/archive/page-id")
    @RequiresPermission("knowledge:internal:read")
    public R<Long> archivedPageId(
            @RequestParam String sourceModule,
            @RequestParam String sourceBizType,
            @RequestParam Long sourceBizId
    ) {
        Optional<Long> pageId = archiveService.findArchivedPageId(sourceModule, sourceBizType, sourceBizId);
        return R.success(pageId.orElse(null));
    }
}
