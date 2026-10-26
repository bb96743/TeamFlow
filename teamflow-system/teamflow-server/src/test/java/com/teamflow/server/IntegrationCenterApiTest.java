package com.teamflow.server;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M12 第三方集成中心集成测试（需求 2.4.1，真实 HTTP + teamflow_test 库）。
 *
 * <p>覆盖：凭据加密存储（密文入库、明文不回读）、连通性检测**不伪造**（无凭据/不可达都返回失败）、
 * 接口不返回凭据密文。测试自清理：只创建 {@code open_app} 测试行，{@code @AfterEach} 物理删除。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class IntegrationCenterApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdAppIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (createdAppIds.isEmpty()) {
            return;
        }
        String marks = String.join(",", Collections.nCopies(createdAppIds.size(), "?"));
        jdbcTemplate.update("DELETE FROM open_app WHERE id IN (" + marks + ")", createdAppIds.toArray());
        createdAppIds.clear();
    }

    @Test
    void credentialIsEncryptedAtRestAndNeverReturned() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appCode", "IT-M12-APP-" + suffix);
        body.put("appName", "集成测试应用" + suffix);
        body.put("appType", "github");
        body.put("endpointUrl", "https://example.com");
        body.put("configJson", "{\"owner\":\"m12\"}");
        body.put("credential", "ghp_secret_token_" + suffix);
        body.put("remark", "M12 集成测试");
        Long appId = dataNumber(post(admin, "/api/integration/apps", body));
        createdAppIds.add(appId);

        // 查库：只存密文，不含明文；提示只留末 4 位
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT app_code, credential_cipher, credential_hint FROM open_app WHERE id = ?", appId);
        String cipher = String.valueOf(stored.get("credential_cipher"));
        System.out.println("[M12] 凭据密文样例: " + cipher.substring(0, Math.min(48, cipher.length())) + "... (长度 " + cipher.length() + ")");
        assertThat(cipher).as("凭据应加密存储").startsWith("v1:");
        assertThat(cipher).as("密文不得包含明文").doesNotContain("ghp_secret_token_" + suffix);
        String hint = String.valueOf(stored.get("credential_hint"));
        assertThat(hint).startsWith("****").endsWith(suffix.substring(suffix.length() - 4));

        // 接口不返回密文
        ResponseEntity<Map<String, Object>> detailResponse = get(admin, "/api/integration/apps/" + appId);
        String raw = String.valueOf(detailResponse.getBody());
        assertThat(raw).as("接口不得返回凭据密文").doesNotContain("credentialCipher");
        assertThat(raw).as("接口不得返回凭据明文").doesNotContain("ghp_secret_token_" + suffix);
        Map<String, Object> detail = dataOf(detailResponse);
        assertThat(detail).containsEntry("credentialConfigured", true);

        Map<String, Object> encryption = dataOf(get(admin, "/api/integration/apps/encryption-status"));
        assertThat(encryption).containsEntry("credentialEncryptionConfigured", true);
    }

    @Test
    void connectivityCheckDoesNotPretendSuccess() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // ① 无凭据 → 明确失败
        Map<String, Object> noCredential = new LinkedHashMap<>();
        noCredential.put("appCode", "IT-M12-NOCRED-" + suffix);
        noCredential.put("appName", "无凭据应用" + suffix);
        noCredential.put("appType", "dingtalk");
        noCredential.put("endpointUrl", "https://example.com");
        Long noCredentialId = dataNumber(post(admin, "/api/integration/apps", noCredential));
        createdAppIds.add(noCredentialId);
        Map<String, Object> check1 = dataOf(post(admin,
                "/api/integration/apps/" + noCredentialId + "/check", Map.of()));
        assertThat(check1).containsEntry("success", false).containsEntry("result", "failed");
        assertThat(String.valueOf(check1.get("message"))).contains("未配置凭据");

        // ② 有凭据但目标不可达 → 明确失败（不伪造联通）
        Map<String, Object> unreachable = new LinkedHashMap<>();
        unreachable.put("appCode", "IT-M12-DOWN-" + suffix);
        unreachable.put("appName", "不可达应用" + suffix);
        unreachable.put("appType", "wecom");
        unreachable.put("endpointUrl", "http://127.0.0.1:1/health");
        unreachable.put("credential", "token_" + suffix);
        Long unreachableId = dataNumber(post(admin, "/api/integration/apps", unreachable));
        createdAppIds.add(unreachableId);
        Map<String, Object> check2 = dataOf(post(admin,
                "/api/integration/apps/" + unreachableId + "/check", Map.of()));
        assertThat(check2).containsEntry("success", false).containsEntry("result", "failed");
        assertThat(String.valueOf(check2.get("message"))).contains("无法连接");

        // 结果落库（失败也留痕）
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT last_check_result, last_check_message FROM open_app WHERE id = ?", unreachableId);
        assertThat(String.valueOf(stored.get("last_check_result"))).isEqualTo("failed");
        assertThat(String.valueOf(stored.get("last_check_message"))).contains("无法连接");

        // 列表可见（凭据状态为已配置，但内容不返回）
        Map<String, Object> page = dataOf(get(admin, "/api/integration/apps?keyword=IT-M12&pageNum=1&pageSize=50"));
        List<Map<String, Object>> rows = listValue(page.get("rows"));
        assertThat(rows).anySatisfy(row -> assertThat(row.get("appCode")).isEqualTo("IT-M12-DOWN-" + suffix));
        assertThat(String.valueOf(page)).doesNotContain("credentialCipher");
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
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

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
