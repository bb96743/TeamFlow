package com.teamflow.knowledge.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.knowledge.domain.dto.KnowledgeFileWriteRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.service.KnowledgeAttachmentService;
import com.teamflow.system.domain.vo.FileDownload;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * M07 文档中心附件接口（1.2.2）。
 *
 * <p>附件本体走平台统一上传能力：本模块只登记文件 ID 并增减引用计数；
 * 下载经本接口鉴权（知识页面/空间读权限）后代理平台文件流，避免静态地址直出。</p>
 */
@RestController
@RequestMapping("/api/knowledge/files")
public class KnowledgeFileController {

    private final KnowledgeAttachmentService service;

    public KnowledgeFileController(KnowledgeAttachmentService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("knowledge:file:list")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) Long pageId
    ) {
        return R.success(service.list(spaceId, pageId));
    }

    @PostMapping
    @RequiresPermission("knowledge:file:add")
    @OperLog(module = "文档中心", action = "引用附件")
    public R<KnowledgeIdResponse> attach(@RequestBody KnowledgeFileWriteRequest request) {
        return R.success(service.attach(request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("knowledge:file:remove")
    @OperLog(module = "文档中心", action = "移除附件引用")
    public R<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return R.success(null);
    }

    @GetMapping("/{id}/download")
    @RequiresPermission("knowledge:file:list")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileDownload download = service.download(id);
        String encodedName = URLEncoder.encode(download.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(download.resource());
    }
}
