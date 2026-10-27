package com.teamflow.integration.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.domain.dto.WebhookWriteRequest;
import com.teamflow.integration.mapper.OpenWebhookDeliveryMapper;
import com.teamflow.integration.mapper.OpenWebhookMapper;
import com.teamflow.integration.security.CredentialCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Webhook 订阅管理（2.4.4）：配置、密钥加密、测试投递与投递记录。
 *
 * <p><b>密钥安全</b>：签名密钥由使用方提供，落库前用 M12 同一套对称加密（{@link CredentialCipher}），
 * 列表/详情只返回末 4 位提示位，任何响应都不返回明文；编辑时密钥留空表示不修改。</p>
 */
@Service
public class WebhookService extends IntegrationSupport {

    /** 允许订阅的事件类型（值为 M03/M09 已发布事件，不在此新建事件类）。 */
    private static final Set<String> EVENT_TYPES = Set.of(
            "task.created", "task.status_changed", "task.kr_changed", "task.deleted", "collab.mention");
    private static final Set<String> STATUSES = Set.of("enabled", "disabled");
    private static final int MIN_SECRET_LENGTH = 8;

    private final OpenWebhookMapper webhookMapper;
    private final OpenWebhookDeliveryMapper deliveryMapper;
    private final CredentialCipher credentialCipher;
    private final WebhookDeliveryService deliveryService;

    public WebhookService(
            OpenWebhookMapper webhookMapper,
            OpenWebhookDeliveryMapper deliveryMapper,
            CredentialCipher credentialCipher,
            WebhookDeliveryService deliveryService
    ) {
        this.webhookMapper = webhookMapper;
        this.deliveryMapper = deliveryMapper;
        this.credentialCipher = credentialCipher;
        this.deliveryService = deliveryService;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status) {
        Page page = pageOf(query);
        String statusFilter = trimToNull(status);
        long total = webhookMapper.countWebhooks(trimToNull(keyword), statusFilter);
        List<Map<String, Object>> rows = webhookMapper.selectWebhooks(trimToNull(keyword), statusFilter,
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = webhookMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("Webhook 订阅不存在");
        }
        decorate(row);
        return row;
    }

    @Transactional
    public Long create(WebhookWriteRequest request) {
        if (request == null) {
            throw new BusinessException("订阅配置不能为空");
        }
        String code = required(request.webhookCode(), "订阅编码不能为空");
        if (webhookMapper.selectIdByCode(code, null) != null) {
            throw new BusinessException("订阅编码已存在：" + code);
        }
        String secret = required(request.secret(), "签名密钥不能为空（接入方需用它验签）");
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new BusinessException("签名密钥至少 " + MIN_SECRET_LENGTH + " 个字符");
        }
        if (!credentialCipher.configured()) {
            throw new BusinessException("凭据加密密钥未配置，拒绝保存签名密钥（不会以明文存储）");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("webhookCode", code);
        row.put("webhookName", required(request.webhookName(), "订阅名称不能为空"));
        row.put("targetUrl", requireHttpUrl(request.targetUrl()));
        row.put("eventTypes", normalizeEventTypes(request.eventTypes()));
        row.put("secretCipher", credentialCipher.encrypt(secret));
        row.put("secretHint", credentialCipher.hintOf(secret));
        row.put("timeoutSeconds", timeoutOf(request.timeoutSeconds()));
        row.put("status", statusOf(request.status(), "enabled"));
        row.put("remark", trimToNull(request.remark()));
        webhookMapper.insertWebhook(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    @Transactional
    public void update(Long id, WebhookWriteRequest request) {
        Map<String, Object> existing = webhookMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("Webhook 订阅不存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("webhookName", request.webhookName() == null
                ? existing.get("webhookName") : required(request.webhookName(), "订阅名称不能为空"));
        row.put("targetUrl", request.targetUrl() == null
                ? existing.get("targetUrl") : requireHttpUrl(request.targetUrl()));
        row.put("eventTypes", request.eventTypes() == null
                ? existing.get("eventTypes") : normalizeEventTypes(request.eventTypes()));
        String secret = trimToNull(request.secret());
        if (secret == null) {
            // 留空表示不修改：沿用原密文与提示位
            row.put("secretCipher", webhookMapper.selectForDelivery(id).get("secretCipher"));
            row.put("secretHint", existing.get("secretHint"));
        } else {
            if (secret.length() < MIN_SECRET_LENGTH) {
                throw new BusinessException("签名密钥至少 " + MIN_SECRET_LENGTH + " 个字符");
            }
            if (!credentialCipher.configured()) {
                throw new BusinessException("凭据加密密钥未配置，拒绝保存签名密钥（不会以明文存储）");
            }
            row.put("secretCipher", credentialCipher.encrypt(secret));
            row.put("secretHint", credentialCipher.hintOf(secret));
        }
        row.put("timeoutSeconds", request.timeoutSeconds() == null
                ? longOf(existing.get("timeoutSeconds")) : timeoutOf(request.timeoutSeconds()));
        row.put("status", request.status() == null
                ? existing.get("status") : statusOf(request.status(), "enabled"));
        row.put("remark", request.remark() == null ? existing.get("remark") : trimToNull(request.remark()));
        webhookMapper.updateWebhook(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        if (webhookMapper.selectById(id) == null) {
            throw new BusinessException("Webhook 订阅不存在");
        }
        webhookMapper.softDelete(id, currentUsername());
        deliveryMapper.softDeleteByWebhook(id, currentUsername());
    }

    /** 测试投递：验证签名与目标可达性（不依赖任何领域事件）。 */
    public Map<String, Object> test(Long id) {
        return deliveryService.sendTest(id);
    }

    /** 最近投递记录（失败可重试）。 */
    public List<Map<String, Object>> deliveries(Long id, Integer limit) {
        return deliveryService.listDeliveries(id, limit);
    }

    public Map<String, Object> retry(Long deliveryId) {
        return deliveryService.retry(deliveryId);
    }

    // ------------------------------------------------------------------

    private void decorate(Map<String, Object> row) {
        row.put("secretConfigured", booleanOf(row.get("secretConfigured")));
        row.put("statusLabel", "enabled".equals(stringOf(row.get("status"))) ? "启用" : "停用");
        row.put("eventTypeList", splitEventTypes(stringOf(row.get("eventTypes"))));
    }

    private List<String> splitEventTypes(String eventTypes) {
        if (eventTypes == null || eventTypes.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private String normalizeEventTypes(String raw) {
        String text = required(raw, "订阅事件类型不能为空（逗号分隔，如 task.created,task.status_changed）");
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : text.split(",")) {
            String code = item.trim();
            if (code.isEmpty()) {
                continue;
            }
            if ("*".equals(code)) {
                normalized.add("*");
                continue;
            }
            if (!EVENT_TYPES.contains(code)) {
                throw new BusinessException("不支持的事件类型：" + code + "（可选 "
                        + String.join("、", EVENT_TYPES) + " 或 *）");
            }
            normalized.add(code);
        }
        if (normalized.isEmpty()) {
            throw new BusinessException("订阅事件类型不能为空");
        }
        return String.join(",", normalized);
    }

    private String requireHttpUrl(String value) {
        String url = required(value, "回调地址不能为空");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException("回调地址必须以 http:// 或 https:// 开头");
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("回调地址格式不合法");
        }
        return url;
    }

    private String statusOf(String value, String defaultValue) {
        String status = defaultString(value, defaultValue);
        if (!STATUSES.contains(status)) {
            throw new BusinessException("状态只能为 enabled 或 disabled");
        }
        return status;
    }

    private int timeoutOf(Integer value) {
        int timeout = value == null ? 3 : value;
        if (timeout < 1 || timeout > 10) {
            throw new BusinessException("投递超时时间需在 1-10 秒之间");
        }
        return timeout;
    }
}
