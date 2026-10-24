package com.teamflow.server;

import com.teamflow.common.event.TaskStatusChangedEvent;
import com.teamflow.framework.event.DomainEventPublisher;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-3 领域事件全链路集成测试（M03 发布端用测试夹具模拟，M02 订阅端走真实 Spring 上下文）。
 *
 * <p>覆盖四条核心语义（L0 §9.1 {@code D-2026-10-25-05} 与 {@code D-2026-10-25-03}）：</p>
 * <ol>
 *   <li>事件 → 派生投影 {@code okr_kr_task_link} 写入 + 任务驱动型 KR 进度按公式变化；</li>
 *   <li>同一 {@code eventId} 重复投递不重复累加（eventId 幂等）；</li>
 *   <li>子任务完成不计入顶层分母（isRoot=0 只入投影）；</li>
 *   <li>事务回滚时事件不投递（{@code AFTER_COMMIT} 语义，用真实事务验证）。</li>
 * </ol>
 *
 * <p>另验证事件契约可被 Jackson 3 序列化/反序列化（未来切 MQ 零改动）。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class OkrTaskEventFlowTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    /** 本用例创建的 KR，用于事后清理事件派生数据（投影表与事件日志可重放重建，测试后清掉避免污染）。 */
    private final List<Long> createdKrIds = new ArrayList<>();

    /**
     * 任务 ID 生成器。
     *
     * <p>不能用 {@code System.nanoTime()}：Windows 上该时钟分辨率较粗，同一毫秒内的连续取值会相同，
     * 导致同一 KR 的两次发布落到同一投影行（唯一键 kr_id + task_id 冲突），表现为偶发红。
     * 这里用单调递增序列保证唯一，起点的数量级仍保持为"大整数"，避免与真实自增 ID 冲突。
     */
    private static final AtomicLong TASK_ID_SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    private static long nextTaskId() {
        return TASK_ID_SEQ.incrementAndGet();
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanupDerivedEventData() {
        for (Long krId : createdKrIds) {
            jdbcTemplate.update("DELETE FROM okr_kr_task_link WHERE kr_id = ?", krId);
            jdbcTemplate.update("DELETE FROM okr_task_event_log WHERE kr_id = ?", krId);
        }
        createdKrIds.clear();
    }

    // ------------------------------------------------------------------
    // ① 事件 → 投影 + 进度按 D-03 公式变化
    // ------------------------------------------------------------------

    @Test
    void taskEventUpdatesProjectionAndTaskDrivenProgress() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "事件回写目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "事件回写KR" + suffix);
        createdKrIds.add(krId);

        Map<String, Object> initial = krData(headers, objectiveId, krId);
        assertThat(intOf(initial, "taskCount")).isZero();
        assertThat(initial).containsEntry("taskLinkStatus", "unlinked");

        Long taskA = nextTaskId();
        publishInTransaction(TaskStatusChangedEvent.of(taskA, krId, taskA, true, "todo", "done", 1L));

        Map<String, Object> afterFirst = krData(headers, objectiveId, krId);
        assertThat(intOf(afterFirst, "taskCount")).as("顶层任务总数").isEqualTo(1);
        assertThat(intOf(afterFirst, "doneTaskCount")).as("已完成顶层数").isEqualTo(1);
        assertThat(decimalOf(afterFirst, "progress")).as("1/1 = 100%").isEqualTo(100.0);
        assertThat(afterFirst).containsEntry("taskLinkStatus", "linked");
        assertThat(linkCount(krId)).isEqualTo(1);
        assertThat(linkStatus(krId, taskA)).isEqualTo("done");
        assertThat(eventLogCount(krId, "applied")).isEqualTo(1);

        List<Map<String, Object>> logs = progressLogs(headers, objectiveId, krId);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0)).containsEntry("progressSource", "task_writeback");
        assertThat(String.valueOf(logs.get(0).get("reason"))).contains("eventId=");

        Long taskB = nextTaskId();
        publishInTransaction(TaskStatusChangedEvent.of(taskB, krId, taskB, true, "todo", "in_progress", 1L));

        Map<String, Object> afterSecond = krData(headers, objectiveId, krId);
        assertThat(intOf(afterSecond, "taskCount")).isEqualTo(2);
        assertThat(intOf(afterSecond, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterSecond, "progress")).as("1/2 = 50%").isEqualTo(50.0);
        assertThat(linkCount(krId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // ② 同一 eventId 重复投递 → 不重复累加
    // ------------------------------------------------------------------

    @Test
    void duplicateEventIdDoesNotAccumulate() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "幂等目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "幂等KR" + suffix);
        createdKrIds.add(krId);

        Long taskId = nextTaskId();
        TaskStatusChangedEvent event = TaskStatusChangedEvent.of(taskId, krId, taskId, true, "todo", "done", 1L);
        publishInTransaction(event);

        int progressLogsAfterFirst = progressLogs(headers, objectiveId, krId).size();
        Map<String, Object> afterFirst = krData(headers, objectiveId, krId);
        assertThat(intOf(afterFirst, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterFirst, "progress")).isEqualTo(100.0);

        // 同一 eventId 再投递一次（模拟 M03 重试/重复投递）
        publishInTransaction(event);

        Map<String, Object> afterDuplicate = krData(headers, objectiveId, krId);
        assertThat(intOf(afterDuplicate, "taskCount")).as("重复投递不得增加顶层任务数").isEqualTo(1);
        assertThat(intOf(afterDuplicate, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(afterDuplicate, "progress")).as("重复投递不得重复累加").isEqualTo(100.0);
        assertThat(linkCount(krId)).as("业务键 (taskId, krId) 唯一，投影仍 1 行").isEqualTo(1);
        assertThat(eventLogCount(krId, "applied")).as("eventId 唯一键拦截重复事件").isEqualTo(1);
        assertThat(progressLogs(headers, objectiveId, krId).size())
                .as("重复事件不得新增进度留痕").isEqualTo(progressLogsAfterFirst);
    }

    // ------------------------------------------------------------------
    // ③ 子任务完成不计入顶层分母（D-03 边界）
    // ------------------------------------------------------------------

    @Test
    void subTaskCompletionDoesNotCountInRootDenominator() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "子任务目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "子任务KR" + suffix);
        createdKrIds.add(krId);

        Long rootDone = nextTaskId();
        Long rootOpen = nextTaskId();
        publishInTransaction(TaskStatusChangedEvent.of(rootDone, krId, rootDone, true, "todo", "done", 1L));
        publishInTransaction(TaskStatusChangedEvent.of(rootOpen, krId, rootOpen, true, "todo", "in_progress", 1L));

        Map<String, Object> baseline = krData(headers, objectiveId, krId);
        assertThat(intOf(baseline, "taskCount")).isEqualTo(2);
        assertThat(intOf(baseline, "doneTaskCount")).isEqualTo(1);
        assertThat(decimalOf(baseline, "progress")).isEqualTo(50.0);

        // 子任务完成：只入投影，不进入分子/分母
        Long childTask = nextTaskId();
        publishInTransaction(TaskStatusChangedEvent.of(childTask, krId, rootDone, false, "todo", "done", 1L));

        Map<String, Object> afterChild = krData(headers, objectiveId, krId);
        assertThat(intOf(afterChild, "taskCount")).as("子任务不改变顶层分母").isEqualTo(2);
        assertThat(intOf(afterChild, "doneTaskCount")).as("子任务不进入已完成顶层数").isEqualTo(1);
        assertThat(decimalOf(afterChild, "progress")).as("进度仍为 1/2 = 50%").isEqualTo(50.0);
        assertThat(linkCount(krId)).as("子任务仍入投影，共 3 行").isEqualTo(3);
        assertThat(linkIsRoot(krId, childTask)).isZero();
        assertThat(linkStatus(krId, childTask)).isEqualTo("done");
    }

    // ------------------------------------------------------------------
    // ④ 事务回滚 → 事件不投递（真实事务，AFTER_COMMIT 语义）
    // ------------------------------------------------------------------

    @Test
    void eventNotDeliveredWhenTransactionRollsBack() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, "回滚目标" + suffix);
        Long krId = createTaskDrivenKr(headers, objectiveId, "回滚KR" + suffix);
        createdKrIds.add(krId);

        Map<String, Object> before = krData(headers, objectiveId, krId);
        int linksBefore = linkCount(krId);
        int logsBefore = progressLogs(headers, objectiveId, krId).size();

        Long taskId = nextTaskId();
        TaskStatusChangedEvent event = TaskStatusChangedEvent.of(taskId, krId, taskId, true, "todo", "done", 1L);

        // 模拟 M03 在事务内发布事件后业务失败回滚
        transactionTemplate().executeWithoutResult(status -> {
            domainEventPublisher.publish(event);
            status.setRollbackOnly();
        });

        Map<String, Object> after = krData(headers, objectiveId, krId);
        assertThat(linkCount(krId)).as("回滚后不得写投影").isEqualTo(linksBefore);
        assertThat(eventLogCountByTask(taskId)).as("回滚后不得写事件日志").isZero();
        assertThat(progressLogs(headers, objectiveId, krId).size()).as("回滚后不得新增进度留痕").isEqualTo(logsBefore);
        assertThat(decimalOf(after, "progress")).isEqualTo(decimalOf(before, "progress"));
        assertThat(intOf(after, "taskCount")).isZero();
    }

    // ------------------------------------------------------------------
    // ⑤ 事件契约可被 Jackson 3 序列化（未来切 MQ 零改动）
    // ------------------------------------------------------------------

    @Test
    void eventContractIsJackson3Serializable() {
        TaskStatusChangedEvent event = TaskStatusChangedEvent.of(101L, 202L, 101L, true, "todo", "done", 1L);

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"eventId\"").contains("\"taskId\":101").contains("\"krId\":202")
                .contains("\"rootTaskId\":101").contains("\"isRoot\":true")
                .contains("\"fromStatus\":\"todo\"").contains("\"toStatus\":\"done\"")
                .contains("\"operatorId\":1").contains("\"occurredAt\"");

        TaskStatusChangedEvent restored = objectMapper.readValue(json, TaskStatusChangedEvent.class);
        assertThat(restored.eventId()).isEqualTo(event.eventId());
        assertThat(restored.taskId()).isEqualTo(101L);
        assertThat(restored.krId()).isEqualTo(202L);
        assertThat(restored.isRoot()).isTrue();
        assertThat(restored.occurredAt()).isEqualTo(event.occurredAt());
    }

    // ------------------------------------------------------------------
    // 请求与数据辅助
    // ------------------------------------------------------------------

    private void publishInTransaction(TaskStatusChangedEvent event) {
        transactionTemplate().executeWithoutResult(status -> domainEventPublisher.publish(event));
    }

    private Long createActivePeriod(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodName", "IT-G3-" + suffix);
        body.put("periodType", "quarter");
        body.put("startDate", "2026-10-01");
        body.put("endDate", "2026-12-31");
        body.put("sort", 1);
        body.put("remark", "G-3 事件集成测试周期");
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
        body.put("objectiveDesc", "G-3 事件回写集成测试");
        body.put("level", "company");
        body.put("ownerId", 1L);
        body.put("weight", 100);
        body.put("status", "active");
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/okr/objectives", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private Long createTaskDrivenKr(HttpHeaders headers, Long objectiveId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", title);
        body.put("krType", "task_driven");
        body.put("ownerId", 1L);
        ResponseEntity<Map<String, Object>> response = post(headers,
                "/api/okr/objectives/" + objectiveId + "/krs", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private Map<String, Object> krData(HttpHeaders headers, Long objectiveId, Long krId) {
        return dataOf(get(headers, "/api/okr/objectives/" + objectiveId + "/krs/" + krId));
    }

    private List<Map<String, Object>> progressLogs(HttpHeaders headers, Long objectiveId, Long krId) {
        ResponseEntity<Map<String, Object>> response = get(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + krId + "/progress-logs");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
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

    private int eventLogCount(Long krId, String processResult) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okr_task_event_log WHERE kr_id = ? AND process_result = ?",
                Integer.class, krId, processResult);
        return count == null ? 0 : count;
    }

    private int eventLogCountByTask(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okr_task_event_log WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private HttpHeaders adminHeaders() {
        return bearerHeaders(login("admin", "admin123"));
    }

    private String login(String username, String password) {
        return TestAuthClient.login(restTemplate, username, password);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String uniqueSuffix() {
        return String.valueOf(nextTaskId());
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
}
