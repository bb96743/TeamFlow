package com.teamflow.insight.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.insight.mapper.InsightPlatformMapper;
import com.teamflow.insight.metric.InsightMetricRegistry;
import com.teamflow.okr.service.OkrKeyResultService;
import com.teamflow.task.service.TaskPerfQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 任务统计看板服务（7.2）：完成率、逾期率、周期趋势、成员分布。
 *
 * <p><b>口径一致性（最关键）</b>：本服务与 M05 归集**使用同一上游只读端口与同一公式**——</p>
 * <ul>
 *   <li>KR 达成率：M02 {@link OkrKeyResultService#perfFacts} → 该员工承接 KR 的进度算术平均；</li>
 *   <li>任务完成率：M03 {@link TaskPerfQueryService#topLevelTaskFacts} → 已完成顶层任务数 / 顶层任务总数；</li>
 *   <li>时限达标率：同一任务集合 → 按时完成顶层任务数 / 已完成顶层任务数（按时 = done_time 日期 ≤ due_date）。</li>
 * </ul>
 * <p>三个指标与 M05 指标字典**同码同文字口径**（V20 种子逐字一致），验收测试把 M13 看板值与 M05 归集预览
 * 逐项比对，差异必须为 0。</p>
 *
 * <p><b>数据边界</b>：不写库、不直连 {@code task_*}/{@code okr_*}/{@code perf_*}；成员展示信息读
 * {@code sys_user}/{@code sys_dept}（平台只读引用，L0 §4.3）。数据范围（R3）由本服务先解析可见成员集合，
 * 再逐成员调用上游端口，普通成员看不到范围外数据。</p>
 *
 * <p><b>性能与缓存（R5）</b>：本期实时聚合、不缓存（字典 {@code cache_ttl_seconds=0}），
 * 统计区间上限 366 天；成员维度**一次批量取数后按用户分组**（D-2026-10-27-14），不再逐人查库。</p>
 */
@Service
public class InsightTaskStatsService extends InsightServiceSupport {

    private final InsightMetricService metricService;
    private final InsightPlatformMapper platformMapper;
    private final OkrKeyResultService okrKeyResultService;
    private final TaskPerfQueryService taskPerfQueryService;

    public InsightTaskStatsService(
            InsightMetricService metricService,
            InsightPlatformMapper platformMapper,
            OkrKeyResultService okrKeyResultService,
            TaskPerfQueryService taskPerfQueryService
    ) {
        this.metricService = metricService;
        this.platformMapper = platformMapper;
        this.okrKeyResultService = okrKeyResultService;
        this.taskPerfQueryService = taskPerfQueryService;
    }

    /**
     * 任务统计总览：一次返回完成率/逾期率/时限达标率/KR 达成率 + 成员分布 + 周期趋势。
     *
     * @param startDate  统计开始日期（含）
     * @param endDate    统计结束日期（含）
     * @param assigneeId 可选：只看某位成员（用于逐个核对口径）；为空时按当前用户数据范围统计全部可见成员
     */
    @DataScope
    public Map<String, Object> overview(String startDate, String endDate, Long assigneeId) {
        LocalDate start = parseDate(startDate, "统计开始日期");
        LocalDate end = parseDate(endDate, "统计结束日期");
        requireValidRange(start, end);
        LocalDate asOf = LocalDate.now();

        // R1 出数闸门：所需指标必须已在指标字典登记且上线（未登记不得上线）
        Map<String, Map<String, Object>> metrics = metricService.loadOnlineMetrics(
                InsightMetricRegistry.TASK_BOARD_METRICS);

        DataScopeCriteria scope = DataScopeContext.current();
        List<Map<String, Object>> participants = resolveParticipants(scope, assigneeId);

        List<Map<String, Object>> memberRows = new ArrayList<>();
        List<Map<String, Object>> allTasks = new ArrayList<>();
        long taskTotal = 0;
        long taskDone = 0;
        long taskOnTime = 0;
        long taskOverdue = 0;
        long krCount = 0;
        BigDecimal krProgressSum = BigDecimal.ZERO;

        // D-2026-10-27-14：先按成员集合批量取全部事实（2 条 SQL），再按 userId 分组；
        // 分组后逐成员统计逻辑与单条版本完全一致，避免 23 成员 × 2 次的 N+1。
        List<Long> participantIds = participants.stream()
                .map(participant -> numberAsLong(participant.get("id")))
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, List<Map<String, Object>>> taskFactsByUser = groupRowsByUser(
                taskPerfQueryService.topLevelTaskFactsBatch(participantIds, start, end), "assigneeId");
        Map<Long, List<Map<String, Object>>> krFactsByUser = groupRowsByUser(
                okrKeyResultService.perfFactsBatch(participantIds, start, end), "ownerId");

        for (Map<String, Object> user : participants) {
            Long userId = numberAsLong(user.get("id"));
            List<Map<String, Object>> tasks = taskFactsByUser.getOrDefault(userId, List.of());
            allTasks.addAll(tasks);
            List<Map<String, Object>> krs = krFactsByUser.getOrDefault(userId, List.of());

            long memberDone = 0;
            long memberOnTime = 0;
            long memberOverdue = 0;
            for (Map<String, Object> task : tasks) {
                boolean done = "done".equals(task.get("status"));
                boolean onTime = isOnTime(task);
                boolean overdue = isOverdue(task, asOf);
                if (done) {
                    memberDone++;
                    if (onTime) {
                        memberOnTime++;
                    }
                }
                if (overdue) {
                    memberOverdue++;
                }
            }
            long memberTotal = tasks.size();
            BigDecimal memberKrSum = BigDecimal.ZERO;
            for (Map<String, Object> kr : krs) {
                memberKrSum = memberKrSum.add(zeroIfNull(numberAsBigDecimal(kr.get("progress"))));
            }
            BigDecimal memberKrAchievement = average(memberKrSum, krs.size());

            boolean hasData = memberTotal > 0 || !krs.isEmpty();
            if (assigneeId == null && !hasData) {
                // 成员分布口径：无任务且无 KR 的成员不出现（不补 0），见 V20 字典 missing_policy
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("username", user.get("username"));
            row.put("nickname", user.get("nickname"));
            row.put("deptId", user.get("deptId"));
            row.put("deptName", user.get("deptName"));
            row.put("taskTotal", memberTotal);
            row.put("taskDone", memberDone);
            row.put("taskOnTime", memberOnTime);
            row.put("taskOverdue", memberOverdue);
            row.put("taskCompletionRate", percent(BigDecimal.valueOf(memberDone), BigDecimal.valueOf(memberTotal)));
            row.put("timeComplianceRate", percent(BigDecimal.valueOf(memberOnTime), BigDecimal.valueOf(memberDone)));
            row.put("overdueRate", percent(BigDecimal.valueOf(memberOverdue), BigDecimal.valueOf(memberTotal)));
            row.put("krCount", krs.size());
            row.put("krAchievement", memberKrAchievement);
            row.put("missing", !hasData);
            row.put("noDataNote", hasData ? null : "该成员在统计区间内无顶层任务与 KR 数据：按 0 计");
            memberRows.add(row);

            taskTotal += memberTotal;
            taskDone += memberDone;
            taskOnTime += memberOnTime;
            taskOverdue += memberOverdue;
            krCount += krs.size();
            krProgressSum = krProgressSum.add(memberKrSum);
        }

        memberRows.sort((left, right) -> {
            int compared = Long.compare(longOf(right.get("taskTotal")), longOf(left.get("taskTotal")));
            return compared != 0 ? compared : Long.compare(longOf(left.get("userId")), longOf(right.get("userId")));
        });

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("memberCount", memberRows.size());
        totals.put("visibleUserCount", participants.size());
        totals.put("taskTotal", taskTotal);
        totals.put("taskDone", taskDone);
        totals.put("taskOnTime", taskOnTime);
        totals.put("taskOverdue", taskOverdue);
        totals.put("krCount", krCount);

        BigDecimal completion = percent(BigDecimal.valueOf(taskDone), BigDecimal.valueOf(taskTotal));
        BigDecimal timeCompliance = percent(BigDecimal.valueOf(taskOnTime), BigDecimal.valueOf(taskDone));
        BigDecimal overdueRate = percent(BigDecimal.valueOf(taskOverdue), BigDecimal.valueOf(taskTotal));
        BigDecimal krAchievement = average(krProgressSum, krCount);

        Map<String, Object> metricViews = new LinkedHashMap<>();
        metricViews.put(InsightMetricRegistry.TASK_COMPLETION, metricPayload(
                metrics.get(InsightMetricRegistry.TASK_COMPLETION),
                completion == null ? BigDecimal.ZERO : completion, "%", completion == null,
                completion == null ? "统计区间内无顶层任务：按 0 计（R7；D-06.5 情形①/③）" : null));
        metricViews.put(InsightMetricRegistry.TIME_COMPLIANCE, metricPayload(
                metrics.get(InsightMetricRegistry.TIME_COMPLIANCE),
                timeCompliance == null ? BigDecimal.ZERO : timeCompliance, "%", timeCompliance == null,
                timeCompliance == null ? "统计区间内无已完成顶层任务：按 0 计（R7；D-06.5 情形②）" : null));
        metricViews.put(InsightMetricRegistry.KR_ACHIEVEMENT, metricPayload(
                metrics.get(InsightMetricRegistry.KR_ACHIEVEMENT),
                krAchievement == null ? BigDecimal.ZERO : krAchievement, "%", krAchievement == null,
                krAchievement == null ? "统计区间内无 KR 数据：按 0 计（R7；D-06.5 情形①/③）" : null));
        metricViews.put(InsightMetricRegistry.TASK_OVERDUE_RATE, metricPayload(
                metrics.get(InsightMetricRegistry.TASK_OVERDUE_RATE),
                overdueRate == null ? BigDecimal.ZERO : overdueRate, "%", overdueRate == null,
                overdueRate == null ? "统计区间内无顶层任务：逾 期率无数据（no_data，不显示 0%）" : null));
        metricViews.put(InsightMetricRegistry.TASK_PERIOD_TREND, metricPayload(
                metrics.get(InsightMetricRegistry.TASK_PERIOD_TREND), null, null, taskTotal == 0,
                "明细型指标：数据见 trend 数组（按自然日展开，时区 +08:00）"));
        metricViews.put(InsightMetricRegistry.TASK_MEMBER_DISTRIBUTION, metricPayload(
                metrics.get(InsightMetricRegistry.TASK_MEMBER_DISTRIBUTION), null, null, memberRows.isEmpty(),
                "明细型指标：数据见 members 数组（无任务且无 KR 的成员不出现）"));

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("timezone", "+08:00");
        period.put("asOf", asOf.toString());
        period.put("rangeDays", end.toEpochDay() - start.toEpochDay() + 1);

        Map<String, Object> scopeView = new LinkedHashMap<>();
        scopeView.put("allScope", scope.isAllScope());
        scopeView.put("assigneeId", assigneeId);
        scopeView.put("visibleUserCount", participants.size());
        scopeView.put("dataScopeRule", "当前登录用户的数据范围（ALL=全量；否则本人 + 本部门）；统计不接受范围外成员");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("scope", scopeView);
        result.put("totals", totals);
        result.put("metrics", metricViews);
        result.put("members", memberRows);
        result.put("trend", buildTrend(allTasks, start, end, asOf));
        result.put("readOnly", true);
        result.put("cache", "不缓存（realtime 实时聚合；字典 cache_ttl_seconds=0）");
        return result;
    }

    /** 批量结果按用户分组成「userId → 该用户的行」，行内保持批量 SQL 的原始顺序。 */
    private Map<Long, List<Map<String, Object>>> groupRowsByUser(
            List<Map<String, Object>> rows, String userIdField) {
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return grouped;
        }
        for (Map<String, Object> row : rows) {
            Long userId = numberAsLong(row.get(userIdField));
            if (userId != null) {
                grouped.computeIfAbsent(userId, key -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }
    // ------------------------------------------------------------------
    // 成员解析（R3）
    // ------------------------------------------------------------------

    private List<Map<String, Object>> resolveParticipants(DataScopeCriteria scope, Long assigneeId) {
        if (assigneeId != null) {
            Map<String, Object> user = platformMapper.selectUserBrief(assigneeId);
            if (user == null) {
                throw new BusinessException("被统计成员不存在或已删除：" + assigneeId);
            }
            if (!inScope(scope, user)) {
                throw new BusinessException("无权查看该成员的统计：超出当前用户数据范围（M13 R3 数据范围强制生效）");
            }
            return List.of(user);
        }
        return platformMapper.selectVisibleUsers(scope);
    }

    private boolean inScope(DataScopeCriteria scope, Map<String, Object> user) {
        if (scope.isAllScope()) {
            return true;
        }
        Long userId = numberAsLong(user.get("id"));
        if (userId != null && userId.equals(scope.getUserId())) {
            return true;
        }
        Long deptId = numberAsLong(user.get("deptId"));
        return deptId != null && scope.getDeptIds().contains(deptId);
    }

    // ------------------------------------------------------------------
    // 趋势
    // ------------------------------------------------------------------

    private List<Map<String, Object>> buildTrend(
            List<Map<String, Object>> tasks, LocalDate start, LocalDate end, LocalDate asOf) {
        // 先把区间内任务按 dueDate / doneDate 归桶，避免逐日重复遍历任务
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            buckets.put(day, new long[3]); // 0=dueCount 1=doneCount 2=lateDueCount
        }
        for (Map<String, Object> task : tasks) {
            LocalDate dueDate = toLocalDate(task.get("dueDate"));
            LocalDate doneDate = toLocalDate(task.get("doneTime"));
            if (dueDate != null && buckets.containsKey(dueDate)) {
                buckets.get(dueDate)[0]++;
                boolean late = (doneDate != null && doneDate.isAfter(dueDate))
                        || (doneDate == null && !dueDate.isAfter(asOf));
                if (late) {
                    buckets.get(dueDate)[2]++;
                }
            }
            if (doneDate != null && buckets.containsKey(doneDate)) {
                buckets.get(doneDate)[1]++;
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        buckets.forEach((day, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("dueCount", counts[0]);
            row.put("doneCount", counts[1]);
            row.put("lateDueCount", counts[2]);
            trend.add(row);
        });
        return trend;
    }

    // ------------------------------------------------------------------
    // 逾期 / 按时判定（与 V20 指标字典公式逐字对应）
    // ------------------------------------------------------------------

    /** 按时 = 已完成且（due_date 为空 或 done_time 的日期 ≤ due_date）。与 M05 的 isOnTime 完全一致。 */
    private boolean isOnTime(Map<String, Object> task) {
        if (!"done".equals(task.get("status"))) {
            return false;
        }
        LocalDate dueDate = toLocalDate(task.get("dueDate"));
        if (dueDate == null) {
            return true;
        }
        LocalDate doneDate = toLocalDate(task.get("doneTime"));
        return doneDate != null && !doneDate.isAfter(dueDate);
    }

    /**
     * 逾期 = 未完成且 due_date < 统计截止日，或已完成且 DATE(done_time) > due_date；
     * due_date 为空不判逾期（与指标字典 task_overdue_rate 公式一致）。
     */
    private boolean isOverdue(Map<String, Object> task, LocalDate asOf) {
        LocalDate dueDate = toLocalDate(task.get("dueDate"));
        if (dueDate == null) {
            return false;
        }
        LocalDate doneDate = toLocalDate(task.get("doneTime"));
        if ("done".equals(task.get("status"))) {
            return doneDate != null && doneDate.isAfter(dueDate);
        }
        return dueDate.isBefore(asOf);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
