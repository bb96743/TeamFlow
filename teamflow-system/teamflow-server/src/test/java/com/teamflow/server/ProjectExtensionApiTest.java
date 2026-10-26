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
 * M14 扩展集成测试（M3-D）：甘特/项目任务关联（2.5.2）、风险登记（2.5.3）、项目复盘归档（2.5.4）。
 *
 * <p>真实 HTTP + 真实 M03 只读端口 + 真实 M07 归档 Service。覆盖 task-31 关键验收：</p>
 * <ol>
 *   <li>关联任务 → 甘特按项目返回时间线；与 M03 任务详情交叉核对起止/状态/完成度一致；取消关联后消失；</li>
 *   <li>任务侧无任何项目字段（查库信息模式）；</li>
 *   <li>风险登记 → 指派责任人 → 状态流转留痕 → 非法流转被拒；</li>
 *   <li>项目结项 → 复盘归档 → 本模块只存 retro_page_id、无正文；知识模块可检索到归档页面。</li>
 * </ol>
 *
 * <p><b>测试自清理</b>：项目/风险/关联/里程碑、M03 任务与流程留痕、M07 归档页面（版本/授权/页面）全部按跟踪 ID 回收。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class ProjectExtensionApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdLinkIds = new ArrayList<>();
    private final List<Long> createdRiskIds = new ArrayList<>();
    private final List<Long> createdPageIds = new ArrayList<>();

    // ------------------------------------------------------------------
    // ① 甘特时间线 + 项目任务关联生命周期（与 M03 交叉核对）
    // ------------------------------------------------------------------

    @Test
    void ganttTimelineAndTaskLinkLifecycle() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long projectId = createProject(admin, "P-EXT-" + suffix, "M14 甘特项目" + suffix);
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Long task1 = createTask(admin, "甘特任务1-" + suffix, "2026-11-01", "2026-11-30");
        Long task2 = createTask(admin, "甘特任务2-" + suffix, "2026-11-05", "2026-12-15");
        Long task3 = createTask(admin, "甘特任务3-" + suffix, "2026-12-01", "2026-12-31");
        completeTask(admin, task1);

        // 关联 3 个任务 → 关联列表 3 条；重复关联被拒
        for (Long taskId : List.of(task1, task2, task3)) {
            createdLinkIds.add(idOf(post(admin, "/api/project/projects/" + projectId + "/tasks",
                    Map.of("taskId", taskId, "linkType", "execution"))));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) listDataOf(get(admin,
                "/api/project/projects/" + projectId + "/tasks"));
        assertThat(links).hasSize(3);
        ResponseEntity<Map<String, Object>> duplicate = post(admin, "/api/project/projects/" + projectId + "/tasks",
                Map.of("taskId", task1));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(duplicate.getBody().get("message"))).contains("已关联本项目");

        // 甘特：关联任务 + 与 M03 任务详情逐字段交叉核对
        Map<String, Object> gantt = dataOf(get(admin, "/api/project/projects/" + projectId + "/gantt"));
        assertThat(gantt).containsEntry("criticalPathSupported", false);
        List<Map<String, Object>> timeline = listValue(gantt.get("tasks"));
        assertThat(timeline).hasSize(3);
        for (Map<String, Object> row : timeline) {
            Long taskId = longOf(row, "taskId");
            Map<String, Object> m03 = dataOf(get(admin, "/api/task/tasks/" + taskId));
            assertThat(row.get("taskTitle")).as("任务标题与 M03 一致").isEqualTo(m03.get("taskTitle"));
            assertThat(row.get("startDate")).as("计划开始与 M03 一致").isEqualTo(m03.get("startDate"));
            assertThat(row.get("dueDate")).as("计划结束与 M03 一致").isEqualTo(m03.get("dueDate"));
            assertThat(row.get("status")).as("状态与 M03 一致").isEqualTo(m03.get("status"));
            assertThat(row.get("statusLabel")).isEqualTo(m03.get("statusLabel"));
            assertThat(decimalOf(row, "completionPercent"))
                    .as("完成度与 M03 subProgress 一致").isEqualTo(decimalOf(m03, "subProgress"));
            assertThat(row.get("missing")).isEqualTo(false);
        }
        Map<String, Object> summary = mapValue(gantt.get("summary"));
        assertThat(longOf(summary, "linkedTaskCount")).isEqualTo(3);
        assertThat(longOf(summary, "completedTaskCount")).isEqualTo(1);
        assertThat(decimalOf(summary, "taskCompletionPercent")).isEqualTo(33.33);

        // 取消关联 → 甘特与关联列表都消失
        delete(admin, "/api/project/projects/" + projectId + "/tasks/" + createdLinkIds.get(1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linksAfter = (List<Map<String, Object>>) listDataOf(get(admin,
                "/api/project/projects/" + projectId + "/tasks"));
        assertThat(linksAfter).hasSize(2);
        Map<String, Object> ganttAfter = dataOf(get(admin, "/api/project/projects/" + projectId + "/gantt"));
        assertThat(listValue(ganttAfter.get("tasks"))).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proj_task_link WHERE project_id = ? AND task_id = ? AND deleted = 1",
                Integer.class, projectId, task2)).isEqualTo(1);

        // 任务侧无项目字段：信息模式证明
        Integer projColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'task_task' AND column_name LIKE '%proj%'",
                Integer.class);
        assertThat(projColumns).as("任务表不得新增项目字段（M2-1 分工）").isZero();

        // 归档后关联只读
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "closed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> closedLink = post(admin,
                "/api/project/projects/" + projectId + "/tasks", Map.of("taskId", task3));
        assertThat(closedLink.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(closedLink.getBody().get("message"))).contains("任务关联只读");
    }

    // ------------------------------------------------------------------
    // ② 风险登记：责任人 + 状态流转留痕 + 非法流转
    // ------------------------------------------------------------------

    @Test
    void riskRegistrationAndIllegalTransitionsAreRejected() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long projectId = createProject(admin, "P-RISK-" + suffix, "M14 风险项目" + suffix);
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> riskBody = new LinkedHashMap<>();
        riskBody.put("riskTitle", "关键路径延期风险" + suffix);
        riskBody.put("riskDesc", "供应商交付可能延迟");
        riskBody.put("riskLevel", "high");
        riskBody.put("riskType", "schedule");
        riskBody.put("ownerId", 2L);
        riskBody.put("responsePlan", "每周跟进供应商交付进度");
        riskBody.put("dueDate", "2026-12-20");
        Long riskId = idOf(post(admin, "/api/project/projects/" + projectId + "/risks", riskBody));
        createdRiskIds.add(riskId);

        Map<String, Object> detail = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/risks/" + riskId));
        assertThat(detail).containsEntry("status", "identified").containsEntry("riskLevelLabel", "高");
        assertThat(String.valueOf(detail.get("ownerName")))
                .isEqualTo(jdbcTemplate.queryForObject("SELECT nickname FROM sys_user WHERE id = 2", String.class));
        assertThat(listValue(detail.get("logs"))).hasSize(1);

        // 非法流转：identified → closed 跳级被拒，且不写留痕
        ResponseEntity<Map<String, Object>> illegal = put(admin,
                "/api/project/projects/" + projectId + "/risks/" + riskId + "/status",
                Map.of("status", "closed", "note", "直接关闭"));
        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(illegal.getBody().get("message"))).contains("不允许从「已识别」");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proj_risk_log WHERE risk_id = ?", Integer.class, riskId)).isEqualTo(1);

        // 合法链路：assessed → responding → closed，留痕逐条追加
        for (String[] step : new String[][]{
                {"assessed", "评估为高概率延期"},
                {"responding", "已启动供应商替代方案"},
                {"closed", "供应商按期交付，风险解除"}}) {
            Map<String, Object> body = Map.of("status", step[0], "note", step[1]);
            Map<String, Object> updated = dataOf(put(admin,
                    "/api/project/projects/" + projectId + "/risks/" + riskId + "/status", body));
            assertThat(updated).containsEntry("status", step[0]);
        }
        List<Map<String, Object>> logs = listDataOf(get(admin,
                "/api/project/projects/" + projectId + "/risks/" + riskId + "/logs"));
        assertThat(logs).hasSize(4);
        assertThat(logs.get(3)).containsEntry("fromStatus", "responding")
                .containsEntry("toStatus", "closed")
                .containsEntry("actionNote", "供应商按期交付，风险解除");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proj_risk_log WHERE risk_id = ?", Integer.class, riskId)).isEqualTo(4);

        // 编辑责任人/等级后回显
        Map<String, Object> update = new LinkedHashMap<>(riskBody);
        update.put("ownerId", 3L);
        update.put("riskLevel", "critical");
        assertThat(put(admin, "/api/project/projects/" + projectId + "/risks/" + riskId, update)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updatedRisk = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/risks/" + riskId));
        assertThat(updatedRisk).containsEntry("riskLevelLabel", "严重");

        // 风险归属项目校验
        Long otherProject = createProject(admin, "P-RISK2-" + suffix, "M14 另一项目" + suffix);
        ResponseEntity<Map<String, Object>> wrongProject = get(admin,
                "/api/project/projects/" + otherProject + "/risks/" + riskId);
        assertThat(wrongProject.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(wrongProject.getBody().get("message"))).contains("不属于该项目");
    }

    // ------------------------------------------------------------------
    // ③ 项目复盘归档：只存 pageId、正文进 M07、可检索、版本化
    // ------------------------------------------------------------------

    @Test
    void retroArchivedToKnowledgeWithoutCopyingContent() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        String marker = "RETRO-MARKER-" + suffix;
        Long projectId = createProject(admin, "P-RETRO-" + suffix, "M14 复盘项目" + suffix);
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 未结项时禁止归档
        ResponseEntity<Map<String, Object>> beforeClose = post(admin,
                "/api/project/projects/" + projectId + "/retro/archive",
                Map.of("content", "# 未结项复盘\n" + marker));
        assertThat(beforeClose.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(beforeClose.getBody().get("message"))).contains("结项后才能归档复盘");

        // 模板预填项目与里程碑事实
        Map<String, Object> template = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/retro/template"));
        assertThat(String.valueOf(template.get("templateContent"))).contains("M14 复盘项目" + suffix)
                .contains("## 二、目标达成情况");
        Map<String, Object> before = dataOf(get(admin, "/api/project/projects/" + projectId + "/retro"));
        assertThat(before).containsEntry("canArchive", false).containsEntry("retroArchived", false);

        // 结项 → 写复盘 → 归档到 M07
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "closed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstContent = "# 项目复盘：" + suffix + "\n\n## 五、经验与改进措施\n\n- " + marker + " 复盘正文只应存在知识模块\n";
        Map<String, Object> archiveBody = new LinkedHashMap<>();
        archiveBody.put("title", "项目复盘：" + suffix);
        archiveBody.put("content", firstContent);
        archiveBody.put("tags", List.of("项目复盘", "M14"));
        archiveBody.put("summary", "结项结论：按期交付");
        Map<String, Object> archived = dataOf(post(admin,
                "/api/project/projects/" + projectId + "/retro/archive", archiveBody));
        Long pageId = longOf(archived, "retroPageId");
        createdPageIds.add(pageId);
        assertThat(archived).containsEntry("created", true).containsEntry("pageType", "retro");
        assertThat(String.valueOf(archived.get("contentStoredIn"))).contains("M07");

        // 查库：本模块只存 retro_page_id，proj_ 表无任何正文类字段
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT retro_page_id, retro_archived_time, retro_archived_by FROM proj_project WHERE id = ?",
                projectId);
        assertThat(((Number) row.get("retro_page_id")).longValue()).isEqualTo(pageId);
        assertThat(row.get("retro_archived_time")).isNotNull();
        assertThat(String.valueOf(row.get("retro_archived_by"))).isEqualTo("admin");
        Integer contentColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name LIKE 'proj\\_%' "
                        + "AND (column_name LIKE '%content%' OR column_name LIKE '%body%' "
                        + "OR column_name LIKE '%markdown%')",
                Integer.class);
        assertThat(contentColumns).as("proj_ 表不得有复盘正文类字段").isZero();
        Integer retroColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'proj_project' AND column_name LIKE 'retro%'",
                Integer.class);
        assertThat(retroColumns).as("只有 retro_page_id / 时间 / 操作人 三列").isEqualTo(3);

        // 知识模块侧：正文与来源三要素正确
        Map<String, Object> page = jdbcTemplate.queryForMap(
                "SELECT page_title, page_type, source_module, source_biz_type, source_biz_id, content "
                        + "FROM knowledge_page WHERE id = ?", pageId);
        assertThat(String.valueOf(page.get("content"))).contains(marker);
        assertThat(page).containsEntry("source_module", "M14").containsEntry("source_biz_type", "project_retro");
        assertThat(((Number) page.get("source_biz_id")).longValue()).isEqualTo(projectId);

        // 归档页能在 M07 检索到
        Map<String, Object> search = dataOf(get(admin,
                "/api/knowledge/search?keyword=" + marker + "&pageType=retro&limit=20"));
        List<Map<String, Object>> hits = listValue(search.get("results"));
        assertThat(hits).anySatisfy(hit -> {
            assertThat(((Number) hit.get("pageId")).longValue()).isEqualTo(pageId);
            assertThat(hit).containsEntry("pageType", "retro");
        });

        // 重复归档：同一来源幂等返回同一 pageId，并生成新版本
        Map<String, Object> secondBody = new LinkedHashMap<>(archiveBody);
        secondBody.put("content", firstContent + "\n- 补充：" + marker + "-V2\n");
        Map<String, Object> second = dataOf(post(admin,
                "/api/project/projects/" + projectId + "/retro/archive", secondBody));
        assertThat(longOf(second, "retroPageId")).as("同一来源重复归档返回同一页面").isEqualTo(pageId);
        assertThat(second).containsEntry("created", false);
        assertThat(longOf(second, "versionNo")).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_page_version WHERE page_id = ?", Integer.class, pageId))
                .isGreaterThanOrEqualTo(2);

        // 归档状态回显
        Map<String, Object> after = dataOf(get(admin, "/api/project/projects/" + projectId + "/retro"));
        assertThat(after).containsEntry("retroArchived", true).containsEntry("canArchive", true);
        assertThat(longOf(after, "retroPageId")).isEqualTo(pageId);
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    @AfterEach
    void cleanup() {
        try {
            for (Long pageId : createdPageIds) {
                jdbcTemplate.update("DELETE FROM knowledge_page_version WHERE page_id = ?", pageId);
                jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'page' AND resource_id = ?", pageId);
                jdbcTemplate.update("DELETE FROM knowledge_page WHERE id = ?", pageId);
            }
            if (!createdTaskIds.isEmpty()) {
                String marks = placeholders(createdTaskIds.size());
                jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN ("
                        + "SELECT id FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + marks + "))", createdTaskIds.toArray());
                jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN ("
                        + "SELECT id FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + marks + "))", createdTaskIds.toArray());
                jdbcTemplate.update("DELETE FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + marks + ")", createdTaskIds.toArray());
                deleteByIds("DELETE FROM task_assignee WHERE task_id IN (%s)", createdTaskIds);
                deleteByIds("DELETE FROM task_relation WHERE task_id IN (%s)", createdTaskIds);
                deleteByIds("DELETE FROM task_task WHERE id IN (%s)", createdTaskIds);
            }
            if (!createdRiskIds.isEmpty()) {
                deleteByIds("DELETE FROM proj_risk_log WHERE risk_id IN (%s)", createdRiskIds);
                deleteByIds("DELETE FROM proj_risk WHERE id IN (%s)", createdRiskIds);
            }
            if (!createdProjectIds.isEmpty()) {
                deleteByIds("DELETE FROM proj_task_link WHERE project_id IN (%s)", createdProjectIds);
                deleteByIds("DELETE FROM proj_milestone WHERE project_id IN (%s)", createdProjectIds);
                deleteByIds("DELETE FROM proj_member WHERE project_id IN (%s)", createdProjectIds);
                deleteByIds("DELETE FROM proj_project WHERE id IN (%s)", createdProjectIds);
            }
        } catch (RuntimeException ignored) {
            // 清理失败不掩盖真实断言错误
        }
        createdProjectIds.clear();
        createdTaskIds.clear();
        createdLinkIds.clear();
        createdRiskIds.clear();
        createdPageIds.clear();
    }

    private void deleteByIds(String sqlTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update(String.format(sqlTemplate, placeholders(ids.size())), ids.toArray());
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    // ------------------------------------------------------------------
    // 造数辅助
    // ------------------------------------------------------------------

    private Long createProject(HttpHeaders headers, String code, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", code);
        body.put("projectName", name);
        body.put("projectDesc", "M14 扩展测试项目");
        body.put("noObjectiveReason", "探索性项目，暂不挂目标");
        body.put("ownerId", 1L);
        body.put("deptId", 5L);
        body.put("startDate", "2026-11-01");
        body.put("endDate", "2026-12-31");
        body.put("priority", "high");
        Long projectId = idOf(post(headers, "/api/project/projects", body));
        createdProjectIds.add(projectId);
        return projectId;
    }

    private Long createTask(HttpHeaders headers, String title, String startDate, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M14 甘特交叉核对任务");
        body.put("assigneeId", 1L);
        body.put("priority", "normal");
        body.put("startDate", startDate);
        body.put("dueDate", dueDate);
        Long taskId = idOf(post(headers, "/api/task/tasks", body));
        createdTaskIds.add(taskId);
        return taskId;
    }

    private void completeTask(HttpHeaders headers, Long taskId) {
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status",
                Map.of("toStatus", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status",
                Map.of("toStatus", "done")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // HTTP / JSON 辅助
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

    private ResponseEntity<Map<String, Object>> put(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> delete(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listDataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        assertThat(id).as("响应应包含 id").isInstanceOf(Number.class);
        return ((Number) id).longValue();
    }

    private long longOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private double decimalOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }
}
