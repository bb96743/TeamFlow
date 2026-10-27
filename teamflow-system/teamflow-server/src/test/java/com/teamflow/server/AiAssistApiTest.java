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

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 AI 待办建议 + 智能提醒集成测试（1.3.5 / 1.3.6，独立库 teamflow_test_p1b）。
 *
 * <p>覆盖：负载均衡建议给出可解释理由且**不修改任务负责人**；建议接口单次请求 SQL 条数为个位数
 * （用 MySQL {@code performance_schema} 语句摘要在该隔离库上的增量计数，不引 N+1）；
 * 提醒判定命中后**经 M09 通知入口**触达（{@code msg_notification} 留痕）、同日重复扫描去重。
 * 测试自清理：建议/提醒/通知/任务/流程留痕全部回收。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class AiAssistApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> taskIds = new ArrayList<>();
    private final List<Long> tempUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long taskId : taskIds) {
            jdbcTemplate.update("DELETE FROM ai_suggestion WHERE biz_type = 'task' AND biz_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM ai_reminder WHERE biz_type = 'task' AND biz_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM msg_notification WHERE biz_type = 'task' AND biz_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM task_assignee WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN "
                    + "(SELECT id FROM flow_instance WHERE biz_id = ? AND instance_type = 'status')", taskId);
            jdbcTemplate.update("DELETE FROM flow_instance WHERE biz_id = ? AND instance_type = 'status'", taskId);
            jdbcTemplate.update("DELETE FROM task_task WHERE id = ?", taskId);
        }
        taskIds.clear();
        for (Long tempUserId : tempUserIds) {
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", tempUserId);
        }
        tempUserIds.clear();
        jdbcTemplate.update("DELETE FROM msg_notification WHERE idempotency_key LIKE 'ai-reminder:%'");
    }

    @Test
    void workloadSuggestionIsExplainableAndNeverDispatched() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // 负载：admin 3 个进行中、chensy 5 个进行中、wumin 0 个；目标为待认领任务
        for (int index = 0; index < 3; index++) {
            createInProgressTask(admin, "AI负载-admin-" + suffix + "-" + index, 1L);
        }
        for (int index = 0; index < 5; index++) {
            createInProgressTask(admin, "AI负载-chensy-" + suffix + "-" + index, 2L);
        }
        Long targetTaskId = createTask(admin, "AI建议目标-" + suffix, null);

        Map<String, Long> sqlBefore = digestSnapshot();
        ResponseEntity<Map<String, Object>> response = post(admin, "/api/ai/suggestions/assign",
                Map.of("taskId", targetTaskId));
        Map<String, Long> sqlAfter = digestSnapshot();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        System.out.println("[AI] 建议结果: " + data);

        assertThat(number(data.get("suggestedUserId"))).as("负载最低的用户应为 wumin(3)").isEqualTo(3);
        assertThat(data).containsEntry("executable", false);
        assertThat(String.valueOf(data.get("reason")))
                .contains("进行中")
                .contains("不会自动修改任务负责人")
                .contains("AI建议目标-" + suffix);
        List<Map<String, Object>> candidates = listValue(data.get("candidates"));
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anySatisfy(candidate -> assertThat(number(candidate.get("userId"))).isEqualTo(3));

        // 不自动派发：目标任务的负责人保持为空
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT assignee_id, status FROM task_task WHERE id = ?", targetTaskId);
        assertThat(stored.get("assignee_id")).as("建议接口绝不自动派发（负责人仍为空）").isNull();

        // 建议留痕（可审计 + 理由/依据非空）
        Map<String, Object> suggestion = jdbcTemplate.queryForMap(
                "SELECT suggested_user_id, reason, evidence_json FROM ai_suggestion"
                        + " WHERE biz_type = 'task' AND biz_id = ?", targetTaskId);
        assertThat(number(suggestion.get("suggested_user_id"))).isEqualTo(3);
        assertThat(String.valueOf(suggestion.get("reason"))).isNotBlank();
        assertThat(String.valueOf(suggestion.get("evidence_json"))).contains("load_balance").contains("candidates");

        // 不写 N+1：隔离库 performance_schema 语句摘要增量 —— 业务数据 SQL 为个位数
        // （task_task / task_relation / ai_suggestion / 候选人目录一次读取；其余为既有框架鉴权/审计/事务语句）
        long totalDelta = 0;
        long businessDelta = 0;
        List<String> businessSql = new ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(sqlBefore.keySet());
        keys.addAll(sqlAfter.keySet());
        for (String sql : keys) {
            long delta = sqlAfter.getOrDefault(sql, 0L) - sqlBefore.getOrDefault(sql, 0L);
            if (delta <= 0) {
                continue;
            }
            totalDelta += delta;
            String normalized = sql.toLowerCase(java.util.Locale.ROOT);
            boolean business = normalized.contains("task_task") || normalized.contains("task_relation")
                    || normalized.contains("ai_suggestion")
                    || (normalized.contains("sys_user") && normalized.contains("order by id"));
            if (business) {
                businessDelta += delta;
                businessSql.add(sql.replaceAll("\\s+", " ").trim());
            }
        }
        System.out.println("[AI] 建议接口 SQL 增量：总语句=" + totalDelta + "，业务数据语句=" + businessDelta);
        businessSql.forEach(sql -> System.out.println("[AI]   业务SQL: " + sql));
        assertThat(businessDelta)
                .as("建议接口业务数据 SQL 应为个位数（口径：候选人负载一次批量查询，无 N+1）")
                .isBetween(1L, 9L);
        long batchLoadStatements = businessSql.stream()
                .filter(sql -> sql.contains("task_relation") && sql.contains("GROUP BY"))
                .count();
        assertThat(batchLoadStatements).as("候选人负载必须是一次批量查询").isEqualTo(1L);

        // 建议列表可查
        Map<String, Object> page = dataOf(get(admin, "/api/ai/suggestions?status=pending&pageSize=20"));
        assertThat(listValue(page.get("rows"))).anySatisfy(row ->
                assertThat(number(row.get("bizId"))).isEqualTo(targetTaskId));
    }

    @Test
    void reminderJudgesAndDeliversThroughCollabNotification() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        String dueDate = LocalDate.now().plusDays(1).toString();
        Long taskId = createTaskWithDueDate(admin, "AI提醒任务-" + suffix, 2L, dueDate);

        ResponseEntity<Map<String, Object>> response = post(admin, "/api/ai/reminders/scan?withinDays=3", Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        System.out.println("[AI] 提醒扫描结果: " + data);
        assertThat(number(data.get("matched"))).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = listValue(data.get("rows")).stream()
                .filter(item -> number(item.get("taskId")).equals(taskId)).findFirst().orElseThrow();
        assertThat(String.valueOf(row.get("notifyStatus"))).isEqualTo("sent");
        assertThat(String.valueOf(row.get("reason"))).contains("责任人为").contains("到期");

        // 判定记录（本模块只存判定 + 引用）
        Map<String, Object> reminder = jdbcTemplate.queryForMap(
                "SELECT receiver_id, notify_status, notification_id, reason FROM ai_reminder"
                        + " WHERE biz_type = 'task' AND biz_id = ?", taskId);
        assertThat(number(reminder.get("receiver_id"))).isEqualTo(2);
        assertThat(String.valueOf(reminder.get("notify_status"))).isEqualTo("sent");
        Long notificationId = number(reminder.get("notification_id"));
        assertThat(notificationId).isNotNull();

        // 触达由 M09 执行：通知记录存在（msg_notification），且内容来自模板渲染
        Map<String, Object> notification = jdbcTemplate.queryForMap(
                "SELECT receiver_id, title, content, biz_type FROM msg_notification WHERE id = ?", notificationId);
        assertThat(number(notification.get("receiver_id"))).isEqualTo(2);
        assertThat(String.valueOf(notification.get("biz_type"))).isEqualTo("task");
        assertThat(String.valueOf(notification.get("title"))).contains("任务即将到期");
        assertThat(String.valueOf(notification.get("content"))).contains("AI提醒任务-" + suffix);

        // 同日重复扫描：判定去重，不重复触达
        post(admin, "/api/ai/reminders/scan?withinDays=3", Map.of());
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE biz_type = 'task' AND biz_id = ?",
                Integer.class, taskId);
        assertThat(notificationCount).as("同一任务同一责任人每天只提醒一次").isEqualTo(1);
        Integer reminderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_reminder WHERE biz_type = 'task' AND biz_id = ?", Integer.class, taskId);
        assertThat(reminderCount).isEqualTo(1);
    }

    /**
     * 边界：负责人已被停用/删除的到期任务 —— 单条触达失败不得拖垮整个扫描（不 500），
     * 该条记为 failed，其余正常任务仍提醒成功。
     *
     * <p>复现路径：`notifyInternal`（M09，REQUIRED）在 scan 的事务内抛 BusinessException 后
     * 外层事务被标记 rollback-only，即使 M11 捕获异常，提交时仍抛 UnexpectedRollbackException → 500。
     * 修复：通知投递改为独立事务（REQUIRES_NEW），失败只影响该条。</p>
     */
    @Test
    void scanSkipsInactiveReceiverInsteadOfRollingBackWholeScan() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        String dueDate = LocalDate.now().plusDays(1).toString();

        // 正常负责人（应提醒成功）
        Long normalTaskId = createTaskWithDueDate(admin, "AI提醒正常-" + suffix, 2L, dueDate);
        // 临时用户做负责人，创建任务后停用（模拟"负责人已停用"的脏数据）
        Long tempUserId = insertTempUser("it_ai_inactive_" + suffix);
        Long badTaskId = createTaskWithDueDate(admin, "AI提醒失效负责人-" + suffix, tempUserId, dueDate);
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = ?", tempUserId);

        ResponseEntity<Map<String, Object>> response = post(admin, "/api/ai/reminders/scan?withinDays=3", Map.of());
        System.out.println("[AI] 边界扫描 HTTP 状态=" + response.getStatusCode() + " body=" + response.getBody());
        // 修复前：500（UnexpectedRollbackException）；修复后：200 且单条降级
        assertThat(response.getStatusCode()).as("单条负责人失效不应导致整个扫描 500").isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(number(data.get("failed"))).isGreaterThanOrEqualTo(1);
        assertThat(number(data.get("reminded"))).isGreaterThanOrEqualTo(1);

        List<Map<String, Object>> rows = listValue(data.get("rows"));
        Map<String, Object> badRow = rows.stream()
                .filter(item -> number(item.get("taskId")).equals(badTaskId)).findFirst().orElseThrow();
        assertThat(String.valueOf(badRow.get("notifyStatus"))).isEqualTo("failed");
        assertThat(String.valueOf(badRow.get("error"))).contains("不存在或已停用");
        Map<String, Object> normalRow = rows.stream()
                .filter(item -> number(item.get("taskId")).equals(normalTaskId)).findFirst().orElseThrow();
        assertThat(String.valueOf(normalRow.get("notifyStatus"))).isEqualTo("sent");

        // 正常任务已触达；失效负责人任务没有通知记录；判定表分别记录 sent / failed
        Integer normalNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE biz_type = 'task' AND biz_id = ?",
                Integer.class, normalTaskId);
        Integer badNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE biz_type = 'task' AND biz_id = ?",
                Integer.class, badTaskId);
        assertThat(normalNotifications).isEqualTo(1);
        assertThat(badNotifications).isZero();
        assertThat(String.valueOf(jdbcTemplate.queryForObject(
                "SELECT notify_status FROM ai_reminder WHERE biz_type = 'task' AND biz_id = ?",
                String.class, badTaskId))).isEqualTo("failed");
        assertThat(String.valueOf(jdbcTemplate.queryForObject(
                "SELECT notify_status FROM ai_reminder WHERE biz_type = 'task' AND biz_id = ?",
                String.class, normalTaskId))).isEqualTo("sent");
    }

    // ------------------------------------------------------------------
    // 造数
    // ------------------------------------------------------------------

    private Long insertTempUser(String username) {
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
            statement.setString(3, "AI 边界测试用户");
            return statement;
        }, holder);
        Long userId = holder.getKey().longValue();
        tempUserIds.add(userId);
        return userId;
    }

    private Long createTask(HttpHeaders headers, String title, Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "AI 建议/提醒测试数据");
        body.put("priority", "normal");
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        Long taskId = number(dataOf(post(headers, "/api/task/tasks", body)).get("id"));
        taskIds.add(taskId);
        return taskId;
    }

    private Long createTaskWithDueDate(HttpHeaders headers, String title, Long assigneeId, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "AI 提醒测试数据");
        body.put("priority", "high");
        body.put("assigneeId", assigneeId);
        body.put("dueDate", dueDate);
        Long taskId = number(dataOf(post(headers, "/api/task/tasks", body)).get("id"));
        taskIds.add(taskId);
        return taskId;
    }

    private void createInProgressTask(HttpHeaders headers, String title, Long assigneeId) {
        Long taskId = createTask(headers, title, assigneeId);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/task/tasks/" + taskId + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("toStatus", "in_progress"), headers), MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------

    /** 当前隔离库的语句摘要快照（digest -> 执行次数），用于计算一次接口调用的 SQL 增量。 */
    private Map<String, Long> digestSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT DIGEST_TEXT, COUNT_STAR
                FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME = DATABASE() AND DIGEST_TEXT IS NOT NULL
                """, rs -> {
            snapshot.put(rs.getString("DIGEST_TEXT"), rs.getLong("COUNT_STAR"));
        });
        return snapshot;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
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

    private Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
