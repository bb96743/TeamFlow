package com.teamflow.integration.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.integration.domain.dto.IntegrationAppWriteRequest;
import com.teamflow.integration.service.IntegrationAppService;
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
 * 第三方集成中心（需求 2.4.1）：接入应用配置、凭据加密存储与连通性检测。
 *
 * <p>仅做配置与探测框架，<b>不发起也不伪造</b>第三方业务调用；凭据明文在落库前加密，
 * 接口与日志都不返回明文。</p>
 */
@RestController
@RequestMapping("/api/integration/apps")
public class IntegrationAppController {

    private final IntegrationAppService service;

    public IntegrationAppController(IntegrationAppService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("integration:app:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String appType,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, keyword, appType, status));
    }

    @GetMapping("/encryption-status")
    @RequiresPermission("integration:app:list")
    public R<Map<String, Object>> encryptionStatus() {
        return R.success(service.encryptionStatus());
    }

    @GetMapping("/{id}")
    @RequiresPermission("integration:app:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("integration:app:add")
    @OperLog(module = "第三方集成", action = "新增接入应用")
    public R<Long> create(@RequestBody IntegrationAppWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("integration:app:edit")
    @OperLog(module = "第三方集成", action = "编辑接入应用")
    public R<Void> update(@PathVariable Long id, @RequestBody IntegrationAppWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("integration:app:remove")
    @OperLog(module = "第三方集成", action = "删除接入应用")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    /** 连通性检测：无凭据/无地址/不可达时明确返回失败，不伪造成功。 */
    @PostMapping("/{id}/check")
    @RequiresPermission("integration:app:check")
    @OperLog(module = "第三方集成", action = "连通性检测")
    public R<Map<String, Object>> check(@PathVariable Long id) {
        return R.success(service.check(id));
    }
}
