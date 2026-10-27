package com.teamflow.integration.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.mapper.OpenWebhookDeliveryMapper;
import com.teamflow.integration.mapper.OpenWebhookMapper;
import com.teamflow.integration.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 投递服务（2.4.4）：签名、幂等、失败留痕与重试。
 *
 * <p><b>幂等</b>：投递表唯一键 {@code (webhook_id, event_id)} 是闸门——同一订阅同一事件只投一次；
 * 重复投递直接跳过。<b>签名</b>：{@code HMAC-SHA256(secret, timestamp + "." + body)}，
 * 头 {@code X-TeamFlow-Signature: sha256=...}、{@code X-TeamFlow-Timestamp}、
 * {@code X-TeamFlow-Event}、{@code X-TeamFlow-Delivery}。<b>失败</b>：记录错误与建议重试时间
 * （指数退避，上限 1 小时），可经接口手动重试。</p>
 *
 * <p><b>不做业务实现</b>：载荷由事件内容构成（订阅方传入的事件字段），本服务不查询任何业务表。</p>
 */
@Service
public class WebhookDeliveryService extends IntegrationSupport {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final int MAX_TIMEOUT_SECONDS = 10;
    private static final int MAX_BACKOFF_SECONDS = 3600;

    private final OpenWebhookMapper webhookMapper;
    private final OpenWebhookDeliveryMapper deliveryMapper;
    private final CredentialCipher credentialCipher;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public WebhookDeliveryService(
            OpenWebhookMapper webhookMapper,
            OpenWebhookDeliveryMapper deliveryMapper,
            CredentialCipher credentialCipher,
            tools.jackson.databind.ObjectMapper objectMapper
    ) {
        this.webhookMapper = webhookMapper;
        this.deliveryMapper = deliveryMapper;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    /**
     * 按事件类型分发到所有启用且匹配的订阅。
     *
     * @return 实际完成投递（未跳过）的订阅数
     */
    public int deliver(String eventType, String eventId, Map<String, Object> data) {
        List<Map<String, Object>> webhooks = webhookMapper.selectEnabledForDelivery();
        int delivered = 0;
        for (Map<String, Object> webhook : webhooks) {
            if (!matches(stringOf(webhook.get("eventTypes")), eventType)) {
                continue;
            }
            try {
                if (deliverOne(webhook, eventType, eventId, data)) {
                    delivered++;
                }
            } catch (RuntimeException exception) {
                log.warn("Webhook 投递异常（不影响主流程） webhookId={} eventType={} eventId={} error={}",
                        webhook.get("id"), eventType, eventId, exception.getMessage());
            }
        }
        return delivered;
    }

    /** 手动重试一条失败投递（复用原载荷，重新签名与计数）。 */
    public Map<String, Object> retry(Long deliveryId) {
        Map<String, Object> row = deliveryMapper.selectForRetry(deliveryId);
        if (row == null) {
            throw new BusinessException("投递记录不存在");
        }
        if (!"enabled".equals(stringOf(row.get("webhookStatus")))) {
            throw new BusinessException("订阅已停用，无法重试投递");
        }
        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("id", row.get("webhookId"));
        webhook.put("targetUrl", row.get("targetUrl"));
        webhook.put("secretCipher", row.get("secretCipher"));
        webhook.put("timeoutSeconds", row.get("timeoutSeconds"));
        int attempts = (int) longOf(row.get("attempts")) + 1;
        boolean success = postAndRecord(deliveryId, webhook, stringOf(row.get("eventId")),
                stringOf(row.get("eventType")), stringOf(row.get("payload")), attempts);
        Map<String, Object> view = deliveryView(deliveryId);
        view.put("success", success);
        return view;
    }

    /** 发送一次测试投递（用于验证签名与目标可达性；不依赖任何领域事件）。 */
    public Map<String, Object> sendTest(Long webhookId) {
        Map<String, Object> webhook = webhookMapper.selectForDelivery(webhookId);
        if (webhook == null) {
            throw new BusinessException("订阅不存在");
        }
        String eventId = "webhook.test:" + UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "TeamFlow Webhook 测试投递");
        data.put("webhookId", webhookId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("webhookId", webhookId);
        row.put("eventId", eventId);
        row.put("eventType", "webhook.test");
        row.put("payload", envelope("webhook.test", eventId, data));
        deliveryMapper.insertDelivery(row, currentUsername());
        Long deliveryId = numberAsLong(row.get("id"));
        boolean success = postAndRecord(deliveryId, webhook, eventId, "webhook.test",
                stringOf(row.get("payload")), 1);
        Map<String, Object> view = deliveryView(deliveryId);
        view.put("success", success);
        return view;
    }

    /** 某订阅最近投递记录（不含载荷，避免泄露业务内容）。 */
    public List<Map<String, Object>> listDeliveries(Long webhookId, Integer limit) {
        if (webhookMapper.selectById(webhookId) == null) {
            throw new BusinessException("订阅不存在");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = deliveryMapper.selectByWebhook(webhookId, size);
        rows.forEach(row -> row.remove("payload"));
        return rows;
    }

    // ------------------------------------------------------------------

    private boolean deliverOne(Map<String, Object> webhook, String eventType, String eventId,
                               Map<String, Object> data) {
        Long webhookId = numberAsLong(webhook.get("id"));
        String body = envelope(eventType, eventId, data);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("webhookId", webhookId);
        row.put("eventId", eventId);
        row.put("eventType", eventType);
        row.put("payload", body);
        try {
            deliveryMapper.insertDelivery(row, currentUsername());
        } catch (DuplicateKeyException exception) {
            log.info("Webhook 幂等跳过：webhookId={} eventId={}（同一事件已投递过）", webhookId, eventId);
            return false;
        }
        Long deliveryId = numberAsLong(row.get("id"));
        return postAndRecord(deliveryId, webhook, eventId, eventType, body, 1);
    }

    private boolean postAndRecord(Long deliveryId, Map<String, Object> webhook, String eventId,
                                  String eventType, String body, int attempts) {
        Long webhookId = numberAsLong(webhook.get("id"));
        String targetUrl = stringOf(webhook.get("targetUrl"));
        int timeout = timeoutOf(webhook.get("timeoutSeconds"));
        LocalDateTime now = LocalDateTime.now();
        String secret;
        try {
            secret = credentialCipher.decrypt(stringOf(webhook.get("secretCipher")));
        } catch (BusinessException exception) {
            return fail(webhookId, deliveryId, attempts, null,
                    "签名密钥不可用：" + exception.getMessage(), null, now);
        }
        long timestamp = System.currentTimeMillis();
        String signature = sign(secret, timestamp, body);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("User-Agent", "TeamFlow-Webhook/1.0")
                    .header("X-TeamFlow-Event", eventType)
                    .header("X-TeamFlow-Delivery", eventId)
                    .header("X-TeamFlow-Timestamp", String.valueOf(timestamp))
                    .header("X-TeamFlow-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            boolean success = status >= 200 && status < 300;
            String snippet = truncate(response.body(), 500);
            if (success) {
                deliveryMapper.updateResult(deliveryId, "success", status, attempts, null, null, snippet,
                        currentUsername());
                webhookMapper.updateDeliveryResult(webhookId, "success", "HTTP " + status, now, currentUsername());
                return true;
            }
            return fail(webhookId, deliveryId, attempts, status, "目标返回 HTTP " + status, snippet, now);
        } catch (IllegalArgumentException exception) {
            return fail(webhookId, deliveryId, attempts, null, "回调地址格式不合法", null, now);
        } catch (Exception exception) {
            String detail = exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : "：" + exception.getMessage());
            return fail(webhookId, deliveryId, attempts, null, "无法连接回调地址：" + detail, null, now);
        }
    }

    private boolean fail(Long webhookId, Long deliveryId, int attempts, Integer httpStatus,
                         String error, String responseSnippet, LocalDateTime now) {
        LocalDateTime nextRetryAt = now.plusSeconds(backoffSeconds(attempts));
        String message = truncate(error, 1000);
        deliveryMapper.updateResult(deliveryId, "failed", httpStatus, attempts, nextRetryAt,
                message, truncate(responseSnippet, 500), currentUsername());
        webhookMapper.updateDeliveryResult(webhookId, "failed", truncate("投递失败：" + message, 500),
                now, currentUsername());
        log.warn("Webhook 投递失败 webhookId={} deliveryId={} attempts={} error={}",
                webhookId, deliveryId, attempts, message);
        return false;
    }

    private long backoffSeconds(int attempts) {
        long seconds = 60L << Math.min(Math.max(attempts - 1, 0), 6);
        return Math.min(seconds, MAX_BACKOFF_SECONDS);
    }

    private Map<String, Object> deliveryView(Long deliveryId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deliveryId", deliveryId);
        Map<String, Object> row = deliveryMapper.selectForRetry(deliveryId);
        if (row != null) {
            view.put("webhookId", row.get("webhookId"));
            view.put("eventId", row.get("eventId"));
            view.put("eventType", row.get("eventType"));
            view.put("attempts", row.get("attempts"));
        }
        return view;
    }

    private String envelope(String eventType, String eventId, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("data", data == null ? Map.of() : data);
        return toJson(envelope);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException("投递载荷序列化失败：" + exception.getMessage());
        }
    }

    private String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new BusinessException("签名计算失败：" + exception.getMessage());
        }
    }

    private boolean matches(String eventTypes, String eventType) {
        if (eventTypes == null || eventTypes.isBlank()) {
            return false;
        }
        for (String item : eventTypes.split(",")) {
            String code = item.trim();
            if ("*".equals(code) || code.equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    private int timeoutOf(Object value) {
        long raw = longOf(value);
        if (raw <= 0) {
            return 3;
        }
        return (int) Math.min(raw, MAX_TIMEOUT_SECONDS);
    }


    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
