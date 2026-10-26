package com.teamflow.server;

import com.teamflow.perf.metric.PerfMetric;
import com.teamflow.perf.metric.PerfMetricDictionary;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M13 统计与洞察集成测试（真实 HTTP + 真实 M02/M03/M04/M05 数据链路）。
 *
 * <p>覆盖（M2 里程碑「统计口径与业务表一致」）：</p>
 * <ol>
 *   <li><b>口径一致性（最关键）</b>：M13 任务看板算出的 KR 达成率 / 任务完成率 / 时限达标率，
 *       与 M05 归集预览的对应指标值**逐项比对，差异必须为 0**；且三项指标的字典口径文字与
 *       M05 {@code PerfMetricDictionary} 逐字一致（V20 种子断言）；</li>
 *   <li>已知数据手算一致：1 个 KR（进度 40）+ 2 个顶层任务（1 个按时完成、1 个逾期未完成）
 *       → 完成率 50%、时限达标率 100%、逾期率 50%、KR 达成率 40%；</li>
 *   <li>7.1 指标字典 CRUD 与「未登记口径的指标不得上线」：代码侧未实现的口径不能上线、
 *       已上线指标下线后看板拒绝出数并提示；</li>
 *   <li>7.3 团队效能：承接量/在办与 M04 团队详情 loadSummary 同源一致，响应时长按创建→完成计算，
 *       负载均衡给出 CV 与均衡度；</li>
 *   <li>R3 数据范围：普通成员（DEPT 数据范围）只能看到本部门统计，越权查看范围外成员被拒。</li>
 * </ol>
 *
 * <p><b>隔离策略</b>：每个用例用 JDBC 建独立测试员工（用例内唯一用户名，结束后清理），
 * 因此统计只命中本用例造的数据，不受其他模块测试残留污染。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class InsightApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);
    private static final String RANGE_START = "2026-10-01";
    private static final String RANGE_END = "2026-12-31";
    /** 逾期任务截止日（早于当前时间 2026-10-26）。 */
    private static final String OVERDUE_DUE_DATE = "2026-10-01";
    /** 按时任务截止日（晚于当前时间）。 */
    private static final String ON_TIME_DUE_DATE = "2026-12-31";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final List<Long> createdCycleIds = new ArrayList<>();
    private final List<Long> createdMetricIds = new ArrayList<>();
    /** 若用例中途失败，仍尽力把被临时下线的指标恢复上线（避免污染指标字典） */
    private MetricRestore pendingMetricRestore;

    private record MetricRestore(Long id, Map<String, Object> body) {
    }

    @AfterEach
    void cleanup() {
        if (pendingMetricRestore != null) {
            try {
                put(adminHeaders(), "/api/insight/metrics/" + pendingMetricRestore.id(), pendingMetricRestore.body());
            } catch (RuntimeException ignored) {
                // 兜底恢复失败不覆盖真实断言错误
            }
            pendingMetricRestore = null;
        }
        for (Long cycleId : createdCycleIds) {
            jdbcTemplate.update("DELETE FROM perf_score WHERE cycle_id = ?", cycleId);
            jdbcTemplate.update("DELETE FROM perf_result WHERE cycle_id = ?", cycleId);
            jdbcTemplate.update("DELETE FROM perf_cycle WHERE id = ?", cycleId);
        }
        for (Long templateId : createdTemplateIds) {
            jdbcTemplate.update("DELETE FROM perf_template_item WHERE template_id = ?", templateId);
            jdbcTemplate.update("DELETE FROM perf_template WHERE id = ?", templateId);
        }
        for (Long teamId : createdTeamIds) {
            jdbcTemplate.update("UPDATE team_team SET deleted = 1, status = 'dissolved' WHERE id = ?", teamId);
        }
        for (Long metricId : createdMetricIds) {
            jdbcTemplate.update("DELETE FROM insight_metric WHERE id = ?", metricId);
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        createdCycleIds.clear();
        createdTemplateIds.clear();
        createdTeamIds.clear();
        createdMetricIds.clear();
        createdUserIds.clear();
    }

    // ------------------------------------------------------------------
    // ① 7.1 指标字典：CRUD、与 M05 同口径、未登记不得上线（含出数闸门）
    // ------------------------------------------------------------------

    @Test
    void metricDictionaryGatesAndM05Consistency() {
        HttpHeaders admin = adminHeaders();

        // 9 个已实现指标全部登记且上线
        Map<String, Object> page = dataOf(get(admin, "/api/insight/metrics?pageNum=1&pageSize=100"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) page.get("rows");
        assertThat(metrics).hasSizeGreaterThanOrEqualTo(9);
        assertThat(metrics).extracting(row -> row.get("metricCode")).contains(
                "kr_achievement", "task_completion", "time_compliance",
                "task_overdue_rate", "task_period_trend", "task_member_distribution",
                "team_member_load", "team_response_time", "team_load_balance");
        for (Map<String, Object> metric : metrics) {
            assertThat(metric).containsKeys("metricDefinition", "formula", "sourceModule", "sourceDetail",
                    "timeGrain", "missingPolicy", "refreshFrequency", "cacheTtlSeconds", "status", "ownerName");
            assertThat(String.valueOf(metric.get("status"))).as("种子指标应已上线").isEqualTo("online");
        }

        // 与 M05 指标字典同码同口径：formula / timeGrain / missingPolicy 逐字一致
        assertSameDefinitionAsM05(metrics, PerfMetricDictionary.KR_ACHIEVEMENT);
        assertSameDefinitionAsM05(metrics, PerfMetricDictionary.TASK_COMPLETION);
        assertSameDefinitionAsM05(metrics, PerfMetricDictionary.TIME_COMPLIANCE);

        // R1 代码侧闸门：M13 没有实现的口径不能上线
        Map<String, Object> unregistered = metricBody("unknown_metric_demo" + nextSuffix(), "online", "task");
        ResponseEntity<Map<String, Object>> rejectedOnline = post(admin, "/api/insight/metrics", unregistered);
        assertThat(rejectedOnline.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejectedOnline.getBody().get("message")))
                .contains("未登记口径").contains("不得上线");

        // 草稿可登记（未上线不参与出数），已上线指标不可删除，草稿可删除
        Map<String, Object> draft = metricBody("draft_metric_demo" + nextSuffix(), "draft", "task");
        Long draftId = idOf(post(admin, "/api/insight/metrics", draft));
        createdMetricIds.add(draftId);
        Map<String, Object> draftDetail = dataOf(get(admin, "/api/insight/metrics/" + draftId));
        assertThat(draftDetail).containsEntry("statusLabel", "草稿").containsEntry("implemented", false);
        assertThat(delete(admin, "/api/insight/metrics/" + draftId).getStatusCode()).isEqualTo(HttpStatus.OK);
        createdMetricIds.remove(draftId);

        // 编码唯一
        Map<String, Object> duplicate = metricBody("kr_achievement", "draft", "task");
        ResponseEntity<Map<String, Object>> duplicateResponse = post(admin, "/api/insight/metrics", duplicate);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(duplicateResponse.getBody().get("message"))).contains("已存在");

        // R1 出数闸门：把「逾期率」下线 → 看板拒绝出数；恢复上线 → 正常（finally 保证恢复原口径）
        Map<String, Object> overdueMetric = metricByCode(metrics, "task_overdue_rate");
        Long overdueMetricId = ((Number) overdueMetric.get("id")).longValue();
        Map<String, Object> originalDetail = dataOf(get(admin, "/api/insight/metrics/" + overdueMetricId));
        Map<String, Object> restoreBody = bodyFromMetric(originalDetail, "online");
        try {
            pendingMetricRestore = new MetricRestore(overdueMetricId, restoreBody);
            assertThat(put(admin, "/api/insight/metrics/" + overdueMetricId,
                    bodyFromMetric(originalDetail, "offline")).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<Map<String, Object>> blocked = get(admin,
                    "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END + "&assigneeId=1");
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(blocked.getBody().get("message")))
                    .contains("未登记口径").contains("未上线");
        } finally {
            put(admin, "/api/insight/metrics/" + overdueMetricId, restoreBody);
            pendingMetricRestore = null;
        }
        assertThat(get(admin, "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END
                + "&assigneeId=1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // ② 7.2 已知数据手算一致：1 KR（40%）+ 2 任务（1 按时完成、1 逾期未完成）
    // ------------------------------------------------------------------

    @Test
    void taskBoardMatchesHandCalculatedValues() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        TestEmployee employee = createEmployee(5L, null, suffix);
        createKnownData(admin, employee, suffix);

        Map<String, Object> overview = dataOf(get(admin,
                "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END
                        + "&assigneeId=" + employee.id()));

        Map<String, Object> totals = mapValue(overview.get("totals"));
        assertThat(longValue(totals, "taskTotal")).isEqualTo(2);
        assertThat(longValue(totals, "taskDone")).isEqualTo(1);
        assertThat(longValue(totals, "taskOnTime")).isEqualTo(1);
        assertThat(longValue(totals, "taskOverdue")).as("未完成且已过截止 = 逾期").isEqualTo(1);
        assertThat(longValue(totals, "krCount")).isEqualTo(1);
        assertThat(longValue(totals, "memberCount")).isEqualTo(1);

        Map<String, Object> metrics = mapValue(overview.get("metrics"));
        // 完成率 1/2 = 50%；时限达标率 1/1 = 100%；KR 达成率 40/100 = 40%；逾期率 1/2 = 50%
        assertMetricValue(metrics, "task_completion", 50.0);
        assertMetricValue(metrics, "time_compliance", 100.0);
        assertMetricValue(metrics, "kr_achievement", 40.0);
        assertMetricValue(metrics, "task_overdue_rate", 50.0);

        List<Map<String, Object>> members = listValue(overview.get("members"));
        assertThat(members).hasSize(1);
        Map<String, Object> member = members.get(0);
        assertThat(((Number) member.get("userId")).longValue()).isEqualTo(employee.id());
        assertThat(decimalValue(member, "taskCompletionRate")).isEqualTo(50.0);
        assertThat(decimalValue(member, "timeComplianceRate")).isEqualTo(100.0);
        assertThat(decimalValue(member, "krAchievement")).isEqualTo(40.0);

        // 周期趋势：截止日 2026-10-01 的任务到期且未按期完成；2026-12-31 有到期任务
        List<Map<String, Object>> trend = listValue(overview.get("trend"));
        Map<String, Object> overdueDay = trend.stream()
                .filter(row -> OVERDUE_DUE_DATE.equals(String.valueOf(row.get("date")))).findFirst().orElseThrow();
        assertThat(longValue(overdueDay, "dueCount")).isEqualTo(1);
        assertThat(longValue(overdueDay, "lateDueCount")).isEqualTo(1);
        Map<String, Object> onTimeDay = trend.stream()
                .filter(row -> ON_TIME_DUE_DATE.equals(String.valueOf(row.get("date")))).findFirst().orElseThrow();
        assertThat(longValue(onTimeDay, "dueCount")).isEqualTo(1);
        assertThat(longValue(onTimeDay, "lateDueCount")).isZero();
        assertThat(trend.stream().mapToLong(row -> longValue(row, "doneCount")).sum())
                .as("趋势中已完成任务合计").isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ②之一 R7：空数据不打错——比例类 0+missing+说明，均值/趋势有明确语义
    // ------------------------------------------------------------------

    @Test
    void emptyDataReturnsZeroWithExplicitMissingFlags() {
        HttpHeaders admin = adminHeaders();
        TestEmployee empty = createEmployee(5L, null, nextSuffix() + "-empty");

        Map<String, Object> overview = dataOf(get(admin,
                "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END
                        + "&assigneeId=" + empty.id()));

        Map<String, Object> totals = mapValue(overview.get("totals"));
        assertThat(longValue(totals, "taskTotal")).isZero();
        assertThat(longValue(totals, "krCount")).isZero();
        assertThat(longValue(totals, "memberCount")).isEqualTo(1);
        assertThat(longValue(totals, "visibleUserCount")).isEqualTo(1);

        Map<String, Object> metrics = mapValue(overview.get("metrics"));
        for (String code : List.of("task_completion", "time_compliance", "kr_achievement", "task_overdue_rate")) {
            Map<String, Object> metric = mapValue(metrics.get(code));
            assertThat(decimalValue(metric, "value")).as("空数据比例类指标按 0 返回：" + code).isZero();
            assertThat(metric).as("空数据必须显式标记 missing：" + code).containsEntry("missing", true);
            assertThat(String.valueOf(metric.get("note"))).as("空数据必须有口径说明：" + code).isNotBlank();
        }
        assertThat(String.valueOf(mapValue(metrics.get("task_completion")).get("note"))).contains("按 0 计");
        assertThat(String.valueOf(mapValue(metrics.get("task_overdue_rate")).get("note"))).contains("no_data");

        List<Map<String, Object>> members = listValue(overview.get("members"));
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("missing", true);
        assertThat(members.get(0).get("noDataNote")).isNotNull();

        List<Map<String, Object>> trend = listValue(overview.get("trend"));
        assertThat(trend).isNotEmpty();
        assertThat(trend.stream().mapToLong(row -> longValue(row, "dueCount")).sum()).isZero();
        assertThat(trend.stream().mapToLong(row -> longValue(row, "doneCount")).sum()).isZero();
    }

    // ------------------------------------------------------------------
    // ③ 最关键验收：M13 口径与 M05 归集预览逐项一致（差异为 0）
    // ------------------------------------------------------------------

    @Test
    void metricsMatchM05CollectPreviewExactly() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        TestEmployee employeeA = createEmployee(5L, null, suffix + "-a");
        TestEmployee employeeB = createEmployee(5L, null, suffix + "-b");

        Map<Long, Map<String, Object>> m05Values = new LinkedHashMap<>();

        // 员工 A：1 个量化 KR（40%）+ 2 个任务（1 按时完成 / 1 逾期未完成）
        createKnownData(admin, employeeA, suffix + "-a");
        // 员工 B：1 个任务驱动型 KR（无关联任务 → 0）+ 1 个逾期完成的任务（完成率 100%、时限达标率 0%）
        Long periodIdB = createActivePeriod(admin, suffix + "-b");
        Long objectiveB = createObjective(admin, periodIdB, employeeB.id(), suffix + "-b");
        createTaskDrivenKr(admin, objectiveB, employeeB.id(), suffix + "-b");
        Long lateTask = createTask(admin, "逾期完成的任务" + suffix + "-b", null, employeeB.id(), OVERDUE_DUE_DATE);
        transitionTask(admin, lateTask, "in_progress", null);
        transitionTask(admin, lateTask, "done", "逾期完成");

        // M05：建标准模板 + 周期，取两位员工的归集预览值
        Long templateId = createM05Template(admin, suffix);
        Long cycleId = createM05Cycle(admin, suffix, templateId);
        Map<String, Object> previewBody = new LinkedHashMap<>();
        previewBody.put("cycleId", cycleId);
        previewBody.put("employeeIds", List.of(employeeA.id(), employeeB.id()));
        Map<String, Object> preview = dataOf(post(admin, "/api/perf/collect/preview", previewBody));
        for (Map<String, Object> employeeResult : listValue(preview.get("employees"))) {
            Long employeeId = ((Number) employeeResult.get("employeeId")).longValue();
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map<String, Object> item : listValue(employeeResult.get("items"))) {
                values.put(String.valueOf(item.get("itemCode")), item.get("autoScore"));
            }
            m05Values.put(employeeId, values);
        }
        assertThat(m05Values).containsKeys(employeeA.id(), employeeB.id());

        // M13：同一区间、同一成员，逐项比对三个同名指标（差异必须为 0）
        for (TestEmployee employee : List.of(employeeA, employeeB)) {
            Map<String, Object> overview = dataOf(get(admin,
                    "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END
                            + "&assigneeId=" + employee.id()));
            Map<String, Object> metrics = mapValue(overview.get("metrics"));
            Map<String, Object> m05 = m05Values.get(employee.id());
            for (String code : List.of("kr_achievement", "task_completion", "time_compliance")) {
                double insightValue = decimalValue(mapValue(metrics.get(code)), "value");
                double m05Value = ((Number) m05.get(code)).doubleValue();
                assertThat(Math.abs(insightValue - m05Value))
                        .as("M13「" + code + "」必须与 M05 归集预览一致（employeeId=" + employee.id()
                                + "，M13=" + insightValue + "，M05=" + m05Value + "）")
                        .isLessThanOrEqualTo(0.0001);
            }
        }
    }

    // ------------------------------------------------------------------
    // ④ R3 数据范围：普通成员只能看到本部门统计
    // ------------------------------------------------------------------

    @Test
    void dataScopeRestrictsStatisticsForOrdinaryMember() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        // 测试登录账号：业务管理员（role 3，DEPT 数据范围）归属部门 6
        TestEmployee scopeUser = createEmployee(6L, 3L, suffix + "-scope");
        TestEmployee dept5Employee = createEmployee(5L, null, suffix + "-d5");
        TestEmployee dept6Employee = createEmployee(6L, null, suffix + "-d6");
        createTask(admin, "部门5任务" + suffix, null, dept5Employee.id(), ON_TIME_DUE_DATE);
        createTask(admin, "部门6任务" + suffix, null, dept6Employee.id(), ON_TIME_DUE_DATE);

        HttpHeaders scoped = bearerHeaders(login(scopeUser.username(), "admin123"));

        // 未指定成员：只能看到本部门（dept 6）成员
        Map<String, Object> overview = dataOf(get(scoped,
                "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END));
        Map<String, Object> scope = mapValue(overview.get("scope"));
        assertThat(scope).containsEntry("allScope", false);
        List<Map<String, Object>> members = listValue(overview.get("members"));
        assertThat(members).isNotEmpty();
        assertThat(members.stream().map(row -> ((Number) row.get("userId")).longValue()))
                .as("不得出现部门 5 的成员").doesNotContain(dept5Employee.id(), 1L);
        assertThat(members.stream().map(row -> ((Number) row.get("deptId")).longValue()))
                .as("范围内成员均属部门 6").allMatch(deptId -> deptId == 6L);
        assertThat(members.stream().map(row -> ((Number) row.get("userId")).longValue()))
                .contains(dept6Employee.id());

        // 越权指定范围外成员：拒绝并说明数据范围
        ResponseEntity<Map<String, Object>> forbidden = get(scoped,
                "/api/insight/tasks/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END
                        + "&assigneeId=" + dept5Employee.id());
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(forbidden.getBody().get("message"))).contains("数据范围");
    }

    // ------------------------------------------------------------------
    // ⑤ 7.3 团队效能：承接量/在办与 M04 一致，响应时长与负载均衡可核算
    // ------------------------------------------------------------------

    @Test
    void teamEfficiencyMatchesM04LoadAndComputesResponseTime() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        TestEmployee busy = createEmployee(5L, null, suffix + "-busy");
        TestEmployee idle = createEmployee(5L, null, suffix + "-idle");

        // busy：1 个按时完成的任务（创建时间回拨 48 小时）+ 1 个进行中任务；idle：0 个任务
        Long doneTask = createTask(admin, "已完成任务" + suffix, null, busy.id(), ON_TIME_DUE_DATE);
        transitionTask(admin, doneTask, "in_progress", null);
        transitionTask(admin, doneTask, "done", "按时完成");
        jdbcTemplate.update("UPDATE task_task SET create_time = DATE_SUB(NOW(), INTERVAL 48 HOUR) WHERE id = ?", doneTask);
        Long openTask = createTask(admin, "进行中任务" + suffix, null, busy.id(), ON_TIME_DUE_DATE);
        transitionTask(admin, openTask, "in_progress", null);

        // M04：建团队（busy 为负责人，避免把 admin 的历史任务算进负载）→ 加入另一名成员
        Map<String, Object> teamBody = new LinkedHashMap<>();
        teamBody.put("teamName", "M13 效能测试团队" + suffix);
        teamBody.put("teamCode", "IT-M13-" + suffix);
        teamBody.put("deptId", 5);
        teamBody.put("ownerId", busy.id());
        Long teamId = idOf(post(admin, "/api/team/teams", teamBody));
        createdTeamIds.add(teamId);
        // busy 已由创建团队时的 ownerId 自动写入为 OWNER，这里只补一名普通成员
        assertThat(post(admin, "/api/team/teams/" + teamId + "/members",
                Map.of("userId", idle.id(), "teamRole", "MEMBER")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> efficiency = dataOf(get(admin,
                "/api/insight/teams/efficiency?teamId=" + teamId
                        + "&startDate=" + RANGE_START + "&endDate=" + RANGE_END));

        assertThat(String.valueOf(mapValue(efficiency.get("team")).get("teamName"))).contains("M13 效能测试团队");
        List<Map<String, Object>> members = listValue(efficiency.get("members"));
        assertThat(members).hasSize(2);
        Map<String, Object> busyRow = members.stream()
                .filter(row -> ((Number) row.get("userId")).longValue() == busy.id()).findFirst().orElseThrow();
        Map<String, Object> idleRow = members.stream()
                .filter(row -> ((Number) row.get("userId")).longValue() == idle.id()).findFirst().orElseThrow();
        assertThat(longValue(busyRow, "taskTotal")).isEqualTo(2);
        assertThat(longValue(busyRow, "taskInProgress")).isEqualTo(1);
        assertThat(longValue(idleRow, "taskTotal")).isZero();
        assertThat(idleRow.get("responseHours")).as("无已完成任务时响应时长为 null（非 0）").isNull();
        double responseHours = decimalValue(busyRow, "responseHours");
        assertThat(responseHours).as("创建→完成约 48 小时").isBetween(47.0, 49.0);
        assertThat(busyRow).containsEntry("sampled", false);

        // 承接量/在办与 M04 团队详情 loadSummary 同源一致
        Map<String, Object> teamDetail = dataOf(get(admin, "/api/team/teams/" + teamId));
        Map<String, Object> loadSummary = mapValue(teamDetail.get("loadSummary"));
        Map<String, Object> totals = mapValue(efficiency.get("totals"));
        assertThat(longValue(totals, "taskTotal")).isEqualTo(longValue(loadSummary, "taskTotal")).isEqualTo(2);
        assertThat(longValue(totals, "taskInProgress")).isEqualTo(longValue(loadSummary, "taskInProgress")).isEqualTo(1);
        assertThat(longValue(totals, "completedTaskCount")).isEqualTo(1);

        // 负载均衡：承接量 [2, 0] → 均值 1、极差 2、CV=1.00、失衡
        Map<String, Object> loadBalance = mapValue(efficiency.get("loadBalance"));
        assertThat(longValue(loadBalance, "memberCount")).isEqualTo(2);
        assertThat(decimalValue(loadBalance, "avgTasks")).isEqualTo(1.0);
        assertThat(longValue(loadBalance, "maxTasks")).isEqualTo(2);
        assertThat(longValue(loadBalance, "minTasks")).isZero();
        assertThat(longValue(loadBalance, "range")).isEqualTo(2);
        assertThat(decimalValue(loadBalance, "cv")).isEqualTo(1.0);
        assertThat(loadBalance).containsEntry("level", "unbalanced");
        assertThat(((Number) loadBalance.get("busiestUserId")).longValue()).isEqualTo(busy.id());

        Map<String, Object> metrics = mapValue(efficiency.get("metrics"));
        assertThat(decimalValue(mapValue(metrics.get("team_member_load")), "value")).isEqualTo(2.0);
        assertThat(decimalValue(mapValue(metrics.get("team_response_time")), "value")).isBetween(47.0, 49.0);
        assertThat(decimalValue(mapValue(metrics.get("team_load_balance")), "value")).isEqualTo(1.0);

        // 团队选项可读
        assertThat(listOf(get(admin, "/api/insight/teams/options")))
                .extracting(row -> ((Number) row.get("id")).longValue()).contains(teamId);
    }

    // ------------------------------------------------------------------
    // 造数辅助（全部走真实 HTTP：M02 目标/KR、M03 任务、M05 模板/周期）
    // ------------------------------------------------------------------

    /** 已知数据：1 个量化 KR（目标 100 / 当前 40）+ 2 个顶层任务（1 按时完成、1 逾期未完成）。 */
    private void createKnownData(HttpHeaders headers, TestEmployee employee, String suffix) {
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, employee.id(), suffix);
        createQuantitativeKr(headers, objectiveId, employee.id(), suffix, 100, 40);
        Long doneTask = createTask(headers, "按时完成任务" + suffix, null, employee.id(), ON_TIME_DUE_DATE);
        transitionTask(headers, doneTask, "in_progress", null);
        transitionTask(headers, doneTask, "done", "按时完成");
        createTask(headers, "逾期未完成任务" + suffix, null, employee.id(), OVERDUE_DUE_DATE);
    }

    private Long createActivePeriod(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodName", "IT-M13-P-" + suffix);
        body.put("periodType", "quarter");
        body.put("startDate", RANGE_START);
        body.put("endDate", RANGE_END);
        Long periodId = idOf(post(headers, "/api/okr/periods", body));
        assertThat(put(headers, "/api/okr/periods/" + periodId + "/status", Map.of("status", "active"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return periodId;
    }

    private Long createObjective(HttpHeaders headers, Long periodId, Long ownerId, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodId", periodId);
        body.put("parentId", 0L);
        body.put("objectiveTitle", "M13 统计口径目标" + suffix);
        body.put("level", "company");
        body.put("ownerId", ownerId);
        body.put("weight", 100);
        body.put("status", "active");
        return idOf(post(headers, "/api/okr/objectives", body));
    }

    private Long createQuantitativeKr(HttpHeaders headers, Long objectiveId, Long ownerId, String suffix,
                                      int targetValue, int currentValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", "量化KR" + suffix);
        body.put("krType", "quantitative");
        body.put("targetValue", targetValue);
        body.put("currentValue", currentValue);
        body.put("direction", "positive");
        body.put("ownerId", ownerId);
        return idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
    }

    private Long createTaskDrivenKr(HttpHeaders headers, Long objectiveId, Long ownerId, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", "任务型KR" + suffix);
        body.put("krType", "task_driven");
        body.put("ownerId", ownerId);
        return idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
    }

    private Long createTask(HttpHeaders headers, String title, Long krId, Long assigneeId, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M13 统计口径测试任务");
        if (krId != null) {
            body.put("krId", krId);
        }
        body.put("assigneeId", assigneeId);
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

    /** M05 标准模板（4 项权重 100%，与 V19 种子同构）。 */
    private Long createM05Template(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", "IT-M13-T-" + suffix);
        body.put("templateName", "M13 口径比对模板" + suffix);
        body.put("status", "enabled");
        body.put("items", List.of(
                templateItem("kr_achievement", "KR 达成率", "collectable", "kr_achievement", 40),
                templateItem("task_completion", "任务完成率", "collectable", "task_completion", 30),
                templateItem("time_compliance", "时限达标率", "collectable", "time_compliance", 10),
                templateItem("collab_attitude", "协作与态度", "subjective", null, 20)
        ));
        Long templateId = idOf(post(headers, "/api/perf/templates", body));
        createdTemplateIds.add(templateId);
        return templateId;
    }

    private Map<String, Object> templateItem(String code, String name, String category, String metricCode, int weight) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemCode", code);
        item.put("itemName", name);
        item.put("itemCategory", category);
        if (metricCode != null) {
            item.put("metricCode", metricCode);
        }
        item.put("weight", weight);
        return item;
    }

    private Long createM05Cycle(HttpHeaders headers, String suffix, Long templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleCode", "IT-M13-C-" + suffix);
        body.put("cycleName", "M13 口径比对周期" + suffix);
        body.put("cycleType", "quarter");
        body.put("startDate", RANGE_START);
        body.put("endDate", RANGE_END);
        body.put("templateId", templateId);
        Long cycleId = idOf(post(headers, "/api/perf/cycles", body));
        createdCycleIds.add(cycleId);
        return cycleId;
    }

    // ------------------------------------------------------------------
    // 指标字典请求体与口径断言辅助
    // ------------------------------------------------------------------

    private Map<String, Object> metricBody(String metricCode, String status, String category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricCode", metricCode);
        body.put("metricName", "测试指标 " + metricCode);
        body.put("metricCategory", category);
        body.put("metricDefinition", "用于集成测试的指标定义");
        body.put("formula", "测试公式：分子 / 分母 × 100");
        body.put("sourceModule", "M03");
        body.put("sourceDetail", "TaskPerfQueryService#topLevelTaskFacts");
        body.put("timeGrain", "统计区间 [startDate, endDate]，时区 +08:00");
        body.put("missingPolicy", "无数据按 0 计并标记 no_data");
        body.put("refreshFrequency", "realtime");
        body.put("cacheTtlSeconds", 0);
        body.put("ownerName", "system");
        body.put("status", status);
        return body;
    }

    /** 由字典详情还原一份完整的更新请求（只改 status），避免临时改状态时覆盖原口径文字。 */
    private Map<String, Object> bodyFromMetric(Map<String, Object> metric, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricCode", metric.get("metricCode"));
        body.put("metricName", metric.get("metricName"));
        body.put("metricCategory", metric.get("metricCategory"));
        body.put("metricDefinition", metric.get("metricDefinition"));
        body.put("formula", metric.get("formula"));
        body.put("sourceModule", metric.get("sourceModule"));
        body.put("sourceDetail", metric.get("sourceDetail"));
        body.put("timeGrain", metric.get("timeGrain"));
        body.put("missingPolicy", metric.get("missingPolicy"));
        body.put("refreshFrequency", metric.get("refreshFrequency"));
        body.put("cacheTtlSeconds", metric.get("cacheTtlSeconds"));
        body.put("ownerName", metric.get("ownerName"));
        body.put("remark", metric.get("remark"));
        body.put("status", status);
        return body;
    }

    private void assertSameDefinitionAsM05(List<Map<String, Object>> metrics, String code) {
        PerfMetric m05 = PerfMetricDictionary.find(code).orElseThrow();
        Map<String, Object> row = metricByCode(metrics, code);
        assertThat(String.valueOf(row.get("formula"))).as("M13 与 M05 公式必须逐字一致：" + code)
                .isEqualTo(m05.formula());
        assertThat(String.valueOf(row.get("timeGrain"))).as("M13 与 M05 取数时间范围必须逐字一致：" + code)
                .isEqualTo(m05.timeRange());
        assertThat(String.valueOf(row.get("missingPolicy"))).as("M13 与 M05 缺失值处理必须逐字一致：" + code)
                .isEqualTo(m05.missingPolicy());
    }

    private Map<String, Object> metricByCode(List<Map<String, Object>> metrics, String code) {
        return metrics.stream().filter(row -> code.equals(row.get("metricCode"))).findFirst()
                .orElseThrow(() -> new AssertionError("指标字典缺少：" + code));
    }

    private void assertMetricValue(Map<String, Object> metrics, String code, double expected) {
        Map<String, Object> metric = mapValue(metrics.get(code));
        assertThat(decimalValue(metric, "value")).as("指标 " + code + " 的本次统计值").isEqualTo(expected);
        assertThat(metric).containsEntry("missing", false);
        assertThat(String.valueOf(metric.get("formula"))).isNotBlank();
    }

    // ------------------------------------------------------------------
    // 用户造数与清理
    // ------------------------------------------------------------------

    private record TestEmployee(Long id, String username) {
    }

    /**
     * 建隔离测试员工（JDBC 直插 sys_user，密码哈希复用 admin，便于需要登录的用例）。
     *
     * @param deptId 所属部门
     * @param roleId 可选角色（null 表示不授角色；数据范围用例传 3=业务管理员 DEPT 范围）
     */
    private TestEmployee createEmployee(Long deptId, Long roleId, String suffix) {
        String username = "insight_it_" + suffix + "_" + SEQ.incrementAndGet();
        String passwordHash = jdbcTemplate.queryForObject("SELECT password FROM sys_user WHERE id = 1", String.class);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by) "
                            + "VALUES (?, ?, ?, ?, 1, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, "M13测试员工" + suffix);
            statement.setLong(4, deptId);
            return statement;
        }, keyHolder);
        Long userId = keyHolder.getKey().longValue();
        createdUserIds.add(userId);
        if (roleId != null) {
            jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        }
        return new TestEmployee(userId, username);
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // HTTP / JSON 辅助
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

    private long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private double decimalValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }
}
