package com.teamflow.server;

import com.sun.net.httpserver.HttpServer;
import com.teamflow.integration.service.WebhookDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 事件外发集成测试（2.4.4，真实 HTTP 投递 + 本地监听 + teamflow_test_p1a 独立库）。
 *
 * <p>覆盖：任务创建事件 → 本地监听收到带签名请求 → HMAC 验签通过；密钥加密存储且响应不返明文；
 * 幂等去重；目标不可达时失败留痕并可重试。测试自清理。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class WebhookApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> webhookIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;

    @AfterEach
    void cleanUp() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        for (Long webhookId : webhookIds) {
            jdbcTemplate.update("DELETE FROM open_webhook_delivery WHERE webhook_id = ?", webhookId);
            jdbcTemplate.update("DELETE FROM open_webhook WHERE id = ?", webhookId);
        }
        webhookIds.clear();
        for (Long taskId : taskIds) {
            jdbcTemplate.update("DELETE FROM task_assignee WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_instance WHERE biz_id = ? AND instance_type = 'status'", taskId);
            jdbcTemplate.update("DELETE FROM task_task WHERE id = ?", taskId);
        }
        taskIds.clear();
    }

    @Test
    void taskCreationDeliversSignedWebhookAndIsIdempotent() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        String secret = "teamflow-webhook-test-secret-" + suffix;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedEvent = new AtomicReference<>();
        AtomicReference<String> receivedDelivery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-TeamFlow-Signature"));
            receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-TeamFlow-Timestamp"));
            receivedEvent.set(exchange.getRequestHeaders().getFirst("X-TeamFlow-Event"));
            receivedDelivery.set(exchange.getRequestHeaders().getFirst("X-TeamFlow-Delivery"));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
            latch.countDown();
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("webhookCode", "IT-M12-WH-" + suffix);
        body.put("webhookName", "GitHub 回调" + suffix);
        body.put("targetUrl", "http://127.0.0.1:" + port + "/hook");
        body.put("eventTypes", "task.created,task.status_changed");
        body.put("secret", secret);
        body.put("timeoutSeconds", 3);
        ResponseEntity<Map<String, Object>> created = post(admin, "/api/integration/webhooks", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long webhookId = number(created.getBody().get("data"));
        webhookIds.add(webhookId);

        // ① 密钥加密存储，响应不返回明文
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT secret_cipher, secret_hint FROM open_webhook WHERE id = ?", webhookId);
        String cipher = String.valueOf(stored.get("secret_cipher"));
        assertThat(cipher).startsWith("v1:").doesNotContain(secret);
        String listRaw = String.valueOf(get(admin, "/api/integration/webhooks?keyword=IT-M12").getBody());
        assertThat(listRaw).doesNotContain(secret).doesNotContain("secretCipher");

        // ② 触发任务创建 → 事件外发（AFTER_COMMIT 同线程投递）
        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("taskTitle", "Webhook 测试任务 " + suffix);
        taskBody.put("taskDesc", "验证签名投递");
        taskBody.put("priority", "high");
        Long taskId = number(dataOf(post(admin, "/api/task/tasks", taskBody)).get("id"));
        taskIds.add(taskId);
        assertThat(latch.await(8, TimeUnit.SECONDS)).as("本地监听应收到 Webhook 投递").isTrue();

        System.out.println("[WEBHOOK] event-header=" + receivedEvent.get()
                + " timestamp=" + receivedTimestamp.get()
                + " signature=" + receivedSignature.get());
        System.out.println("[WEBHOOK] payload=" + receivedBody.get());

        assertThat(receivedEvent.get()).isEqualTo("task.created");
        assertThat(receivedDelivery.get()).isNotBlank();
        assertThat(receivedTimestamp.get()).matches("\\d+");
        assertThat(receivedSignature.get()).startsWith("sha256=");
        String expected = hmacHex(secret, receivedTimestamp.get() + "." + receivedBody.get());
        assertThat(receivedSignature.get()).isEqualTo("sha256=" + expected);
        JsonNode payload = objectMapper.readTree(receivedBody.get());
        assertThat(payload.path("type").asString()).isEqualTo("task.created");
        assertThat(payload.path("id").asString()).isNotBlank();
        assertThat(payload.path("data").path("taskId").asLong()).isEqualTo(taskId);
        assertThat(payload.path("data").path("fromStatus").isNull()).isTrue();

        // ③ 投递留痕 success
        Map<String, Object> delivery = jdbcTemplate.queryForMap(
                "SELECT status, http_status, attempts FROM open_webhook_delivery WHERE webhook_id = ?", webhookId);
        assertThat(String.valueOf(delivery.get("status"))).isEqualTo("success");
        assertThat(number(delivery.get("http_status"))).isEqualTo(200);
        Map<String, Object> last = jdbcTemplate.queryForMap(
                "SELECT last_delivery_status FROM open_webhook WHERE id = ?", webhookId);
        assertThat(String.valueOf(last.get("last_delivery_status"))).isEqualTo("success");

        // ④ 幂等：同一事件重复投递被去重
        String eventId = "idempotent-" + suffix;
        int first = deliveryService.deliver("task.created", eventId, Map.of("taskId", taskId));
        int second = deliveryService.deliver("task.created", eventId, Map.of("taskId", taskId));
        System.out.println("[WEBHOOK] 幂等验证 first=" + first + " second=" + second);
        assertThat(first).isPositive();
        assertThat(second).isZero();
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM open_webhook_delivery WHERE webhook_id = ? AND event_id = ?",
                Integer.class, webhookId, eventId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void unreachableTargetRecordsFailureAndCanRetry() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("webhookCode", "IT-M12-DOWN-" + suffix);
        body.put("webhookName", "不可达回调" + suffix);
        body.put("targetUrl", "http://127.0.0.1:1/hook");
        body.put("eventTypes", "task.created");
        body.put("secret", "teamflow-webhook-secret-" + suffix);
        Long webhookId = dataNumber(post(admin, "/api/integration/webhooks", body));
        webhookIds.add(webhookId);

        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("taskTitle", "Webhook 不可达任务 " + suffix);
        taskBody.put("priority", "normal");
        Long taskId = number(dataOf(post(admin, "/api/task/tasks", taskBody)).get("id"));
        taskIds.add(taskId);

        Map<String, Object> delivery = jdbcTemplate.queryForMap(
                "SELECT id, status, attempts, error_message, next_retry_at FROM open_webhook_delivery"
                        + " WHERE webhook_id = ? ORDER BY id DESC LIMIT 1", webhookId);
        System.out.println("[WEBHOOK] 不可达投递留痕=" + delivery);
        assertThat(String.valueOf(delivery.get("status"))).isEqualTo("failed");
        assertThat(String.valueOf(delivery.get("error_message"))).contains("无法连接回调地址");
        assertThat(delivery.get("next_retry_at")).isNotNull();
        Long deliveryId = number(delivery.get("id"));

        ResponseEntity<Map<String, Object>> retried = post(admin,
                "/api/integration/webhooks/deliveries/" + deliveryId + "/retry", Map.of());
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> view = dataOf(retried);
        System.out.println("[WEBHOOK] 重试结果=" + view);
        assertThat(view).containsEntry("success", false);
        assertThat(number(view.get("attempts"))).isEqualTo(2);
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, attempts FROM open_webhook_delivery WHERE id = ?", deliveryId);
        assertThat(String.valueOf(after.get("status"))).isEqualTo("failed");
        assertThat(number(after.get("attempts"))).isEqualTo(2);
    }

    // ------------------------------------------------------------------

    private HttpHeaders adminHeaders() {
        return bearerHeaders(TestAuthClient.login(restTemplate, "admin", "admin123"));
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<Map<String, Object>> get(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> post(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private Long dataNumber(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return number(response.getBody().get("data"));
    }

    private Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String hmacHex(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
