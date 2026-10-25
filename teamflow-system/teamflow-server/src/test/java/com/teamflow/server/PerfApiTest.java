package com.teamflow.server;

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

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05 绩效模块集成测试（真实 HTTP 链路 + 真实 M02/M03 数据）。
 *
 * <p><b>覆盖</b>：</p>
 * <ol>
 *   <li>端到端主链路：周期开启 → 归集预览（真实 KR 进度 + 任务事实）→ 自评 → 上级评 → 总分 → 归档 → 锁定，
 *       并验证锁定后编辑被拒、申诉成为唯一改分通道；</li>
 *   <li>L0 §9.1 {@code D-2026-10-25-06} 的五条口径（D-06.1～D-06.5）逐条断言；</li>
 *   <li>R2 指标字典硬门槛、R3 重算幂等、R6 权重校验、DoD 可归集指标下钻追溯。</li>
 * </ol>
 *
 * <p><b>隔离策略</b>：每个用例通过 JDBC 建一个独立测试员工（每个用例唯一用户名，结束后清理），
 * 因此归集只命中本用例造的 KR / 任务，不受其它模块测试数据污染。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class PerfApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);
    private static final String CYCLE_START = "2026-10-01";
    private static final String CYCLE_END = "2026-12-31";
    /** 逾期任务截止日（早于用例执行时刻 2026-10-25，完成即为逾期）。 */
    private static final String OVERDUE_DUE_DATE = "2026-10-01";
    /** 按时任务截止日。 */
    private static final String ON_TIME_DUE_DATE = "2026-12-31";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdCycleIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    private Long employeeId;
    private String employeeUsername;

    @BeforeEach
    void createIsolatedEmployee() {
        String suffix = nextSuffix();
        employeeUsername = "perf_it_" + suffix;
        String passwordHash = jdbcTemplate.queryForObject("SELECT password FROM sys_user WHERE id = 1", String.class);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by) "
                            + "VALUES (?, ?, ?, 5, 1, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, employeeUsername);
            statement.setString(2, passwordHash);
            statement.setString(3, "绩效归集测试员工" + suffix);
            return statement;
        }, keyHolder);
        employeeId = keyHolder.getKey().longValue();
        // 业务管理员角色：V10 已授予 perf:score:self / perf:cycle:list / perf:score:list / perf:result:list / perf:result:appeal
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, 3)", employeeId);
        createdUserIds.add(employeeId);
    }

    @AfterEach
    void cleanup() {
        for (Long cycleId : createdCycleIds) {
            jdbcTemplate.update("DELETE FROM perf_appeal WHERE cycle_id = ?", cycleId);
            jdbcTemplate.update("DELETE FROM perf_result WHERE cycle_id = ?", cycleId);
            jdbcTemplate.update("DELETE FROM perf_score WHERE cycle_id = ?", cycleId);
            jdbcTemplate.update("DELETE FROM perf_cycle WHERE id = ?", cycleId);
        }
        for (Long templateId : createdTemplateIds) {
            jdbcTemplate.update("DELETE FROM perf_template_item WHERE template_id = ?", templateId);
            jdbcTemplate.update("DELETE FROM perf_template WHERE id = ?", templateId);
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        createdCycleIds.clear();
        createdTemplateIds.clear();
        createdUserIds.clear();
    }

    // ------------------------------------------------------------------
    // ① 端到端主链路 + 锁定 + 申诉
    // ------------------------------------------------------------------

    @Test
    void endToEndCollectScoreArchiveLockAndAppeal() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long itemCollab = subjectiveItemId(admin, templateId, "collab_attitude");

        // M02：目标 + 任务驱动型 KR；M03：关联任务并完成 → KR 进度 100%
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);
        Long taskId = createTask(admin, "端到端任务" + suffix, krId, ON_TIME_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "任务完成");
        assertThat(decimalOf(krDetail(admin, objectiveId, krId), "progress")).isEqualTo(100.0);

        // 归集预览：真实取到 M02 KR 进度 + M03 任务数据
        Map<String, Object> preview = employeeCollect(admin, "/api/perf/collect/preview", cycleId, employeeId);
        assertThat(decimalOf(itemOf(preview, "kr_achievement"), "autoScore")).as("KR 达成率取到 M02 进度").isEqualTo(100.0);
        assertThat(decimalOf(itemOf(preview, "task_completion"), "autoScore")).as("任务完成率取到 M03 事实").isEqualTo(100.0);
        assertThat(decimalOf(itemOf(preview, "time_compliance"), "autoScore")).as("按时完成").isEqualTo(100.0);
        assertThat(decimalOf(preview, "previewTotal")).as("(100*40+100*30+100*10)/80").isEqualTo(100.0);

        // 自评（被评人本人登录）→ 上级评（管理者）
        HttpHeaders employee = bearerHeaders(login(employeeUsername, "admin123"));
        saveSelf(employee, cycleId, itemCollab, 80, "本人自评：协作良好");
        saveManager(admin, cycleId, employeeId, itemCollab, 90, "上级评：跨团队协同突出");

        // 进入评分中 → 归档（周期末快照重算 + 权重加权）
        assertThat(put(admin, "/api/perf/cycles/" + cycleId + "/status", Map.of("status", "scoring"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> archived = dataOf(post(admin, "/api/perf/results/archive", Map.of("cycleId", cycleId)));
        assertThat(archived).containsEntry("status", "archived").containsEntry("archivedCount", 1);

        Map<String, Object> result = resultOfCycle(admin, cycleId);
        // (100*40 + 100*30 + 100*10 + 90*20) / 100 = 98；自评口径 (4000+3000+1000+1600)/100 = 96
        assertThat(decimalOf(result, "totalScore")).as("上级评口径加权总分").isEqualTo(98.0);
        assertThat(decimalOf(result, "selfScore")).as("自评口径加权总分（参考）").isEqualTo(96.0);
        assertThat(result).containsEntry("status", "archived");

        // 锁定（不可逆）
        Map<String, Object> locked = dataOf(post(admin, "/api/perf/results/lock", Map.of("cycleId", cycleId)));
        assertThat(locked).containsEntry("status", "locked").containsEntry("lockedCount", 1);
        assertThat(String.valueOf(dataOf(get(admin, "/api/perf/cycles/" + cycleId)).get("status")))
                .isEqualTo("locked");

        // 锁定后编辑被拒，提示明确
        ResponseEntity<Map<String, Object>> rejected = put(employee, "/api/perf/scores/self",
                scoreBody(cycleId, itemCollab, 70, "锁定后尝试改自评"));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message"))).contains("锁定");

        // 锁定后修改只能走申诉：提交 → 通过 → 定向改分并留痕
        Long resultId = ((Number) result.get("id")).longValue();
        Long appealId = idOf(post(employee, "/api/perf/appeals",
                Map.of("resultId", resultId, "reason", "自评与上级评偏差较大，申请复核")));
        Map<String, Object> handled = dataOf(put(admin, "/api/perf/appeals/" + appealId + "/handle",
                Map.of("approved", true, "comment", "复核通过，调整总分", "afterScore", 88)));
        assertThat(handled).containsEntry("appealStatus", "approved").containsEntry("applied", true);
        assertThat(decimalOf(dataOf(get(admin, "/api/perf/results/" + resultId)), "totalScore"))
                .as("申诉是锁定后唯一改分通道").isEqualTo(88.0);
    }

    // ------------------------------------------------------------------
    // ② DoD：可归集指标下钻可追溯到 KR / 任务清单 + 校准量（D-06.3 展示）
    // ------------------------------------------------------------------

    @Test
    void previewDrillsDownToKrsTasksAndCalibration() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);

        Long objectiveId = createObjective(admin, suffix);
        Long krId = createQuantitativeKr(admin, objectiveId, suffix, 100, 40);
        // 自动值 40%，人工校准到 50%（偏离 10 个百分点，允许）→ D-06.3 要求下钻能看到校准量与原因
        Map<String, Object> progressBody = new LinkedHashMap<>();
        progressBody.put("progress", 50);
        progressBody.put("reason", "负责人校准：口径调整");
        assertThat(put(admin, "/api/okr/objectives/" + objectiveId + "/krs/" + krId + "/progress", progressBody)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Long taskId = createTask(admin, "下钻任务" + suffix, null, ON_TIME_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "完成");

        Map<String, Object> preview = employeeCollect(admin, "/api/perf/collect/preview", cycleId, employeeId);
        Map<String, Object> krItem = itemOf(preview, "kr_achievement");
        assertThat(decimalOf(krItem, "autoScore")).as("校准后的 KR 进度参与归集").isEqualTo(50.0);
        List<Map<String, Object>> krSources = listValue(krItem.get("sources"));
        assertThat(krSources).hasSize(1);
        assertThat(((Number) krSources.get(0).get("krId")).longValue()).isEqualTo(krId);
        assertThat(decimalOf(krSources.get(0), "calibrationTo")).as("下钻展示校准量").isEqualTo(50.0);
        assertThat(String.valueOf(krSources.get(0).get("calibrationReason"))).contains("口径调整");

        Map<String, Object> taskItem = itemOf(preview, "task_completion");
        List<Map<String, Object>> taskSources = listValue(taskItem.get("sources"));
        assertThat(taskSources).hasSize(1);
        assertThat(((Number) taskSources.get(0).get("taskId")).longValue()).isEqualTo(taskId);
        assertThat(taskSources.get(0)).containsEntry("onTime", true);

        assertThat(joinWarnings(preview)).contains("人工校准").contains("口径调整");
        assertThat(String.valueOf(dataOf(get(admin, "/api/perf/cycles/" + cycleId)).get("status"))).isEqualTo("active");
        assertThat(objectiveId).isNotNull();
    }

    // ------------------------------------------------------------------
    // ③ R3：重算幂等（同周期重算两次结果一致、system 行不重复）
    // ------------------------------------------------------------------

    @Test
    void recalculateIsIdempotent() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);
        Long taskId = createTask(admin, "幂等任务" + suffix, krId, ON_TIME_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "完成");

        Map<String, Object> first = employeeCollect(admin, "/api/perf/collect/recalculate", cycleId, employeeId);
        Map<String, Object> second = employeeCollect(admin, "/api/perf/collect/recalculate", cycleId, employeeId);

        assertThat(decimalOf(second, "previewTotal")).isEqualTo(decimalOf(first, "previewTotal"));
        assertThat(decimalOf(itemOf(second, "kr_achievement"), "autoScore"))
                .isEqualTo(decimalOf(itemOf(first, "kr_achievement"), "autoScore"));
        Integer systemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM perf_score WHERE cycle_id = ? AND employee_id = ? AND rater_type = 'system'",
                Integer.class, cycleId, employeeId);
        assertThat(systemRows).as("重算覆盖而非累加：system 行数应等于可归集指标项数 3").isEqualTo(3);
        Integer totalScoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM perf_score WHERE cycle_id = ? AND employee_id = ?", Integer.class, cycleId, employeeId);
        assertThat(totalScoreRows).as("两次重算不新增行").isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // ④ R2 / R6：指标字典四要素硬门槛、模板权重和必须 100
    // ------------------------------------------------------------------

    @Test
    void metricDictionaryAndTemplateWeightGates() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // R2 四要素可查
        List<Map<String, Object>> metrics = listOf(get(admin, "/api/perf/metrics"));
        assertThat(metrics).extracting(row -> row.get("metricCode"))
                .contains("kr_achievement", "task_completion", "time_compliance");
        for (Map<String, Object> metric : metrics) {
            assertThat(metric).containsKeys("sourceModule", "formula", "timeRange", "missingPolicy");
            assertThat(String.valueOf(metric.get("sourceModule"))).contains("M0");
        }

        // R2：未登记指标的模板项被拒（未登记不得上线）
        Map<String, Object> unknownMetricTemplate = new LinkedHashMap<>();
        unknownMetricTemplate.put("templateCode", "IT-PERF-G-BAD-" + suffix);
        unknownMetricTemplate.put("templateName", "未登记指标模板" + suffix);
        unknownMetricTemplate.put("status", "draft");
        unknownMetricTemplate.put("items", List.of(
                itemBody("kr_achievement", "KR 达成率", "collectable", "unknown_metric", 100, null)));
        ResponseEntity<Map<String, Object>> unknownMetric = post(admin, "/api/perf/templates", unknownMetricTemplate);
        assertThat(unknownMetric.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(unknownMetric.getBody().get("message"))).contains("指标字典");

        // R6：启用态权重合计 90% 被拒
        Map<String, Object> badWeightTemplate = new LinkedHashMap<>();
        badWeightTemplate.put("templateCode", "IT-PERF-G-90-" + suffix);
        badWeightTemplate.put("templateName", "权重不足模板" + suffix);
        badWeightTemplate.put("status", "enabled");
        badWeightTemplate.put("items", List.of(
                itemBody("kr_achievement", "KR 达成率", "collectable", "kr_achievement", 50, null),
                itemBody("task_completion", "任务完成率", "collectable", "task_completion", 20, null),
                itemBody("time_compliance", "时限达标率", "collectable", "time_compliance", 10, null),
                itemBody("collab_attitude", "协作与态度", "subjective", null, 10, "协同")
        ));
        ResponseEntity<Map<String, Object>> badWeight = post(admin, "/api/perf/templates", badWeightTemplate);
        assertThat(badWeight.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(badWeight.getBody().get("message"))).contains("100%");

        // 开周期时校验模板完整性：草稿模板（权重 100 但未启用）不能开启周期
        Long draftTemplate = createTemplate(admin, suffix + "-draft", false);
        Long cycleId = createCycle(admin, suffix, draftTemplate, false);
        ResponseEntity<Map<String, Object>> open = put(admin, "/api/perf/cycles/" + cycleId + "/status",
                Map.of("status", "active"));
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(open.getBody().get("message"))).contains("未启用");
    }

    // ------------------------------------------------------------------
    // ⑤ D-06.1 逾期不扣减 KR 进度，逾期由时限达标率承接
    // ------------------------------------------------------------------

    @Test
    void overdueTaskStillCountsInKrProgressButFailsTimeCompliance() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);

        // 截止日已过（2026-10-01）后才完成（2026-10-25）：逾期任务
        Long taskId = createTask(admin, "逾期任务" + suffix, krId, OVERDUE_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "逾期后完成");

        // D-06.1：KR 进度不因逾期扣减，仍为 1/1 = 100%
        Map<String, Object> kr = krDetail(admin, objectiveId, krId);
        assertThat(decimalOf(kr, "progress")).as("逾期任务完成仍计入 KR 进度（不扣减）").isEqualTo(100.0);
        assertThat(intOf(kr, "doneTaskCount")).isEqualTo(1);

        Map<String, Object> preview = employeeCollect(admin, "/api/perf/collect/preview", cycleId, employeeId);
        assertThat(decimalOf(itemOf(preview, "kr_achievement"), "autoScore")).isEqualTo(100.0);
        assertThat(decimalOf(itemOf(preview, "task_completion"), "autoScore")).isEqualTo(100.0);
        assertThat(decimalOf(itemOf(preview, "time_compliance"), "autoScore"))
                .as("逾期由时限达标率单独承接：0/1 按时 = 0%").isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // ⑥ D-06.2 返工回退：归集取周期末快照，不取历史峰值
    // ------------------------------------------------------------------

    @Test
    void reworkFallsBackToSnapshotInsteadOfHistoricalPeak() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);
        Long taskId = createTask(admin, "返工任务" + suffix, krId, ON_TIME_DUE_DATE);

        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "先完成");
        Map<String, Object> afterDone = employeeCollect(admin, "/api/perf/collect/recalculate", cycleId, employeeId);
        assertThat(decimalOf(itemOf(afterDone, "task_completion"), "autoScore")).isEqualTo(100.0);
        assertThat(decimalOf(itemOf(afterDone, "kr_achievement"), "autoScore")).isEqualTo(100.0);

        // 返工：done → in_progress（M03 允许的回退，事件触发 M02 进度回退）
        transitionTask(admin, taskId, "in_progress", "验收不通过，返工");
        assertThat(decimalOf(krDetail(admin, objectiveId, krId), "progress")).as("KR 进度实时回落").isEqualTo(0.0);

        Map<String, Object> afterRework = employeeCollect(admin, "/api/perf/collect/recalculate", cycleId, employeeId);
        assertThat(decimalOf(itemOf(afterRework, "task_completion"), "autoScore"))
                .as("归集取回落后的快照，而非 100% 历史峰值").isEqualTo(0.0);
        assertThat(decimalOf(itemOf(afterRework, "kr_achievement"), "autoScore")).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // ⑦ D-06.3 校准限额：超 ±20 个百分点被拒
    // ------------------------------------------------------------------

    @Test
    void calibrationBeyondTwentyPointsIsRejected() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createQuantitativeKr(admin, objectiveId, suffix, 100, 40);
        String progressUrl = "/api/okr/objectives/" + objectiveId + "/krs/" + krId + "/progress";

        // 自动 40%，校准到 50%（偏离 10）→ 允许
        Map<String, Object> within = new LinkedHashMap<>();
        within.put("progress", 50);
        within.put("reason", "小范围校准");
        assertThat(put(admin, progressUrl, within).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 校准到 65%（相对自动值 40 偏离 25 个百分点）→ 拒绝，提示含 D-06.3 与 ±20
        Map<String, Object> beyond = new LinkedHashMap<>();
        beyond.put("progress", 65);
        beyond.put("reason", "大幅上调");
        ResponseEntity<Map<String, Object>> rejected = put(admin, progressUrl, beyond);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message")))
                .contains("D-06.3").contains("±20").contains("25");

        // 下调同理：自动 40% 校准到 15%（偏离 25）被拒
        Map<String, Object> down = new LinkedHashMap<>();
        down.put("progress", 15);
        down.put("reason", "大幅下调");
        ResponseEntity<Map<String, Object>> rejectedDown = put(admin, progressUrl, down);
        assertThat(rejectedDown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejectedDown.getBody().get("message"))).contains("±20");
    }

    // ------------------------------------------------------------------
    // ⑧ D-06.4 并发：同一 KR 两个任务并发完成，进度必须正确
    // ------------------------------------------------------------------

    @Test
    void concurrentTaskCompletionKeepsKrProgressConsistent() throws Exception {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);

        Long taskA = createTask(admin, "并发任务A" + suffix, krId, ON_TIME_DUE_DATE);
        Long taskB = createTask(admin, "并发任务B" + suffix, krId, ON_TIME_DUE_DATE);
        transitionTask(admin, taskA, "in_progress", null);
        transitionTask(admin, taskB, "in_progress", null);
        assertThat(decimalOf(krDetail(admin, objectiveId, krId), "progress")).isEqualTo(0.0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (Long taskId : List.of(taskA, taskB)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return put(admin, "/api/task/tasks/" + taskId + "/status",
                            Map.of("toStatus", "done", "opinion", "并发完成"));
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                assertThat(future.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> kr = krDetail(admin, objectiveId, krId);
        assertThat(intOf(kr, "taskCount")).isEqualTo(2);
        assertThat(intOf(kr, "doneTaskCount")).as("两个并发完成事件都不得丢失").isEqualTo(2);
        assertThat(decimalOf(kr, "progress")).as("行锁串行化后最终必须为 2/2 = 100%").isEqualTo(100.0);

        Map<String, Object> preview = employeeCollect(admin, "/api/perf/collect/recalculate", cycleId, employeeId);
        assertThat(decimalOf(itemOf(preview, "kr_achievement"), "autoScore")).isEqualTo(100.0);
    }

    // ------------------------------------------------------------------
    // ⑨ D-06.5 缺失值：默认 0 + 显式标记 not_applicable 后按剩余权重归一化
    // ------------------------------------------------------------------

    @Test
    void missingDataDefaultsToZeroThenNotApplicableNormalizesWeight() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long itemKr = itemIdOf(admin, templateId, "kr_achievement");

        // 员工无 KR（有任务且按时完成）→ KR 达成率缺失默认 0，但不影响任务类指标
        Long taskId = createTask(admin, "无KR员工任务" + suffix, null, ON_TIME_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "完成");

        Map<String, Object> before = employeeCollect(admin, "/api/perf/collect/preview", cycleId, employeeId);
        Map<String, Object> krItem = itemOf(before, "kr_achievement");
        assertThat(decimalOf(krItem, "autoScore")).as("无数据默认取 0").isEqualTo(0.0);
        assertThat(krItem).containsEntry("missing", true);
        assertThat(joinWarnings(before)).as("未标记缺失需在预览提示").contains("未标记不适用").contains("按 0 计");
        // (0*40 + 100*30 + 100*10) / 80 = 50
        assertThat(decimalOf(before, "previewTotal")).isEqualTo(50.0);

        // HR/管理者显式标记不适用（D-06.5 例外通道，必须写原因）
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("cycleId", cycleId);
        mark.put("employeeId", employeeId);
        mark.put("itemId", itemKr);
        mark.put("notApplicable", true);
        mark.put("reason", "试用期未定目标（HR 已确认）");
        assertThat(put(admin, "/api/perf/scores/not-applicable", mark).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = employeeCollect(admin, "/api/perf/collect/preview", cycleId, employeeId);
        Map<String, Object> krItemAfter = itemOf(after, "kr_achievement");
        assertThat(krItemAfter).containsEntry("notApplicable", true);
        assertThat(String.valueOf(krItemAfter.get("naReason"))).contains("试用期");
        // 剩余权重归一化：(100*30 + 100*10) / 40 = 100
        assertThat(decimalOf(after, "scoredWeight")).isEqualTo(40.0);
        assertThat(decimalOf(after, "naWeight")).isEqualTo(40.0);
        assertThat(decimalOf(after, "previewTotal")).isEqualTo(100.0);
    }

    // ------------------------------------------------------------------
    // ⑩ 归档前置校验 + 结果下钻追溯（DoD）
    // ------------------------------------------------------------------

    @Test
    void archiveRequiresManagerScoreAndResultTraceShowsSources() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long templateId = createTemplate(admin, suffix, true);
        Long cycleId = createCycle(admin, suffix, templateId, true);
        Long itemCollab = subjectiveItemId(admin, templateId, "collab_attitude");
        Long objectiveId = createObjective(admin, suffix);
        Long krId = createTaskDrivenKr(admin, objectiveId, suffix);
        Long taskId = createTask(admin, "追溯任务" + suffix, krId, ON_TIME_DUE_DATE);
        transitionTask(admin, taskId, "in_progress", null);
        transitionTask(admin, taskId, "done", "完成");

        HttpHeaders employee = bearerHeaders(login(employeeUsername, "admin123"));
        saveSelf(employee, cycleId, itemCollab, 85, "自评");
        assertThat(put(admin, "/api/perf/cycles/" + cycleId + "/status", Map.of("status", "scoring"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 主观项未上级评分 → 归档被拒且提示明确
        ResponseEntity<Map<String, Object>> rejected = post(admin, "/api/perf/results/archive",
                Map.of("cycleId", cycleId));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message"))).contains("上级评分");

        saveManager(admin, cycleId, employeeId, itemCollab, 95, "上级评价");
        assertThat(post(admin, "/api/perf/results/archive", Map.of("cycleId", cycleId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> result = resultOfCycle(admin, cycleId);
        Long resultId = ((Number) result.get("id")).longValue();

        // DoD：结果下钻可追溯到具体 KR / 任务清单
        Map<String, Object> trace = dataOf(get(admin, "/api/perf/results/" + resultId + "/trace"));
        Map<String, Object> detail = mapValue(trace.get("detail"));
        List<Map<String, Object>> items = listValue(detail.get("items"));
        assertThat(items).extracting(row -> row.get("itemCode"))
                .contains("kr_achievement", "task_completion", "time_compliance", "collab_attitude");
        Map<String, Object> krItem = items.stream()
                .filter(row -> "kr_achievement".equals(row.get("itemCode"))).findFirst().orElseThrow();
        assertThat(listValue(krItem.get("sources"))).hasSize(1);
        Map<String, Object> taskItem = items.stream()
                .filter(row -> "task_completion".equals(row.get("itemCode"))).findFirst().orElseThrow();
        assertThat(listValue(taskItem.get("sources"))).hasSize(1);
        assertThat(String.valueOf(mapValue(trace.get("result")).get("status"))).isEqualTo("archived");
    }

    // ------------------------------------------------------------------
    // 建数据辅助
    // ------------------------------------------------------------------

    private Long createTemplate(HttpHeaders headers, String suffix, boolean enabled) {
        return createTemplateWithWeight(headers, suffix, 40, enabled);
    }

    private Long createTemplateWithWeight(HttpHeaders headers, String suffix, int krWeight, boolean enabled) {
        int taskWeight = 30;
        int timeWeight = 10;
        int subjectiveWeight = 100 - krWeight - taskWeight - timeWeight;
        assertThat(subjectiveWeight).as("测试模板权重设计错误").isGreaterThan(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", "IT-PERF-T-" + suffix);
        body.put("templateName", "绩效集成测试模板" + suffix);
        body.put("description", "M05 集成测试模板");
        body.put("status", enabled ? "enabled" : "draft");
        body.put("items", List.of(
                itemBody("kr_achievement", "KR 达成率", "collectable", "kr_achievement", krWeight, null),
                itemBody("task_completion", "任务完成率", "collectable", "task_completion", taskWeight, null),
                itemBody("time_compliance", "时限达标率", "collectable", "time_compliance", timeWeight, null),
                itemBody("collab_attitude", "协作与态度", "subjective", null, subjectiveWeight, "跨团队协同与响应")
        ));
        Long templateId = idOf(post(headers, "/api/perf/templates", body));
        createdTemplateIds.add(templateId);
        return templateId;
    }

    private Map<String, Object> itemBody(String code, String name, String category, String metricCode,
                                         int weight, String scoreStandard) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemCode", code);
        item.put("itemName", name);
        item.put("itemCategory", category);
        if (metricCode != null) {
            item.put("metricCode", metricCode);
        }
        item.put("weight", weight);
        if (scoreStandard != null) {
            item.put("scoreStandard", scoreStandard);
        }
        return item;
    }

    private Long createCycle(HttpHeaders headers, String suffix, Long templateId, boolean open) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleCode", "IT-PERF-C-" + suffix);
        body.put("cycleName", "绩效集成测试周期" + suffix);
        body.put("cycleType", "quarter");
        body.put("startDate", CYCLE_START);
        body.put("endDate", CYCLE_END);
        body.put("templateId", templateId);
        body.put("remark", "M05 集成测试");
        Long cycleId = idOf(post(headers, "/api/perf/cycles", body));
        createdCycleIds.add(cycleId);
        if (open) {
            assertThat(put(headers, "/api/perf/cycles/" + cycleId + "/status", Map.of("status", "active"))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return cycleId;
    }

    private Long createObjective(HttpHeaders headers, String suffix) {
        Map<String, Object> periodBody = new LinkedHashMap<>();
        periodBody.put("periodName", "IT-PERF-P-" + suffix);
        periodBody.put("periodType", "quarter");
        periodBody.put("startDate", CYCLE_START);
        periodBody.put("endDate", CYCLE_END);
        Long periodId = idOf(post(headers, "/api/okr/periods", periodBody));
        assertThat(put(headers, "/api/okr/periods/" + periodId + "/status", Map.of("status", "active"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> objectiveBody = new LinkedHashMap<>();
        objectiveBody.put("periodId", periodId);
        objectiveBody.put("parentId", 0L);
        objectiveBody.put("objectiveTitle", "绩效归集目标" + suffix);
        objectiveBody.put("level", "company");
        objectiveBody.put("ownerId", employeeId);
        objectiveBody.put("weight", 100);
        objectiveBody.put("status", "active");
        return idOf(post(headers, "/api/okr/objectives", objectiveBody));
    }

    private Long createTaskDrivenKr(HttpHeaders headers, Long objectiveId, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", "绩效任务型KR" + suffix);
        body.put("krType", "task_driven");
        body.put("ownerId", employeeId);
        return idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
    }

    private Long createQuantitativeKr(HttpHeaders headers, Long objectiveId, String suffix,
                                      int targetValue, int currentValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", "绩效量化KR" + suffix);
        body.put("krType", "quantitative");
        body.put("targetValue", targetValue);
        body.put("currentValue", currentValue);
        body.put("direction", "positive");
        body.put("metricUnit", "%");
        body.put("ownerId", employeeId);
        return idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
    }

    private Long createTask(HttpHeaders headers, String title, Long krId, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M05 归集取数测试任务");
        if (krId != null) {
            body.put("krId", krId);
        }
        body.put("assigneeId", employeeId);
        body.put("priority", "high");
        body.put("dueDate", dueDate);
        return idOf(post(headers, "/api/task/tasks", body));
    }

    private void transitionTask(HttpHeaders headers, Long taskId, String toStatus, String opinion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toStatus", toStatus);
        if (opinion != null) {
            body.put("opinion", opinion);
        }
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status", body).getStatusCode())
                .as("任务应可流转到 " + toStatus).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> krDetail(HttpHeaders headers, Long objectiveId, Long krId) {
        return dataOf(get(headers, "/api/okr/objectives/" + objectiveId + "/krs/" + krId));
    }

    // ------------------------------------------------------------------
    // 归集/评分辅助
    // ------------------------------------------------------------------

    private Map<String, Object> employeeCollect(HttpHeaders headers, String url, Long cycleId, Long employeeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycleId);
        body.put("employeeIds", List.of(employeeId));
        Map<String, Object> data = dataOf(post(headers, url, body));
        return employeeResult(data, employeeId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> employeeResult(Map<String, Object> collectData, Long employeeId) {
        List<Map<String, Object>> employees = (List<Map<String, Object>>) collectData.get("employees");
        assertThat(employees).isNotEmpty();
        return employees.stream()
                .filter(row -> employeeId.equals(((Number) row.get("employeeId")).longValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("归集结果缺少员工：" + employeeId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemOf(Map<String, Object> employeeResult, String itemCode) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) employeeResult.get("items");
        return items.stream()
                .filter(row -> itemCode.equals(row.get("itemCode")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("归集明细缺少指标项：" + itemCode));
    }

    private String joinWarnings(Map<String, Object> employeeResult) {
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) employeeResult.get("warnings");
        return warnings == null ? "" : String.join(" | ", warnings);
    }

    private Long subjectiveItemId(HttpHeaders headers, Long templateId, String itemCode) {
        return itemIdOf(headers, templateId, itemCode);
    }

    private Long itemIdOf(HttpHeaders headers, Long templateId, String itemCode) {
        Map<String, Object> template = dataOf(get(headers, "/api/perf/templates/" + templateId));
        List<Map<String, Object>> items = listValue(template.get("items"));
        return items.stream()
                .filter(row -> itemCode.equals(row.get("itemCode")))
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("模板缺少指标项：" + itemCode));
    }

    private Map<String, Object> scoreBody(Long cycleId, Long itemId, int score, String comment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", itemId);
        item.put("score", score);
        item.put("comment", comment);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycleId);
        body.put("items", List.of(item));
        return body;
    }

    private void saveSelf(HttpHeaders headers, Long cycleId, Long itemId, int score, String comment) {
        assertThat(put(headers, "/api/perf/scores/self", scoreBody(cycleId, itemId, score, comment))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void saveManager(HttpHeaders headers, Long cycleId, Long employeeId, Long itemId, int score, String comment) {
        Map<String, Object> body = scoreBody(cycleId, itemId, score, comment);
        body.put("employeeId", employeeId);
        assertThat(put(headers, "/api/perf/scores/manager", body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> resultOfCycle(HttpHeaders headers, Long cycleId) {
        Map<String, Object> page = dataOf(get(headers, "/api/perf/results?cycleId=" + cycleId));
        List<Map<String, Object>> rows = listValue(page.get("rows"));
        assertThat(rows).as("周期应已产生绩效结果").isNotEmpty();
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // HTTP 与断言辅助
    // ------------------------------------------------------------------

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
