package com.teamflow.system.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.system.domain.vo.FileDownload;
import com.teamflow.system.domain.vo.FileUploadResponse;
import com.teamflow.system.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一文件上传接口（G-1，M00 平台能力）。
 *
 * <p>权限码（V12 已落 {@code sys_menu}）：</p>
 * <ul>
 *   <li>{@code POST /api/system/files} —— {@code system:file:upload}</li>
 *   <li>{@code GET /api/system/files/{id}} —— {@code system:file:list}（下载必须鉴权）</li>
 *   <li>{@code DELETE /api/system/files/{id}} —— {@code system:file:remove}</li>
 * </ul>
 *
 * <p><b>不做静态目录直出</b>：文件只能经本接口按 id 下载，避免猜到 URL 越权获取；
 * 未认证请求由 SecurityConfig 拦截返回 401，无权限返回 403。</p>
 */
@RestController
@RequestMapping("/api/system")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("system:file:upload")
    @OperLog(module = "文件管理", action = "上传")
    public R<FileUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "bizId", required = false) Long bizId
    ) {
        return R.success(fileService.upload(file, bizType, bizId));
    }

    @GetMapping("/files/{id}")
    @RequiresPermission("system:file:list")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileDownload download = fileService.download(id);
        String encodedName = URLEncoder.encode(download.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(download.resource());
    }

    @DeleteMapping("/files/{id}")
    @RequiresPermission("system:file:remove")
    @OperLog(module = "文件管理", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return R.success(null);
    }
}
