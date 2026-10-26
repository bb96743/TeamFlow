package com.teamflow.server;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.intent.LlmIntentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 AI 助手集成测试（真实 HTTP + 规则意图 + 工具以调用者身份执行；测试库 teamflow_test，自清理）。
 *
 * <p>覆盖：任务/绩效工具路由真实取数、无 OKR 权限越权拒绝、知识问答引用溯源、
 * 日报草稿不可发布、会话上下文与归属隔离、规则实现默认启用、RAG 不引向量库与跨模块边界扫描。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class AiApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LlmIntentResolver llmIntentResolver;

    @Autowired
    private AiProperties aiProperties;

    private String suffix;
    private String adminToken;
    private Long adminId;
    private HttpHeaders admin;
    private Long zhangSanId;
    private Long restrictedUserId;
    private Long restrictedRoleId;
    private Long taskId;
    private Long spaceId;
    private Long pageId;
    private Long perfTemplateId;
    private Long perfCycleId;
    private Long perfResultId;
    private final List<Long> sessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());
        admin = adminHeaders();
        adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);

        // ① 被查询员工：昵称「张三」，供规则槽位与服务数据定位
        zhangSanId = insertUser("ai_zhangsan_" + suffix, "张三");

        // ② 只有「任务查询 + 使用助手」权限的用户，用于越权验证
        restrictedRoleId = insertRestrictedRole();
        restrictedUserId = insertUser("ai_limited_" + suffix, "受限用户" + suffix);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                restrictedUserId, restrictedRoleId);

        // ③ 张三的任务（本周内）
        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("taskTitle", "AI 路由测试任务" + suffix);
        taskBody.put("assigneeId", zhangSanId);
        taskBody.put("startDate", LocalDate.now().toString());
        taskBody.put("dueDate", LocalDate.now().toString());
        taskBody.put("priority", "normal");
        taskId = idOf(post(admin, "/api/task/tasks", taskBody));

        // ④ 张三的绩效结果（直接落测试夹具，工具经 M05 Service 读）
        perfTemplateId = insertPerfTemplate();
        perfCycleId = insertPerfCycle(perfTemplateId);
        perfResultId = insertPerfResult(perfCycleId, perfTemplateId, zhangSanId);

        // ⑤ 知识页面（用于带溯源的知识问答）
        Map<String, Object> spaceBody = new LinkedHashMap<>();
        spaceBody.put("spaceName", "AI 测试知识库" + suffix);
        spaceBody.put("spaceCode", "ai-kb-" + suffix);
        spaceBody.put("visibility", "internal");
        spaceBody.put("status", "active");
        spaceId = idOf(post(admin, "/api/knowledge/spaces", spaceBody));
        Map<String, Object> pageBody = new LinkedHashMap<>();
        pageBody.put("spaceId", spaceId);
        pageBody.put("nodeId", 0);
        pageBody.put("pageTitle", "联调窗口说明" + suffix);
        pageBody.put("pageType", "wiki");
        pageBody.put("content", "# 联调窗口说明\n联调窗口提前到本周五，目标对齐检查下周复盘。\n风险：外部依赖延期时顺延一天。");
        pageBody.put("visibility", "inherit");
        pageBody.put("status", "published");
        pageId = idOf(post(admin, "/api/knowledge/pages", pageBody));
    }

    @AfterEach
    void cleanUp() {
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM ai_tool_call WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM ai_message WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM ai_session WHERE id = ?", sessionId);
        }
        sessionIds.clear();
        jdbcTemplate.update("DELETE FROM ai_tool_call WHERE operator_id IN (?, ?)", adminId, restrictedUserId);
        jdbcTemplate.update("DELETE FROM ai_message WHERE session_id IN (SELECT id FROM ai_session WHERE owner_id IN (?, ?))",
                adminId, restrictedUserId);
        jdbcTemplate.update("DELETE FROM ai_session WHERE owner_id IN (?, ?)", adminId, restrictedUserId);
        jdbcTemplate.update("DELETE FROM ai_report_draft WHERE owner_id IN (?, ?)", adminId, restrictedUserId);

        if (pageId != null) {
            jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'page' AND resource_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_page_version WHERE page_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_file WHERE page_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_page WHERE id = ?", pageId);
        }
        if (spaceId != null) {
            jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'space' AND resource_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM knowledge_node WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM knowledge_space WHERE id = ?", spaceId);
        }
        if (taskId != null) {
            jdbcTemplate.update("DELETE FROM task_task WHERE id = ?", taskId);
        }
        if (perfResultId != null) {
            jdbcTemplate.update("DELETE FROM perf_result WHERE id = ?", perfResultId);
        }
        if (perfCycleId != null) {
            jdbcTemplate.update("DELETE FROM perf_cycle WHERE id = ?", perfCycleId);
        }
        if (perfTemplateId != null) {
            jdbcTemplate.update("DELETE FROM perf_template WHERE id = ?", perfTemplateId);
        }
        for (Long userId : new Long[]{zhangSanId, restrictedUserId}) {
            if (userId != null) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
            }
        }
        if (restrictedRoleId != null) {
            jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", restrictedRoleId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", restrictedRoleId);
        }
    }

    // ------------------------------------------------------------------
    // ① 工具路由：任务（真实数据）
    // ------------------------------------------------------------------

    @Test
    void ruleRoutesTaskQueryToTaskToolWithRealData() {
        Map<String, Object> result = chat(admin, "帮我查张三本周的任务", null);
        Map<String, Object> intent = mapValue(result.get("intent"));
        Map<String, Object> tool = mapValue(result.get("tool"));

        assertThat(String.valueOf(result.get("executor"))).isEqualTo("rule");
        assertThat(intent).containsEntry("code", "task_query");
        assertThat(tool).containsEntry("name", "task.query").containsEntry("allowed", true);
        assertThat(mapValue(tool.get("params"))).containsEntry("employeeName", "张三");
        assertThat(String.valueOf(result.get("answer"))).contains("AI 路由测试任务" + suffix);
        assertThat(String.valueOf(result.get("status"))).isEqualTo("ok");

        Long sessionId = number(result.get("sessionId"));
        sessionIds.add(sessionId);
        Map<String, Object> call = jdbcTemplate.queryForMap("""
                SELECT tool_name, allowed, permission_code, operator_name
                FROM ai_tool_call WHERE session_id = ? ORDER BY id DESC LIMIT 1
                """, sessionId);
        assertThat(String.valueOf(call.get("tool_name"))).isEqualTo("task.query");
        assertThat(((Number) call.get("allowed")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(call.get("permission_code"))).isEqualTo("task:task:list");
    }

    // ------------------------------------------------------------------
    // ② 工具路由：绩效（真实数据）
    // ------------------------------------------------------------------

    @Test
    void ruleRoutesPerfQueryToPerfToolWithRealData() {
        Map<String, Object> result = chat(admin, "查张三的绩效", null);
        Map<String, Object> intent = mapValue(result.get("intent"));
        Map<String, Object> tool = mapValue(result.get("tool"));

        assertThat(intent).containsEntry("code", "perf_query");
        assertThat(tool).containsEntry("name", "perf.query").containsEntry("allowed", true);
        assertThat(String.valueOf(result.get("answer"))).contains("AI 绩效测试周期" + suffix).contains("92.50");
        Long sessionId = number(result.get("sessionId"));
        sessionIds.add(sessionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_tool_call
                WHERE session_id = ? AND tool_name = 'perf.query' AND allowed = 1
                """, Integer.class, sessionId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ③ 越权防护：只有 task 权限，无 okr 权限
    // ------------------------------------------------------------------

    @Test
    void okrQueryIsDeniedForUserWithoutPermission() {
        HttpHeaders limited = bearerHeaders(login("ai_limited_" + suffix, "admin123"));

        Map<String, Object> result = chat(limited, "查一下公司的 OKR 目标", null);
        Map<String, Object> tool = mapValue(result.get("tool"));
        assertThat(String.valueOf(result.get("status"))).isEqualTo("denied");
        assertThat(tool).containsEntry("name", "okr.query").containsEntry("allowed", false);
        assertThat(String.valueOf(result.get("answer"))).contains("无权限").contains("未读取");
        assertThat(String.valueOf(result.get("citations"))).isEqualTo("[]");

        Long sessionId = number(result.get("sessionId"));
        sessionIds.add(sessionId);
        Map<String, Object> denied = jdbcTemplate.queryForMap("""
                SELECT allowed, permission_code FROM ai_tool_call
                WHERE session_id = ? ORDER BY id DESC LIMIT 1
                """, sessionId);
        assertThat(((Number) denied.get("allowed")).intValue()).isZero();
        assertThat(String.valueOf(denied.get("permission_code"))).isEqualTo("okr:objective:list");

        // 同一用户直接调 OKR 接口同样被拒，证明助手没有绕过鉴权
        assertThat(get(limited, "/api/okr/objectives").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 该用户有任务权限：任务工具可正常执行（对照）
        Map<String, Object> allowed = chat(limited, "帮我查我的任务", null);
        sessionIds.add(number(allowed.get("sessionId")));
        assertThat(mapValue(allowed.get("tool"))).containsEntry("name", "task.query").containsEntry("allowed", true);
    }

    // ------------------------------------------------------------------
    // ④ 知识问答：引用溯源来自 M07 检索
    // ------------------------------------------------------------------

    @Test
    void knowledgeQaReturnsCitationsFromM07Search() {
        Map<String, Object> qaBody = Map.of("question", "联调窗口提前");
        Map<String, Object> result = dataOf(post(admin, "/api/ai/kb/qa", qaBody));
        List<Map<String, Object>> citations = rowsValue(result.get("citations"));

        assertThat(citations).isNotEmpty();
        Map<String, Object> first = citations.get(0);
        assertThat(number(first.get("pageId"))).isEqualTo(pageId);
        assertThat(String.valueOf(first.get("pageTitle"))).contains("联调窗口说明" + suffix);
        assertThat(String.valueOf(first.get("snippet"))).isNotBlank();
        assertThat(((Number) first.get("offset")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(String.valueOf(result.get("engine"))).isIn(
                "fulltext-ngram", "fulltext-miss-like-fallback", "like-short-keyword", "like-fallback");
        assertThat(String.valueOf(result.get("answer"))).contains("联调窗口");

        // M07 页面正文确实存在（引用不是编造）
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM knowledge_page WHERE id = ?", String.class, pageId);
        assertThat(content).contains("联调窗口提前到本周五");

        Map<String, Object> message = jdbcTemplate.queryForMap(
                "SELECT citations FROM ai_message WHERE session_id = ? AND role = 'assistant' ORDER BY id DESC LIMIT 1",
                number(result.get("sessionId")));
        assertThat(String.valueOf(message.get("citations"))).contains("\"pageId\":" + pageId);
        sessionIds.add(number(result.get("sessionId")));
    }

    // ------------------------------------------------------------------
    // ⑤ 日报草稿：只有草稿，不能发布
    // ------------------------------------------------------------------

    @Test
    void dailyReportIsDraftOnlyAndEditable() {
        Map<String, Object> generated = dataOf(post(admin, "/api/ai/reports/daily", Map.of()));
        Long reportId = number(generated.get("id"));
        assertThat(reportId).isNotNull();

        Map<String, Object> detail = dataOf(get(admin, "/api/ai/reports/" + reportId));
        assertThat(detail).containsEntry("status", "draft");
        assertThat(String.valueOf(detail.get("content"))).contains("AI 草稿").contains("## 今日完成");

        Map<String, Object> updateBody = Map.of(
                "reportTitle", "我的日报（已编辑）",
                "content", "# 我的日报\n\n人工编辑后的草稿内容");
        dataOf(put(admin, "/api/ai/reports/" + reportId, updateBody));
        Map<String, Object> edited = dataOf(get(admin, "/api/ai/reports/" + reportId));
        assertThat(String.valueOf(edited.get("reportTitle"))).isEqualTo("我的日报（已编辑）");
        assertThat(String.valueOf(edited.get("content"))).contains("人工编辑后的草稿内容");
        assertThat(edited).containsEntry("status", "draft");

        // 不存在发布接口：不能直接发布
        ResponseEntity<Map<String, Object>> publish = post(admin, "/api/ai/reports/" + reportId + "/publish", Map.of());
        assertThat(publish.getStatusCode().is4xxClientError()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_report_draft WHERE id = ?", String.class, reportId)).isEqualTo("draft");

        jdbcTemplate.update("DELETE FROM ai_report_draft WHERE id = ?", reportId);
    }

    // ------------------------------------------------------------------
    // ⑥ 会话上下文与归属隔离
    // ------------------------------------------------------------------

    @Test
    void conversationKeepsContextAndSessionIsOwned() {
        Map<String, Object> first = chat(admin, "帮我查我的任务", null);
        Long sessionId = number(first.get("sessionId"));
        sessionIds.add(sessionId);

        Map<String, Object> second = chat(admin, "查张三的绩效", sessionId);
        assertThat(number(second.get("sessionId"))).isEqualTo(sessionId);

        List<Map<String, Object>> messages = listOf(get(admin, "/api/ai/sessions/" + sessionId + "/messages"));
        assertThat(messages.size()).isGreaterThanOrEqualTo(4);
        assertThat(messages.stream().filter(row -> "user".equals(row.get("role"))).count()).isEqualTo(2);
        assertThat(messages.stream().filter(row -> "assistant".equals(row.get("role"))).count()).isEqualTo(2);

        Map<String, Object> sessions = dataOf(get(admin, "/api/ai/sessions?pageNum=1&pageSize=20"));
        assertThat(rowsOf(sessions)).anySatisfy(row -> assertThat(number(row.get("id"))).isEqualTo(sessionId));

        HttpHeaders limited = bearerHeaders(login("ai_limited_" + suffix, "admin123"));
        assertThat(get(limited, "/api/ai/sessions/" + sessionId + "/messages").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // ⑦ 未识别意图：不调用工具、给能力提示
    // ------------------------------------------------------------------

    @Test
    void unknownIntentDoesNotCallAnyTool() {
        Map<String, Object> result = chat(admin, "你好", null);
        Long sessionId = number(result.get("sessionId"));
        sessionIds.add(sessionId);
        assertThat(mapValue(result.get("intent"))).containsEntry("code", "unknown");
        assertThat(result.get("tool")).isNull();
        assertThat(String.valueOf(result.get("answer"))).contains("可以这样问");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE session_id = ?", Integer.class, sessionId)).isZero();
    }

    // ------------------------------------------------------------------
    // ⑧ 规则实现默认启用 + RAG 不引向量库 + 不跨模块直连
    // ------------------------------------------------------------------

    @Test
    void ruleResolverIsDefaultAndAiPackageHasNoVectorOrCrossModuleSql() throws IOException {
        assertThat(aiProperties.isLlmEnabled()).isFalse();
        assertThat(llmIntentResolver.available()).isFalse();

        Path aiRoot = Paths.get("..", "teamflow-system", "src", "main", "java", "com", "teamflow", "ai");
        assertThat(Files.isDirectory(aiRoot)).as("应能定位 M11 源码目录").isTrue();
        List<String> vectorViolations = new ArrayList<>();
        List<String> crossModuleViolations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(aiRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.matches("(?is).*(embedding|vectorStore|Milvus|Qdrant).*")) {
                    vectorViolations.add(file.getFileName().toString());
                }
                if (source.matches("(?is).*(INTO\\s+todo_item|INTO\\s+collab_comment|INTO\\s+knowledge_page|FROM\\s+task_|FROM\\s+okr_|FROM\\s+knowledge_).*")) {
                    crossModuleViolations.add(file.getFileName().toString());
                }
            }
        }
        assertThat(vectorViolations).as("D-12 结论三：RAG 不引 embedding/向量库").isEmpty();
        assertThat(crossModuleViolations).as("AI 只经 Service 取数，不直连他模块表").isEmpty();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private Long insertUser(String username, String nickname) {
        String passwordHash = jdbcTemplate.queryForObject("SELECT password FROM sys_user WHERE id = 1", String.class);
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
        return holder.getKey().longValue();
    }

    private Long insertRestrictedRole() {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_role (role_name, role_code, data_scope, sort, status, builtin, create_by, update_by) "
                            + "VALUES (?, ?, 'SELF', 99, 1, 0, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "AI 仅任务权限角色" + suffix);
            statement.setString(2, "ai_limited_" + suffix);
            return statement;
        }, holder);
        Long roleId = holder.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (role_id, menu_id)
                SELECT ?, id FROM sys_menu
                WHERE menu_type = 'btn' AND permission IN ('ai:chat:use', 'task:task:list')
                """, roleId);
        return roleId;
    }

    private Long insertPerfTemplate() {
        jdbcTemplate.update("""
                INSERT INTO perf_template (template_code, template_name, description, status, total_weight,
                                           create_by, update_by)
                VALUES (?, ?, 'AI 集成测试夹具', 'enabled', 100.00, 'system', 'system')
                """, "ai_tpl_" + suffix, "AI 绩效模板" + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM perf_template WHERE template_code = ?", Long.class, "ai_tpl_" + suffix);
    }

    private Long insertPerfCycle(Long templateId) {
        jdbcTemplate.update("""
                INSERT INTO perf_cycle (cycle_code, cycle_name, cycle_type, start_date, end_date, template_id,
                                        status, archive_time, create_by, update_by)
                VALUES (?, ?, 'quarter', ?, ?, ?, 'archived', NOW(), 'system', 'system')
                """, "ai_cycle_" + suffix, "AI 绩效测试周期" + suffix,
                LocalDate.now().minusDays(30), LocalDate.now(), templateId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM perf_cycle WHERE cycle_code = ?", Long.class, "ai_cycle_" + suffix);
    }

    private Long insertPerfResult(Long cycleId, Long templateId, Long employeeId) {
        jdbcTemplate.update("""
                INSERT INTO perf_result (cycle_id, employee_id, template_id, total_score, self_score,
                                         collect_score, subjective_score, effective_weight, na_weight,
                                         status, archive_time, create_by, update_by)
                VALUES (?, ?, ?, 92.50, 90.00, 92.50, 0.00, 100.00, 0.00, 'archived', NOW(), 'system', 'system')
                """, cycleId, employeeId, templateId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM perf_result WHERE cycle_id = ? AND employee_id = ?", Long.class, cycleId, employeeId);
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private Map<String, Object> chat(HttpHeaders headers, String message, Long sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (sessionId != null) {
            body.put("sessionId", sessionId);
        }
        return dataOf(post(headers, "/api/ai/chat", body));
    }

    private ResponseEntity<Map<String, Object>> get(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> post(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> put(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private String login(String username, String password) {
        return TestAuthClient.login(restTemplate, username, password);
    }

    private HttpHeaders adminHeaders() {
        adminToken = login("admin", "admin123");
        return bearerHeaders(adminToken);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("HTTP 状态").isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", 200);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsValue(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", 200);
        return (List<Map<String, Object>>) body.get("data");
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
