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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 退出条件端到端验证（M03 发布端 → M02 订阅端），全部走**真实 HTTP 链路**。
 *
 * <p>链路：M02 建周期 → 建目标 → 建任务驱动型 KR → M03 建任务（kr_id 指向该 KR）→ 流转到完成
 * → 回查 M02 的 KR 详情，确认 {@code taskCount}/{@code progress}/{@code progress_source=task_writeback}
 * 自动变化（非手工写库、非 M03 直连 {@code okr_*}）。</p>
 *
 * <p>另覆盖 G-3 遗留的两个脏数据路径（本次必须补）：</p>
 * <ul>
 *   <li>任务从 KR-A 改挂到 KR-B：KR-A 投影被清理、计数减少、进度回落；KR-B 增加（{@code TaskKrChangedEvent}）；</li>
 *   <li>任务逻辑删除：投影被清理、进度回落（{@code TaskDeletedEvent}）；</li>
 *   <li>子任务完成时 {@code isRoot=false}，不进入顶层分母。</li>
 * </ul>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class TaskEventFlowTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本用例创建的 KR：结束后清理事件派生数据（投影与事件日志可重放重建，避免污染其他用例）。 */
    private final List<Long> createdKrIds = new ArrayList<>();

    @AfterEach
    void cleanupDerivedEventData() {
        for (Long krId : createdKrIds) {
            jdbcTemplate.update("DELETE FROM okr_kr_task_link WHERE kr_id = ?", krId);
            jdbcTemplate.update("DELETE FROM okr_task_event_log WHERE kr_id = ?", krId);
        }
        createdKrIds.clear();
    }

    // ------------------------------------------------------------------
    // ① M1 退出条件：完成任务 → KR 进度自动回写（真实 HTTP 全链路）
    // ------------------------------------------------------------------

    @Test
    void taskCompletionWritesBackToTaskDrivenKr() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "M1回写目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "M1回写KR" + suffix);
        createdKrIds.add(krId);

        // M03 建任务，kr_id 指向该 KR
        Long taskId = createTask(headers, "M1回写任务" + suffix, krId, null);
        Map<String, Object> afterCreate = krData(headers, objectiveId, krId);
        assertThat(intOf(afterCreate, "taskCount")).as("任务创建即进入分母").isEqualTo(1);
        assertThat(intOf(afterCreate, "doneTaskCount")).isZero();
        assertThat(afterCreate).containsEntry("taskLinkStatus", "linked");

        // 流转：待办 → 进行中（进度不变）→ 已完成（进度回写 100%）
        transition(headers, taskId, "in_progress", "开始执行");
        Map<String, Object> inProgress = krData(headers, objectiveId, krId);
        assertThat(intOf(inProgress, "doneTaskCount")).isZero();
        assertThat(decimalOf(inProgress, "progress")).isEqualTo(0.0);

        transition(headers, taskId, "done", "任务完成");

        Map<String, Object> afterDone = krData(headers, objectiveId, krId);
        assertThat(intOf(afterDone, "taskCount")).isEqualTo(1);
        assertThat(intOf(afterDone, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterDone, "progress")).as("1/1 顶层任务完成 = 100%").isEqualTo(100.0);
        assertThat(afterDone).containsEntry("progressSource", "task_writeback");
        assertThat(afterDone).containsEntry("taskLinkStatus", "linked");

        // 投影由事件驱动写入：任务在 M02 派生表里状态为 done，事件日志为 applied
        assertThat(linkStatus(krId, taskId)).isEqualTo("done");
        assertThat(eventCountByTask(taskId, "TaskStatusChangedEvent", "applied")).isGreaterThanOrEqualTo(1);

        // 进度留痕：最近一条来源为 task_writeback，且原因含 taskId（可回溯）
        List<Map<String, Object>> logs = progressLogs(headers, objectiveId, krId);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0)).containsEntry("progressSource", "task_writeback");
        assertThat(String.valueOf(logs.get(0).get("reason"))).contains("taskId=" + taskId);
    }

    // ------------------------------------------------------------------
    // ② 改挂 KR：旧 KR 投影清理 + 进度回落；新 KR 增加（TaskKrChangedEvent）
    // ------------------------------------------------------------------

    @Test
    void krChangeCleansOldProjectionAndRecalculatesBothKrs() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "改挂目标" + suffix);
        Long krA = createTaskDrivenKr(headers, objectiveId, "改挂KR-A" + suffix);
        Long krB = createTaskDrivenKr(headers, objectiveId, "改挂KR-B" + suffix);
        createdKrIds.add(krA);
        createdKrIds.add(krB);

        // KR-A 上两个顶层任务：一个已完成、一个待办 → A = 1/2 = 50%
        Long doneTask = createTask(headers, "改挂任务-已完成" + suffix, krA, null);
        transition(headers, doneTask, "in_progress", null);
        transition(headers, doneTask, "done", "先完成");
        Long openTask = createTask(headers, "改挂任务-待办" + suffix, krA, null);

        Map<String, Object> beforeA = krData(headers, objectiveId, krA);
        assertThat(intOf(beforeA, "taskCount")).isEqualTo(2);
        assertThat(intOf(beforeA, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(beforeA, "progress")).as("1/2 = 50%").isEqualTo(50.0);
        assertThat(linkCount(krA)).isEqualTo(2);

        // 把已完成任务从 KR-A 改挂到 KR-B
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("krId", krB);
        ResponseEntity<Map<String, Object>> response = put(headers, "/api/task/tasks/" + doneTask, update);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> afterA = krData(headers, objectiveId, krA);
        assertThat(intOf(afterA, "taskCount")).as("旧 KR 投影被清理：分母回到 1").isEqualTo(1);
        assertThat(intOf(afterA, "doneTaskCount")).isZero();
        assertThat(decimalOf(afterA, "progress")).as("旧 KR 进度回落为 0，不再虚高").isEqualTo(0.0);

        Map<String, Object> afterB = krData(headers, objectiveId, krB);
        assertThat(intOf(afterB, "taskCount")).as("新 KR 接收该任务").isEqualTo(1);
        assertThat(intOf(afterB, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterB, "progress")).as("新 KR 1/1 = 100%").isEqualTo(100.0);
        assertThat(afterB).containsEntry("progressSource", "task_writeback");

        assertThat(linkCount(krA)).as("旧 KR 不再有该任务投影").isEqualTo(1);
        assertThat(linkCount(krB)).isEqualTo(1);
        assertThat(linkStatus(krB, doneTask)).isEqualTo("done");
        assertThat(eventCountByTask(doneTask, "TaskKrChangedEvent", "applied")).isEqualTo(1);
        assertThat(openTask).isNotNull();
    }

    // ------------------------------------------------------------------
    // ③ 删除任务：投影被清理、进度回落（TaskDeletedEvent）
    // ------------------------------------------------------------------

    @Test
    void deletedTaskCleansProjectionAndProgress() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "删除目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "删除KR" + suffix);
        createdKrIds.add(krId);

        Long taskId = createTask(headers, "删除任务" + suffix, krId, null);
        transition(headers, taskId, "in_progress", null);
        transition(headers, taskId, "done", "完成后删除");

        Map<String, Object> before = krData(headers, objectiveId, krId);
        assertThat(decimalOf(before, "progress")).isEqualTo(100.0);

        ResponseEntity<Map<String, Object>> deleteResponse = delete(headers, "/api/task/tasks/" + taskId);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = krData(headers, objectiveId, krId);
        assertThat(intOf(after, "taskCount")).as("删除后退出分母").isZero();
        assertThat(intOf(after, "doneTaskCount")).isZero();
        assertThat(decimalOf(after, "progress")).as("进度回落为 0").isEqualTo(0.0);
        assertThat(after).containsEntry("taskLinkStatus", "unlinked");
        assertThat(linkCount(krId)).as("投影行被清理").isZero();
        assertThat(eventCountByTask(taskId, "TaskDeletedEvent", "applied")).isEqualTo(1);

        // 逻辑删除：任务详情不可见
        assertThat(get(headers, "/api/task/tasks/" + taskId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // ④ 子任务 isRoot=false，不进入顶层分母（D-03 边界）
    // ------------------------------------------------------------------

    @Test
    void subTaskIsNotRootAndDoesNotCountInKrDenominator() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "子任务目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "子任务KR" + suffix);
        createdKrIds.add(krId);

        Long rootTask = createTask(headers, "顶层任务" + suffix, krId, null);
        transition(headers, rootTask, "in_progress", null);
        transition(headers, rootTask, "done", "顶层完成");

        Map<String, Object> rootDone = krData(headers, objectiveId, krId);
        assertThat(intOf(rootDone, "taskCount")).isEqualTo(1);
        assertThat(decimalOf(rootDone, "progress")).isEqualTo(100.0);

        // 子任务：继承父任务的 kr_id，但事件里 isRoot=false，M02 只入投影、不计入分母
        Long childTask = createTask(headers, "子任务-不计分母" + suffix, krId, rootTask);
        assertThat(linkIsRoot(krId, childTask)).as("子任务投影 is_root=0").isZero();

        Map<String, Object> afterChildCreate = krData(headers, objectiveId, krId);
        assertThat(intOf(afterChildCreate, "taskCount")).as("子任务不增加顶层分母").isEqualTo(1);
        assertThat(decimalOf(afterChildCreate, "progress")).isEqualTo(100.0);

        transition(headers, childTask, "in_progress", null);
        transition(headers, childTask, "done", "子任务完成");

        Map<String, Object> afterChildDone = krData(headers, objectiveId, krId);
        assertThat(intOf(afterChildDone, "taskCount")).isEqualTo(1);
        assertThat(intOf(afterChildDone, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterChildDone, "progress")).as("子任务完成不改变顶层进度").isEqualTo(100.0);
        assertThat(linkCount(krId)).as("顶层 + 子任务共 2 行投影").isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // M02 / M03 请求与数据辅助
    // ------------------------------------------------------------------

    private Long createActivePeriod(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodName", "IT-M03-" + suffix);
        body.put("periodType", "quarter");
        body.put("startDate", "2026-10-01");
        body.put("endDate", "2026-12-31");
        body.put("sort", 1);
        body.put("remark", "M03 端到端事件测试周期");
        Long periodId = idOf(post(headers, "/api/okr/periods", body));
        assertThat(put(headers, "/api/okr/periods/" + periodId + "/status", Map.of("status", "active"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return periodId;
    }

    private Long createObjective(HttpHeaders headers, Long periodId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodId", periodId);
        body.put("parentId", 0L);
        body.put("objectiveTitle", title);
        body.put("objectiveDesc", "M03 端到端事件回写测试");
        body.put("level", "company");
        body.put("ownerId", 1L);
        body.put("weight", 100);
        body.put("status", "active");
        return idOf(post(headers, "/api/okr/objectives", body));
    }

    private Long createTaskDrivenKr(HttpHeaders headers, Long objectiveId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", title);
        body.put("krType", "task_driven");
        body.put("ownerId", 1L);
        return idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
    }

    private Long createTask(HttpHeaders headers, String title, Long krId, Long parentTaskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M03 → M02 端到端测试任务");
        body.put("krId", krId);
        body.put("assigneeId", 1L);
        body.put("priority", "high");
        if (parentTaskId != null) {
            body.put("parentTaskId", parentTaskId);
        }
        return idOf(post(headers, "/api/task/tasks", body));
    }

    private void transition(HttpHeaders headers, Long taskId, String toStatus, String opinion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toStatus", toStatus);
        if (opinion != null) {
            body.put("opinion", opinion);
        }
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status", body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> krData(HttpHeaders headers, Long objectiveId, Long krId) {
        return dataOf(get(headers, "/api/okr/objectives/" + objectiveId + "/krs/" + krId));
    }

    private List<Map<String, Object>> progressLogs(HttpHeaders headers, Long objectiveId, Long krId) {
        ResponseEntity<Map<String, Object>> response = get(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + krId + "/progress-logs");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("data");
        return rows;
    }

    private int linkCount(Long krId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okr_kr_task_link WHERE kr_id = ?", Integer.class, krId);
        return count == null ? 0 : count;
    }

    private String linkStatus(Long krId, Long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT task_status FROM okr_kr_task_link WHERE kr_id = ? AND task_id = ?",
                String.class, krId, taskId);
    }

    private int linkIsRoot(Long krId, Long taskId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT is_root FROM okr_kr_task_link WHERE kr_id = ? AND task_id = ?",
                Integer.class, krId, taskId);
        return value == null ? -1 : value;
    }

    private int eventCountByTask(Long taskId, String eventType, String processResult) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okr_task_event_log WHERE task_id = ? AND event_type = ? AND process_result = ?",
                Integer.class, taskId, eventType, processResult);
        return count == null ? 0 : count;
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestAuthClient.login(restTemplate, "admin", "admin123"));
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

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }

    private int intOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).intValue();
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
