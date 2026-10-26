package com.teamflow.insight.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.insight.mapper.InsightPlatformMapper;
import com.teamflow.insight.metric.InsightMetricRegistry;
import com.teamflow.task.service.TaskService;
import com.teamflow.team.service.TeamService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M13 团队效能视图服务（7.3）：承接量、响应时长、负载均衡。
 *
 * <p><b>数据来源</b>（不直连任何业务表）：</p>
 * <ul>
 *   <li>团队成员集合与承接量/在办数：M04 {@link TeamService#detail}（团队详情已含在册成员与负载，M04 内部经
 *       M03 {@code TaskPerfQueryService#memberTaskLoads} 取数）；</li>
 *   <li>响应时长：M03 {@link TaskService#list} 只读列表接口按成员分页读取已完成任务，计算
 *       「完成时间 − 创建时间」的小时均值（仅统计区间内完成的顶层任务）。</li>
 * </ul>
 *
 * <p><b>R3 数据范围</b>：团队成员还要与当前用户数据范围求交（范围外的成员不参与统计并给出 filteredCount 说明）。</p>
 *
 * <p><b>R5 性能</b>：承接量/在办为当前快照（一次批量端口调用）；响应时长按成员分页读取，单成员最多 5 页 × 100 条，
 * 超出时 sampled=true 并以样本均值近似（字典 missing_policy 已登记）。本期不启用缓存。</p>
 */
@Service
public class InsightTeamEfficiencyService extends InsightServiceSupport {

    /** 响应时长单成员采样上限：5 页 × 100 条。 */
    private static final int RESPONSE_PAGE_SIZE = 100;
    private static final int RESPONSE_MAX_PAGES = 5;
    private static final int DEFAULT_RANGE_DAYS = 30;

    private final InsightMetricService metricService;
    private final InsightPlatformMapper platformMapper;
    private final TeamService teamService;
    private final TaskService taskService;

    public InsightTeamEfficiencyService(
            InsightMetricService metricService,
            InsightPlatformMapper platformMapper,
            TeamService teamService,
            TaskService taskService
    ) {
        this.metricService = metricService;
        this.platformMapper = platformMapper;
        this.teamService = teamService;
        this.taskService = taskService;
    }

    /** 团队下拉选项（M04 既有接口的读模型代理，供看板选团队；不产生新数据）。 */
    public List<Map<String, Object>> teamOptions() {
        return teamService.options();
    }

    /**
     * 团队效能视图。
     *
     * @param teamId    团队 ID（必填）
     * @param startDate 响应时长统计开始日期；为空默认近 30 天
     * @param endDate   响应时长统计结束日期；为空默认今天
     */
    @DataScope
    public Map<String, Object> efficiency(Long teamId, String startDate, String endDate) {
        if (teamId == null) {
            throw new BusinessException("团队 ID 不能为空（7.3 团队效能视图需选定团队）");
        }
        LocalDate end = trimToNull(endDate) == null ? LocalDate.now() : parseDate(endDate, "统计结束日期");
        LocalDate start = trimToNull(startDate) == null ? end.minusDays(DEFAULT_RANGE_DAYS - 1L)
                : parseDate(startDate, "统计开始日期");
        requireValidRange(start, end);

        // R1 出数闸门
        Map<String, Map<String, Object>> metrics = metricService.loadOnlineMetrics(
                InsightMetricRegistry.TEAM_EFFICIENCY_METRICS);

        // 团队可见性由 M04 detail 内的 @DataScope 校验（团队不存在/无权访问会抛业务异常）
        Map<String, Object> team = teamService.detail(teamId);
        Object membersValue = team.get("members");
        List<Map<String, Object>> allMembers = membersValue instanceof List<?> list
                ? castRows(list) : List.of();

        // R3：成员维度再叠加当前用户数据范围（范围外成员不参与统计，只记录被过滤数量）
        DataScopeCriteria scope = DataScopeContext.current();
        Set<Long> visibleUserIds = new HashSet<>();
        for (Map<String, Object> user : platformMapper.selectVisibleUsers(scope)) {
            Long userId = numberAsLong(user.get("id"));
            if (userId != null) {
                visibleUserIds.add(userId);
            }
        }
        List<Map<String, Object>> visibleMembers = new ArrayList<>();
        for (Map<String, Object> member : allMembers) {
            Long userId = numberAsLong(member.get("userId"));
            if (userId != null && visibleUserIds.contains(userId)) {
                visibleMembers.add(member);
            }
        }
        int filteredMembers = allMembers.size() - visibleMembers.size();

        List<Map<String, Object>> memberRows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long taskTotal = 0;
        long taskInProgress = 0;
        long completedTaskCount = 0;
        BigDecimal responseMinutesSum = BigDecimal.ZERO;
        boolean anySampled = false;

        for (Map<String, Object> member : visibleMembers) {
            Long userId = numberAsLong(member.get("userId"));
            long memberTaskTotal = longOf(member.get("taskTotal"));
            long memberInProgress = longOf(member.get("taskInProgress"));
            ResponseStat response = responseStat(userId, start, end);
            if (response.sampled()) {
                anySampled = true;
            }
            BigDecimal responseHours = response.count() == 0 ? null
                    : response.minutesSum().divide(BigDecimal.valueOf(response.count() * 60L), 2, RoundingMode.HALF_UP);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("username", member.get("username"));
            row.put("nickname", member.get("nickname"));
            row.put("deptName", member.get("deptName"));
            row.put("teamRole", member.get("teamRole"));
            row.put("teamRoleLabel", member.get("teamRoleLabel"));
            row.put("taskTotal", memberTaskTotal);
            row.put("taskInProgress", memberInProgress);
            row.put("completedTaskCount", response.count());
            row.put("responseHours", responseHours);
            row.put("sampled", response.sampled());
            memberRows.add(row);

            taskTotal += memberTaskTotal;
            taskInProgress += memberInProgress;
            completedTaskCount += response.count();
            responseMinutesSum = responseMinutesSum.add(response.minutesSum());
        }

        memberRows.sort((left, right) -> {
            int compared = Long.compare(longOf(right.get("taskTotal")), longOf(left.get("taskTotal")));
            return compared != 0 ? compared : Long.compare(longOf(left.get("userId")), longOf(right.get("userId")));
        });
        if (filteredMembers > 0) {
            notes.add("按当前用户数据范围过滤掉 " + filteredMembers + " 名团队在册成员（R3 数据范围强制生效）");
        }
        if (anySampled) {
            notes.add("响应时长存在采样：单成员最多读取 " + (RESPONSE_PAGE_SIZE * RESPONSE_MAX_PAGES)
                    + " 条已完成任务，超出以样本均值近似（sampled=true）");
        }

        // 负载均衡（承接量分布，快照口径）
        Map<String, Object> loadBalance = loadBalance(memberRows);
        BigDecimal avgResponseHours = completedTaskCount == 0 ? null
                : responseMinutesSum.divide(BigDecimal.valueOf(completedTaskCount * 60L), 2, RoundingMode.HALF_UP);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("memberCount", memberRows.size());
        totals.put("taskTotal", taskTotal);
        totals.put("taskInProgress", taskInProgress);
        totals.put("completedTaskCount", completedTaskCount);
        totals.put("avgResponseHours", avgResponseHours);

        Map<String, Object> metricViews = new LinkedHashMap<>();
        metricViews.put(InsightMetricRegistry.TEAM_MEMBER_LOAD, metricPayload(
                metrics.get(InsightMetricRegistry.TEAM_MEMBER_LOAD), taskTotal, "个", memberRows.isEmpty(),
                "团队顶层任务承接量合计（含全部状态）；在办数 " + taskInProgress + " 个"));
        metricViews.put(InsightMetricRegistry.TEAM_RESPONSE_TIME, metricPayload(
                metrics.get(InsightMetricRegistry.TEAM_RESPONSE_TIME), avgResponseHours, "小时",
                completedTaskCount == 0,
                completedTaskCount == 0 ? "统计区间内无已完成任务：响应时长无数据（no_completed_task，不显示 0 小时）"
                        : "按 " + completedTaskCount + " 条已完成顶层任务计算完成时长均值"));
        metricViews.put(InsightMetricRegistry.TEAM_LOAD_BALANCE, metricPayload(
                metrics.get(InsightMetricRegistry.TEAM_LOAD_BALANCE),
                loadBalance.get("cv"), null, loadBalance.get("missing") == Boolean.TRUE,
                String.valueOf(loadBalance.get("note"))));

        Map<String, Object> teamView = new LinkedHashMap<>();
        teamView.put("id", numberAsLong(team.get("id")));
        teamView.put("teamName", team.get("teamName"));
        teamView.put("teamCode", team.get("teamCode"));
        teamView.put("deptName", team.get("deptName"));
        teamView.put("ownerName", team.get("ownerName"));
        teamView.put("status", team.get("status"));
        teamView.put("memberCount", allMembers.size());
        teamView.put("statMemberCount", memberRows.size());
        teamView.put("filteredMemberCount", filteredMembers);

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("timezone", "+08:00");
        period.put("asOf", LocalDate.now().toString());
        period.put("rangeDays", end.toEpochDay() - start.toEpochDay() + 1);
        period.put("rangeNote", "承接量/在办为当前快照，不受区间限制；区间仅约束响应时长（按完成日期）");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("team", teamView);
        result.put("period", period);
        result.put("totals", totals);
        result.put("metrics", metricViews);
        result.put("members", memberRows);
        result.put("loadBalance", loadBalance);
        result.put("notes", notes);
        result.put("readOnly", true);
        result.put("cache", "不缓存（realtime 实时聚合；字典 cache_ttl_seconds=0）");
        return result;
    }

    // ------------------------------------------------------------------
    // 响应时长（M03 只读列表接口，逐成员分页）
    // ------------------------------------------------------------------

    private ResponseStat responseStat(Long userId, LocalDate start, LocalDate end) {
        long counted = 0;
        BigDecimal minutesSum = BigDecimal.ZERO;
        boolean sampled = false;
        for (int page = 1; page <= RESPONSE_MAX_PAGES; page++) {
            PageResult<Map<String, Object>> pageResult = taskService.list(
                    new PageQuery(page, RESPONSE_PAGE_SIZE, null, null),
                    null, "done", userId, null, null);
            List<Map<String, Object>> rows = pageResult.rows();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> task : rows) {
                // 顶层任务才计入（与 M02/M05 口径一致：子任务不计入）
                if (task.get("parentTaskId") != null) {
                    continue;
                }
                LocalDate doneDate = toLocalDate(task.get("doneTime"));
                if (doneDate == null || doneDate.isBefore(start) || doneDate.isAfter(end)) {
                    continue;
                }
                LocalDateTime createTime = parseDateTime(task.get("createTime"));
                LocalDateTime doneTime = parseDateTime(task.get("doneTime"));
                if (createTime == null || doneTime == null || doneTime.isBefore(createTime)) {
                    continue;
                }
                minutesSum = minutesSum.add(BigDecimal.valueOf(Duration.between(createTime, doneTime).toMinutes()));
                counted++;
            }
            if (rows.size() < RESPONSE_PAGE_SIZE) {
                break;
            }
            if (page == RESPONSE_MAX_PAGES) {
                sampled = pageResult.total() > (long) RESPONSE_PAGE_SIZE * RESPONSE_MAX_PAGES;
            }
        }
        return new ResponseStat(counted, minutesSum, sampled);
    }

    // ------------------------------------------------------------------
    // 负载均衡
    // ------------------------------------------------------------------

    private Map<String, Object> loadBalance(List<Map<String, Object>> memberRows) {
        Map<String, Object> balance = new LinkedHashMap<>();
        int memberCount = memberRows.size();
        balance.put("memberCount", memberCount);
        if (memberCount < 2) {
            balance.putAll(zeroBalance("在册成员不足 2 人，无法评估负载均衡（insufficient_data）"));
            return balance;
        }
        BigDecimal sum = BigDecimal.ZERO;
        long max = Long.MIN_VALUE;
        long min = Long.MAX_VALUE;
        Long busiestUserId = null;
        String busiestName = null;
        for (Map<String, Object> row : memberRows) {
            long tasks = longOf(row.get("taskTotal"));
            sum = sum.add(BigDecimal.valueOf(tasks));
            if (tasks > max) {
                max = tasks;
                busiestUserId = numberAsLong(row.get("userId"));
                busiestName = String.valueOf(row.get("nickname"));
            }
            min = Math.min(min, tasks);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(memberCount), 2, RoundingMode.HALF_UP);
        balance.put("avgTasks", avg);
        balance.put("maxTasks", max);
        balance.put("minTasks", min);
        balance.put("range", max - min);
        balance.put("busiestUserId", busiestUserId);
        balance.put("busiestName", busiestName);
        if (avg.signum() == 0) {
            balance.putAll(zeroBalance("成员平均承接量为 0，无法评估负载均衡（insufficient_data）"));
            balance.put("avgTasks", avg);
            return balance;
        }
        BigDecimal variance = BigDecimal.ZERO;
        for (Map<String, Object> row : memberRows) {
            BigDecimal diff = BigDecimal.valueOf(longOf(row.get("taskTotal"))).subtract(avg);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(memberCount), 6, RoundingMode.HALF_UP);
        double stdDev = Math.sqrt(variance.doubleValue());
        BigDecimal cv = BigDecimal.valueOf(stdDev).divide(avg, 2, RoundingMode.HALF_UP);
        String level = cv.compareTo(new BigDecimal("0.30")) < 0 ? "balanced"
                : cv.compareTo(new BigDecimal("0.60")) <= 0 ? "moderate" : "unbalanced";
        balance.put("cv", cv);
        balance.put("level", level);
        balance.put("levelLabel", switch (level) {
            case "balanced" -> "均衡";
            case "moderate" -> "一般";
            default -> "失衡";
        });
        balance.put("missing", false);
        balance.put("note", "承接量分布：均值 " + avg.toPlainString() + "、极差 " + (max - min)
                + "、变异系数 " + cv.toPlainString() + "（" + balance.get("levelLabel") + "）");
        return balance;
    }

    private Map<String, Object> zeroBalance(String note) {
        Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("avgTasks", null);
        balance.put("maxTasks", null);
        balance.put("minTasks", null);
        balance.put("range", null);
        balance.put("cv", null);
        balance.put("level", null);
        balance.put("levelLabel", null);
        balance.put("busiestUserId", null);
        balance.put("busiestName", null);
        balance.put("missing", true);
        balance.put("note", note);
        return balance;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(List<?> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    /** 响应时长中间结果。 */
    private record ResponseStat(long count, BigDecimal minutesSum, boolean sampled) {
    }
}
