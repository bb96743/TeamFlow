package com.teamflow.integration.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.integration.domain.dto.ApiKeyCreateRequest;
import com.teamflow.integration.domain.vo.ApiKeyCreatedResponse;
import com.teamflow.integration.service.ApiKeyService;
import com.teamflow.integration.service.OpenApiAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 开放 API 密钥管理（需求 2.4.3）。
 *
 * <p>密钥库中只存哈希与前缀；创建响应是明文唯一一次出现的地方。Key 绑定用户与权限范围，
 * 调用时以绑定用户身份执行并受 scope 约束；撤销即时生效。</p>
 */
@RestController
@RequestMapping("/api/integration/keys")
public class ApiKeyController {

    private final ApiKeyService keyService;
    private final OpenApiAuditService auditService;

    public ApiKeyController(ApiKeyService keyService, OpenApiAuditService auditService) {
        this.keyService = keyService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequiresPermission("integration:key:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String status
    ) {
        return R.success(keyService.list(query, keyword, appId, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("integration:key:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(keyService.detail(id));
    }

    @PostMapping
    @RequiresPermission("integration:key:add")
    @OperLog(module = "开放密钥", action = "创建密钥")
    public R<ApiKeyCreatedResponse> create(@RequestBody ApiKeyCreateRequest request) {
        return R.success(keyService.create(request));
    }

    @PostMapping("/{id}/revoke")
    @RequiresPermission("integration:key:revoke")
    @OperLog(module = "开放密钥", action = "撤销密钥")
    public R<Void> revoke(@PathVariable Long id) {
        keyService.revoke(id);
        return R.success(null);
    }

    /** 最近开放调用审计（含开放接口与 MCP 工具调用）。 */
    @GetMapping("/audits")
    @RequiresPermission("integration:key:list")
    public R<List<Map<String, Object>>> audits(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Long keyId
    ) {
        return R.success(auditService.recent(channel, keyId));
    }
}
