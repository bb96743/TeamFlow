package com.teamflow.integration.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.integration.service.TransferService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 导入导出接口（2.4.5）：任务 CSV 模板、导入（逐行结果）、导出（UTF-8 BOM）。
 *
 * <p>导出数据来自任务 Service，自动受当前用户数据权限约束；导入逐行走任务 Service，
 * 不直写业务表。</p>
 */
@RestController
@RequestMapping("/api/integration/transfer")
public class TransferController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /** 导入模板（UTF-8 BOM，Excel 友好）。 */
    @GetMapping("/tasks/template")
    @RequiresPermission("integration:transfer:export")
    public ResponseEntity<byte[]> template() {
        return csvResponse("tasks-import-template.csv", service.taskTemplate());
    }

    /** 导出任务列表（当前用户数据范围内的任务）。 */
    @GetMapping("/tasks/export")
    @RequiresPermission("integration:transfer:export")
    @OperLog(module = "导入导出", action = "导出任务")
    public ResponseEntity<byte[]> exportTasks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return csvResponse("tasks-" + System.currentTimeMillis() + ".csv",
                service.exportTasks(keyword, status));
    }

    /** 导入任务：逐行校验 + 逐行结果；失败行不产生数据。 */
    @PostMapping("/tasks/import")
    @RequiresPermission("integration:transfer:import")
    @OperLog(module = "导入导出", action = "导入任务")
    public R<Map<String, Object>> importTasks(@RequestParam("file") MultipartFile file) throws Exception {
        return R.success(service.importTasks(file.getOriginalFilename(), file.getBytes()));
    }

    /** 最近导入导出批次（审计）。 */
    @GetMapping("/logs")
    @RequiresPermission("integration:transfer:export")
    public R<List<Map<String, Object>>> logs(@RequestParam(required = false) Integer limit) {
        return R.success(service.recentLogs(limit));
    }

    private ResponseEntity<byte[]> csvResponse(String filename, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
