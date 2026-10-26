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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M12 开放 API 集成测试（需求 2.4.3，真实 HTTP + teamflow_test 库）。
 *
 * <p>覆盖：API Key <b>只存哈希</b>、Key 绑定用户与权限范围、开放接口与内部接口<b>双向隔离</b>、
 * scope/用户权限越权 403、撤销即时失效、调用审计落库、白名单写（任务认领）。
 * 测试自清理：open_api_key / open_event_log / 任务 / 临时用户与角色全部回收。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class OpenApiIsolationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> keyIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private Long restrictedRoleId;
    private Long restrictedUserId;

    @AfterEach
    void cleanUp() {
        if (!keyIds.isEmpty()) {
            String marks = String.join(",", Collections.nCopies(keyIds.size(), "?"));
            jdbcTemplate.update("DELETE FROM open_event_log WHERE key_id IN (" + marks + ")", keyIds.toArray());
            jdbcTemplate.update("DELETE FROM open_api_key WHERE id IN (" + marks + ")", keyIds.toArray());
            keyIds.clear();
        }
        for (Long taskId : taskIds) {
            jdbcTemplate.update("DELETE FROM task_assignee WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM task_relation WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_instance WHERE biz_id = ? AND instance_type = 'status'", taskId);
            jdbcTemplate.update("DELETE FROM task_task WHERE id = ?", taskId);
        }
        taskIds.clear();
        if (restrictedUserId != null) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", restrictedUserId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", restrictedUserId);
            restrictedUserId = null;
        }
        if (restrictedRoleId != null) {
            jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", restrictedRoleId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", restrictedRoleId);
            restrictedRoleId = null;
        }
    }

    @Test
    void keyIsHashedAndChannelsAreIsolated() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        Map<String, Object> keyBody = new LinkedHashMap<>();
        keyBody.put("keyName", "IT-M12-Key-" + suffix);
        keyBody.put("scope", "task:task:list,okr:objective:list");
        Map<String, Object> created = dataOf(post(admin, "/api/integration/keys", keyBody));
        Long keyId = number(created.get("id"));
        String plainKey = String.valueOf(created.get("plainKey"));
        keyIds.add(keyId);
        assertThat(plainKey).startsWith("tfk_");

        // ① 查库证明：只存哈希与前缀，不存明文
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT key_hash, key_prefix FROM open_api_key WHERE id = ?", keyId);
        System.out.println("[M12] API Key 仅存哈希: prefix=" + stored.get("key_prefix") + ", sha256=" + stored.get("key_hash") + ", plain_len=" + plainKey.length());
        assertThat(String.valueOf(stored.get("key_hash"))).isEqualTo(sha256Hex(plainKey));
        assertThat(String.valueOf(stored.get("key_prefix"))).isEqualTo(plainKey.substring(0, 12));
        Integer plainRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM open_api_key WHERE key_hash = ?", Integer.class, plainKey);
        assertThat(plainRows).as("库中不得存在明文密钥").isZero();
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'open_api_key'", String.class);
        assertThat(columns).doesNotContain("plain_key", "raw_key", "secret");
        String listRaw = String.valueOf(get(admin, "/api/integration/keys?keyword=IT-M12").getBody());
        assertThat(listRaw).as("列表不得返回哈希或明文").doesNotContain(plainKey)
                .doesNotContain(String.valueOf(stored.get("key_hash")));

        // ② 开放接口：X-API-Key 可访问（真实数据）
        ResponseEntity<Map<String, Object>> open = getWithApiKey(plainKey, "/open/v1/tasks?pageSize=3");
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(open.getBody()).containsEntry("code", 200);

        // ③ 隔离 A：API Key 访问内部 /api/** 必须失败
        ResponseEntity<Map<String, Object>> keyToInternal = getWithApiKey(plainKey, "/api/task/tasks");
        assertThat(keyToInternal.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ③ 隔离 B：内部 JWT 访问 /open/v1/** 必须失败（开放通道只认 API Key）
        ResponseEntity<Map<String, Object>> jwtToOpen = get(admin, "/open/v1/tasks");
        assertThat(jwtToOpen.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ④ scope 越权：insight:metric:list 不在 Key 的 scope 内
        ResponseEntity<Map<String, Object>> scopeDenied = getWithApiKey(plainKey, "/open/v1/insights/metrics");
        assertThat(scopeDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ⑤ 撤销后立即失效
        assertThat(post(admin, "/api/integration/keys/" + keyId + "/revoke", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> afterRevoke = getWithApiKey(plainKey, "/open/v1/tasks");
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ⑥ 审计：200 与 403 均留痕（撤销后的 401 在认证层拦截，不入拦截器）
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT path, status FROM open_event_log WHERE key_id = ? AND channel = 'open_api' ORDER BY id", keyId);
        assertThat(audits).isNotEmpty();
        assertThat(audits).anySatisfy(row -> assertThat(number(row.get("status"))).isEqualTo(200));
        assertThat(audits).anySatisfy(row -> assertThat(number(row.get("status"))).isEqualTo(403));
    }

    @Test
    void keyBoundUserPermissionIsEnforcedAndWhitelistWriteWorks() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        restrictedRoleId = insertRestrictedRole(suffix);
        restrictedUserId = insertUser("it_m12_limited_" + suffix, "M12 受限用户" + suffix, restrictedRoleId);

        // ① 绑定受限用户、scope 为空（继承用户权限）：调 OKR 开放接口 → 403（用户无该权限）
        Map<String, Object> limitedKeyBody = new LinkedHashMap<>();
        limitedKeyBody.put("keyName", "IT-M12-Limited-" + suffix);
        limitedKeyBody.put("userId", restrictedUserId);
        Map<String, Object> limited = dataOf(post(admin, "/api/integration/keys", limitedKeyBody));
        Long limitedKeyId = number(limited.get("id"));
        keyIds.add(limitedKeyId);
        String limitedPlain = String.valueOf(limited.get("plainKey"));
        ResponseEntity<Map<String, Object>> denied = getWithApiKey(limitedPlain, "/open/v1/objectives");
        assertThat(denied.getStatusCode()).as("绑定用户缺权限应 403").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getWithApiKey(limitedPlain, "/open/v1/tasks?pageSize=1").getStatusCode())
                .as("绑定用户有 task 权限则可访问").isEqualTo(HttpStatus.OK);

        // ② 白名单写：管理员的 key（含 task:task:assign）认领一个待认领任务
        Map<String, Object> writeKeyBody = new LinkedHashMap<>();
        writeKeyBody.put("keyName", "IT-M12-Write-" + suffix);
        writeKeyBody.put("scope", "task:task:list,task:task:assign");
        Map<String, Object> writeKey = dataOf(post(admin, "/api/integration/keys", writeKeyBody));
        Long writeKeyId = number(writeKey.get("id"));
        keyIds.add(writeKeyId);
        String writePlain = String.valueOf(writeKey.get("plainKey"));

        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("taskTitle", "M12 开放 API 认领任务" + suffix);
        taskBody.put("taskDesc", "白名单写测试");
        taskBody.put("priority", "normal");
        Long taskId = number(dataOf(post(admin, "/api/task/tasks", taskBody)).get("id"));
        taskIds.add(taskId);
        String beforeStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM task_task WHERE id = ?", String.class, taskId);
        assertThat(beforeStatus).isEqualTo("pool");

        ResponseEntity<Map<String, Object>> claim = restTemplate.exchange(
                "/open/v1/tasks/" + taskId + "/claim", HttpMethod.POST,
                new HttpEntity<>(apiKeyHeaders(writePlain)), MAP_TYPE);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        String afterStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM task_task WHERE id = ?", String.class, taskId);
        assertThat(afterStatus).as("白名单写：认领后离开待认领池").isNotEqualTo("pool");

        // ③ 白名单外写：创建任务没有开放端点（404），确保不暴露内部写接口
        ResponseEntity<Map<String, Object>> createViaOpen = restTemplate.exchange(
                "/open/v1/tasks", HttpMethod.POST,
                new HttpEntity<>(Map.of("taskTitle", "不应创建"), apiKeyHeaders(writePlain)), MAP_TYPE);
        assertThat(createViaOpen.getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private Long insertUser(String username, String nickname, Long roleId) {
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE id = 1", String.class);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by) "
                            + "VALUES (?, ?, ?, 5, 1, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, nickname);
            return statement;
        }, holder);
        Long userId = holder.getKey().longValue();
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return userId;
    }

    private Long insertRestrictedRole(String suffix) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_role (role_name, role_code, data_scope, sort, status, builtin, create_by, update_by) "
                            + "VALUES (?, ?, 'SELF', 99, 1, 0, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "M12 仅任务查看角色" + suffix);
            statement.setString(2, "it_m12_limited_" + suffix);
            return statement;
        }, holder);
        Long roleId = holder.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (role_id, menu_id)
                SELECT ?, id FROM sys_menu WHERE menu_type = 'btn' AND permission = 'task:task:list'
                """, roleId);
        return roleId;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private HttpHeaders adminHeaders() {
        return bearerHeaders(TestAuthClient.login(restTemplate, "admin", "admin123"));
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders apiKeyHeaders(String plainKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", plainKey);
        return headers;
    }

    private ResponseEntity<Map<String, Object>> get(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> getWithApiKey(String plainKey, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(apiKeyHeaders(plainKey)), MAP_TYPE);
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

    private Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
