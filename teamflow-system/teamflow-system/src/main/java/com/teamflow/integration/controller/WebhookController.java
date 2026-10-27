package com.teamflow.integration.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.integration.domain.dto.WebhookWriteRequest;
import com.teamflow.integration.service.WebhookService;
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
 * Webhook 订阅管理（2.4.4）：配置、测试投递、失败重试。
 *
 * <p>签名密钥只在创建/编辑请求中出现，服务端加密存储；任何响应都不返回明文。</p>
 */
@RestController
@RequestMapping("/api/integration/webhooks")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("integration:webhook:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, keyword, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("integration:webhook:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @GetMapping("/{id}/deliveries")
    @RequiresPermission("integration:webhook:list")
    public R<List<Map<String, Object>>> deliveries(
            @PathVariable Long id,
            @RequestParam(required = false) Integer limit
    ) {
        return R.success(service.deliveries(id, limit));
    }

    @PostMapping
    @RequiresPermission("integration:webhook:add")
    @OperLog(module = "Webhook 订阅", action = "新增订阅")
    public R<Long> create(@RequestBody WebhookWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("integration:webhook:edit")
    @OperLog(module = "Webhook 订阅", action = "编辑订阅")
    public R<Void> update(@PathVariable Long id, @RequestBody WebhookWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("integration:webhook:remove")
    @OperLog(module = "Webhook 订阅", action = "删除订阅")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    /** 测试投递：验证签名与目标可达性。 */
    @PostMapping("/{id}/test")
    @RequiresPermission("integration:webhook:test")
    @OperLog(module = "Webhook 订阅", action = "测试投递")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.success(service.test(id));
    }

    /** 重试一次失败的投递（复用原载荷，重新签名与计数）。 */
    @PostMapping("/deliveries/{deliveryId}/retry")
    @RequiresPermission("integration:webhook:test")
    @OperLog(module = "Webhook 订阅", action = "重试投递")
    public R<Map<String, Object>> retry(@PathVariable Long deliveryId) {
        return R.success(service.retry(deliveryId));
    }
}
