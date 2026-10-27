package com.teamflow.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入导出集成测试（2.4.5，真实 HTTP + teamflow_test_p1a 独立库）。
 *
 * <p>覆盖：2 行合法 + 1 行非法 CSV → 逐行结果（2 成功 1 失败含原因）且失败行无脏数据；
 * 导出 CSV 带 UTF-8 BOM、中文不乱码；低权限用户导出条数少于超管（数据权限生效）。
 * 测试自清理。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class TransferApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> taskIds = new ArrayList<>();
    private Long restrictedRoleId;
    private Long restrictedUserId;
    private Long transferLogBaseline;

    @AfterEach
    void cleanUp() {
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
        if (transferLogBaseline != null) {
            jdbcTemplate.update("DELETE FROM open_transfer_log WHERE id > ?", transferLogBaseline);
            transferLogBaseline = null;
        }
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
    void importReportsPerRowAndExportHonoursDataScope() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        transferLogBaseline = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM open_transfer_log", Long.class);

        // 先造 2 条超管可见任务（用于导出条数对比）
        Long taskA = createTask(admin, "导出任务A-" + suffix);
        Long taskB = createTask(admin, "导出任务B-" + suffix);

        // ① 导入：2 行合法 + 1 行非法（日期格式错误）
        String csv = "\uFEFF"
                + "任务标题,任务描述,优先级,负责人账号,计划开始,截止日期,关联KR ID\r\n"
                + "导入任务A-" + suffix + ",说明A,high,,2026-11-01,2026-11-10,\r\n"
                + "导入任务B-" + suffix + ",说明B,,,,\r\n"
                + "非法行-" + suffix + ",说明C,normal,,2026-13-45,,\r\n";
        MultipartUpload upload = multipart(admin, csv, "tasks-import-" + suffix + ".csv");
        ResponseEntity<Map<String, Object>> importResponse = restTemplate.exchange(
                "/api/integration/transfer/tasks/import", HttpMethod.POST, upload.entity(), MAP_TYPE);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> imported = dataOf(importResponse);
        System.out.println("[TRANSFER] 导入逐行结果=" + imported);
        assertThat(imported).containsEntry("total", 3)
                .containsEntry("success", 2)
                .containsEntry("failed", 1)
                .containsEntry("status", "partial");
        List<Map<String, Object>> rows = listValue(imported.get("rows"));
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(row -> Boolean.TRUE.equals(row.get("success"))).count()).isEqualTo(2);
        Map<String, Object> failedRow = rows.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("success"))).findFirst().orElseThrow();
        assertThat(String.valueOf(failedRow.get("error"))).contains("日期格式");
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("success"))) {
                taskIds.add(number(row.get("taskId")));
            }
        }
        // 失败行不留脏数据
        Integer validCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_task WHERE deleted = 0 AND task_title LIKE ?",
                Integer.class, "导入任务%");
        Integer invalidCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_task WHERE deleted = 0 AND task_title = ?",
                Integer.class, "非法行-" + suffix);
        assertThat(validCount).isEqualTo(2);
        assertThat(invalidCount).isZero();

        // ② 导出：BOM + 中文表头 + 数据行
        ResponseEntity<byte[]> exported = restTemplate.exchange(
                "/api/integration/transfer/tasks/export", HttpMethod.GET,
                new HttpEntity<>(admin), byte[].class);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csvText = new String(exported.getBody(), StandardCharsets.UTF_8);
        String[] lines = csvText.split("\\R");
        System.out.println("[TRANSFER] 导出前 3 行：");
        for (int index = 0; index < Math.min(3, lines.length); index++) {
            System.out.println("[TRANSFER]   " + lines[index]);
        }
        assertThat(csvText).startsWith("\uFEFF");
        assertThat(lines[0]).contains("任务标题").contains("负责人账号");
        assertThat(lines.length).isGreaterThanOrEqualTo(4);
        int adminRows = (int) java.util.Arrays.stream(lines).filter(line -> !line.isBlank()).count() - 1;

        // ③ 模板：BOM + 中文表头
        ResponseEntity<byte[]> template = restTemplate.exchange(
                "/api/integration/transfer/tasks/template", HttpMethod.GET,
                new HttpEntity<>(admin), byte[].class);
        String templateText = new String(template.getBody(), StandardCharsets.UTF_8);
        assertThat(templateText).startsWith("\uFEFF").contains("任务标题");

        // ④ 低权限用户（SELF 数据范围）导出条数必须少于超管
        restrictedRoleId = insertRole(suffix);
        restrictedUserId = insertUser("it_m12_export_" + suffix, restrictedRoleId);
        HttpHeaders limited = bearerHeaders(TestAuthClient.login(restTemplate,
                "it_m12_export_" + suffix, "admin123"));
        ResponseEntity<byte[]> limitedExport = restTemplate.exchange(
                "/api/integration/transfer/tasks/export", HttpMethod.GET,
                new HttpEntity<>(limited), byte[].class);
        assertThat(limitedExport.getStatusCode()).isEqualTo(HttpStatus.OK);
        String limitedText = new String(limitedExport.getBody(), StandardCharsets.UTF_8);
        int limitedRows = limitedText.split("\\R").length - 1;
        System.out.println("[TRANSFER] 导出条数对比：超管=" + adminRows + " 低权限=" + limitedRows);
        assertThat(limitedRows).as("低权限用户导出条数必须少于超管（数据权限生效）")
                .isLessThan(adminRows);

        // ⑤ 批次审计留痕
        List<Map<String, Object>> logs = dataListOf(get(admin,
                "/api/integration/transfer/logs?limit=10"));
        assertThat(logs).anySatisfy(log -> assertThat(String.valueOf(log.get("direction"))).isEqualTo("import"));
        assertThat(logs).anySatisfy(log -> assertThat(String.valueOf(log.get("direction"))).isEqualTo("export"));
        assertThat(taskA).isNotNull();
        assertThat(taskB).isNotNull();
    }

    // ------------------------------------------------------------------

    private Long createTask(HttpHeaders headers, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "导出对比用任务");
        body.put("priority", "normal");
        Long taskId = number(dataOf(post(headers, "/api/task/tasks", body)).get("id"));
        taskIds.add(taskId);
        return taskId;
    }

    private record MultipartUpload(MultiValueMap<String, Object> body, HttpHeaders headers) {
        HttpEntity<MultiValueMap<String, Object>> entity() {
            return new HttpEntity<>(body, headers);
        }
    }

    private MultipartUpload multipart(HttpHeaders authHeaders, String content, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(authHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new MultipartUpload(body, headers);
    }

    private Long insertRole(String suffix) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_role (role_name, role_code, data_scope, sort, status, builtin, create_by, update_by) "
                            + "VALUES (?, ?, 'SELF', 99, 1, 0, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "M12 导出只读角色" + suffix);
            statement.setString(2, "it_m12_export_" + suffix);
            return statement;
        }, holder);
        Long roleId = holder.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (role_id, menu_id)
                SELECT ?, id FROM sys_menu WHERE menu_type = 'btn'
                  AND permission IN ('task:task:list', 'integration:transfer:export')
                """, roleId);
        return roleId;
    }

    private Long insertUser(String username, Long roleId) {
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
            statement.setString(3, "M12 导出用户");
            return statement;
        }, holder);
        Long userId = holder.getKey().longValue();
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return userId;
    }

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
    private List<Map<String, Object>> dataListOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (List<Map<String, Object>>) response.getBody().get("data");
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
        return Long.valueOf(String.valueOf(value));
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet() % 1_000_000L);
    }
}
