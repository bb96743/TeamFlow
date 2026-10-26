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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M12 MCP Server 集成测试（需求 2.4.2，真实 HTTP JSON-RPC 探测 + teamflow_test 库）。
 *
 * <p>覆盖：MCP Server 可连接、工具清单（白名单只读）、调用只读工具拿到真实数据、
 * 低权限上下文调用高权限工具<b>被拒</b>（以调用方身份执行，禁止越权）、未携带 API Key 401。
 * 测试自清理：临时 API Key / 任务 / 用户与角色 / 审计行全部回收。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class McpServerApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
    void mcpServerListsWhitelistToolsAndCallsRealData() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // 真实数据：一条随机后缀的任务
        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("taskTitle", "MCP 探测任务 " + suffix);
        taskBody.put("taskDesc", "MCP 只读工具真实取数");
        taskBody.put("priority", "high");
        Long taskId = number(dataOf(post(admin, "/api/task/tasks", taskBody)).get("id"));
        taskIds.add(taskId);

        // 高权限 Key（含任务/目标/知识/项目查询）
        Map<String, Object> keyBody = new LinkedHashMap<>();
        keyBody.put("keyName", "IT-M12-MCP-" + suffix);
        keyBody.put("scope", "task:task:list,okr:objective:list,project:project:list,knowledge:page:list");
        Map<String, Object> key = dataOf(post(admin, "/api/integration/keys", keyBody));
        Long keyId = number(key.get("id"));
        String plainKey = String.valueOf(key.get("plainKey"));
        keyIds.add(keyId);

        // ① initialize → 拿到会话（streamable HTTP 会返回 Mcp-Session-Id）
        Rpc init = rpc(plainKey, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"teamflow-mcp-probe","version":"1.0"}}}
                """.formatted(PROTOCOL_VERSION));
        System.out.println("[MCP] initialize status=" + init.status() + " session=" + init.sessionId()
                + " body=" + abbreviate(init.body()));
        assertThat(init.status()).isEqualTo(200);
        assertThat(init.json()).isNotNull();
        assertThat(init.json().path("result").path("serverInfo").path("name").asString()).isNotBlank();

        String session = init.sessionId();
        // ② initialized 通知
        Rpc notified = rpc(plainKey, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(notified.status()).isIn(200, 202, 204);

        // ③ tools/list → 白名单工具清单
        Rpc list = rpc(plainKey, session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(list.status()).isEqualTo(200);
        JsonNode tools = list.json().path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        List<String> toolNames = new ArrayList<>();
        tools.forEach(tool -> toolNames.add(tool.path("name").asString()));
        System.out.println("[MCP] tools/list = " + toolNames);
        assertThat(toolNames).containsExactlyInAnyOrder(
                "task_query", "objective_query", "kr_query", "perf_query",
                "knowledge_search", "project_query", "insight_metrics");
        // 只读白名单：清单里不存在任何写工具
        assertThat(toolNames).noneMatch(name -> name.contains("create") || name.contains("update")
                || name.contains("delete") || name.contains("write") || name.contains("assign"));

        // ④ tools/call task_query → 真实数据
        Rpc call = rpc(plainKey, session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"task_query","arguments":{"keyword":"%s","limit":5}}}
                """.formatted(suffix));
        assertThat(call.status()).isEqualTo(200);
        String text = toolText(call.json());
        System.out.println("[MCP] tools/call task_query = " + abbreviate(text));
        assertThat(text).contains("MCP 探测任务 " + suffix);
        assertThat(call.json().path("result").path("isError").asBoolean(false)).isFalse();

        // ⑤ 审计：MCP 工具调用留痕
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM open_event_log WHERE key_id = ? AND channel = 'mcp' AND path = 'task_query'",
                Integer.class, keyId);
        assertThat(auditRows).isPositive();
    }

    @Test
    void lowPermissionContextCannotCallHigherPermissionTool() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        restrictedRoleId = insertRestrictedRole(suffix);
        restrictedUserId = insertUser("it_m12_mcp_limited_" + suffix, "MCP 受限用户" + suffix, restrictedRoleId);

        // 低权限 Key：绑定用户只有任务查看权限，scope 也只有任务查看
        Map<String, Object> keyBody = new LinkedHashMap<>();
        keyBody.put("keyName", "IT-M12-MCP-Limited-" + suffix);
        keyBody.put("userId", restrictedUserId);
        keyBody.put("scope", "task:task:list");
        Map<String, Object> key = dataOf(post(admin, "/api/integration/keys", keyBody));
        keyIds.add(number(key.get("id")));
        String plainKey = String.valueOf(key.get("plainKey"));

        Rpc init = rpc(plainKey, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"teamflow-mcp-probe","version":"1.0"}}}
                """.formatted(PROTOCOL_VERSION));
        assertThat(init.status()).isEqualTo(200);
        String session = init.sessionId();
        rpc(plainKey, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        // 低权限上下文调用高权限工具 objective_query（需要 okr:objective:list）→ 被拒
        Rpc denied = rpc(plainKey, session, """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
                  "name":"objective_query","arguments":{"limit":5}}}
                """);
        String deniedText = toolText(denied.json());
        System.out.println("[MCP] 越权调用 objective_query status=" + denied.status() + " text=" + abbreviate(deniedText));
        boolean rejected = denied.json().path("result").path("isError").asBoolean(false)
                || denied.json().has("error")
                || deniedText.contains("权限")
                || deniedText.contains("拒绝")
                || deniedText.contains("AccessDenied");
        assertThat(rejected).as("低权限上下文调用高权限工具必须被拒（不越权）").isTrue();

        // 同一低权限上下文调用自己权限内的 task_query 正常（证明不是一刀切失败）
        Rpc allowed = rpc(plainKey, session, """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
                  "name":"task_query","arguments":{"limit":3}}}
                """);
        assertThat(allowed.status()).isEqualTo(200);
        assertThat(allowed.json().path("result").path("isError").asBoolean(false)).isFalse();
    }

    @Test
    void mcpEndpointRequiresApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> response = restTemplate.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // JSON-RPC 探测
    // ------------------------------------------------------------------

    private Rpc rpc(String plainKey, String sessionId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", plainKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        ResponseEntity<String> response = restTemplate.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        String session = response.getHeaders().getFirst("Mcp-Session-Id");
        String payload = extractJson(response.getBody());
        JsonNode json = null;
        if (payload != null && !payload.isBlank() && payload.trim().startsWith("{")) {
            try {
                json = objectMapper.readTree(payload);
            } catch (RuntimeException exception) {
                json = null;
            }
        }
        return new Rpc(response.getStatusCode().value(), session, response.getBody(), json);
    }

    /** 从可能为 SSE（{@code data: {...}}）或纯 JSON 的响应体中提取 JSON。 */
    private String extractJson(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : trimmed.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("data:")) {
                builder.append(value.substring("data:".length()).trim());
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String toolText(JsonNode json) {
        if (json == null) {
            return "";
        }
        JsonNode content = json.path("result").path("content");
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            content.forEach(item -> builder.append(item.path("text").asString()));
            return builder.toString();
        }
        return json.path("error").path("message").asString();
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 600 ? value : value.substring(0, 600) + "…";
    }

    private record Rpc(int status, String sessionId, String body, JsonNode json) {
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
            statement.setString(1, "M12 MCP 仅任务查看角色" + suffix);
            statement.setString(2, "it_m12_mcp_limited_" + suffix);
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

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
