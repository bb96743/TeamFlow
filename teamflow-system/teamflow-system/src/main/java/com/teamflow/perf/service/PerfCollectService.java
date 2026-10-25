package com.teamflow.perf.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.okr.service.OkrKeyResultService;
import com.teamflow.perf.domain.dto.PerfCollectRequest;
import com.teamflow.perf.mapper.PerfPlatformMapper;
import com.teamflow.perf.metric.PerfMetric;
import com.teamflow.perf.metric.PerfMetricDictionary;
import com.teamflow.task.service.TaskPerfQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M05 归集引擎（本模块核心）：把 M02 的 KR 达成率与 M03 的任务事实按 D-06 口径归集为绩效分。
 *
 * <p><b>数据边界（L0 §4.3 / M05 RULE §四）</b>：本类**不直连 {@code okr_*} / {@code task_*} 表**，
 * 分别经 M02 的 {@link OkrKeyResultService#perfFacts} 与 M03 的 {@link TaskPerfQueryService#topLevelTaskFacts}
 * 取数（同模块内直接注入 Spring Bean，跨模块只走 Service）。</p>
 *
 * <p><b>口径（L0 §9.1 D-2026-10-25-06，不得自行改动）</b>：</p>
 * <ul>
 *   <li>D-06.1 逾期：KR 进度不因逾期扣减；逾期由「时限达标率」单独承接；</li>
 *   <li>D-06.2 返工：每次归集都按当前状态重算并**覆盖** system 行（取周期末快照，不取历史峰值）；</li>
 *   <li>D-06.3 校准：归集值禁止手工覆盖；下钻展示 M02 的人工校准量与原因；</li>
 *   <li>D-06.5 缺失：无数据默认取 0 并在预览提示；仅显式标记 not_applicable 的例外状态按剩余权重归一化。</li>
 * </ul>
 */
@Service
public class PerfCollectService extends PerfServiceSupport {

    private final PerfCycleService cycleService;
    private final PerfTemplateService templateService;
    private final PerfScoreService scoreService;
    private final PerfPlatformMapper platformMapper;
    private final OkrKeyResultService okrKeyResultService;
    private final TaskPerfQueryService taskPerfQueryService;

    public PerfCollectService(
            PerfCycleService cycleService,
            PerfTemplateService templateService,
            PerfScoreService scoreService,
            PerfPlatformMapper platformMapper,
            OkrKeyResultService okrKeyResultService,
            TaskPerfQueryService taskPerfQueryService
    ) {
        this.cycleService = cycleService;
        this.templateService = templateService;
        this.scoreService = scoreService;
        this.platformMapper = platformMapper;
        this.okrKeyResultService = okrKeyResultService;
        this.taskPerfQueryService = taskPerfQueryService;
    }

    /** 归集预览：只计算不落库（上线前核对口径用），必可下钻到 KR / 任务清单。 */
    public Map<String, Object> preview(PerfCollectRequest request) {
        return collect(request, false);
    }

    /** 归集重算：计算并覆盖写 system 行（R3 幂等；同周期重算两次结果一致）。 */
    @Transactional
    public Map<String, Object> recalculate(PerfCollectRequest request) {
        return collect(request, true);
    }

    /**
     * 供结果服务复用的单员工归集：返回该员工的指标项归集明细（含下钻来源与提示）。
     *
     * @param persist 是否把可归集值写入 {@code perf_score} 的 system 行（归档时必为 true，固化周期末快照）
     */
    public Map<String, Object> computeForEmployee(
            Map<String, Object> cycle, List<Map<String, Object>> items, Map<String, Object> employee, boolean persist) {
        Long cycleId = numberAsLong(cycle.get("id"));
        Long employeeId = numberAsLong(employee.get("id"));
        String employeeName = employeeName(employee);
        LocalDate start = parseCycleDate(cycle, "startDate");
        LocalDate end = parseCycleDate(cycle, "endDate");

        // ===== 跨模块取数（只走 Service）=====
        List<Map<String, Object>> krs = okrKeyResultService.perfFacts(employeeId, start, end);
        List<Map<String, Object>> tasks = taskPerfQueryService.topLevelTaskFacts(employeeId, start, end);

        Map<String, MetricValue> metrics = new LinkedHashMap<>();
        metrics.put(PerfMetricDictionary.KR_ACHIEVEMENT, krAchievement(krs));
        metrics.put(PerfMetricDictionary.TASK_COMPLETION, taskCompletion(tasks));
        metrics.put(PerfMetricDictionary.TIME_COMPLIANCE, timeCompliance(tasks));

        List<Map<String, Object>> itemRows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PerfCollectedValue> collectableValues = new ArrayList<>();
        BigDecimal scoredWeight = BigDecimal.ZERO;
        BigDecimal naWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;

        for (Map<String, Object> item : items) {
            Long itemId = numberAsLong(item.get("id"));
            String itemCode = String.valueOf(item.get("itemCode"));
            String itemName = String.valueOf(item.get("itemName"));
            String category = String.valueOf(item.get("itemCategory"));
            BigDecimal weight = zeroIfNull(numberAsBigDecimal(item.get("weight")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", itemId);
            row.put("itemCode", itemCode);
            row.put("itemName", itemName);
            row.put("itemCategory", category);
            row.put("weight", weight);

            if ("subjective".equals(category)) {
                row.put("metricCode", null);
                row.put("autoScore", null);
                row.put("score", null);
                row.put("notApplicable", false);
                row.put("countedInTotal", false);
                row.put("missing", false);
                row.put("drillDownType", null);
                row.put("sources", List.of());
                itemRows.add(row);
                continue;
            }

            PerfMetric metric = PerfMetricDictionary.find(String.valueOf(item.get("metricCode")))
                    .orElseThrow(() -> new BusinessException("可归集指标「" + item.get("metricCode")
                            + "」未在指标字典登记四要素（R2）"));
            MetricValue value = metrics.getOrDefault(metric.code(),
                    new MetricValue(metric.code(), BigDecimal.ZERO, true, List.of(), "未登记取数实现"));
            row.put("metricCode", metric.code());
            row.put("metricName", metric.name());
            row.put("drillDownType", metric.drillDownType());
            row.put("sources", value.sources());
            row.put("missing", value.missing());
            row.put("sourceNote", value.note());

            Map<String, Object> systemRow = scoreService.systemRow(cycleId, employeeId, itemId);
            boolean notApplicable = systemRow != null && intOf(systemRow.get("notApplicable")) == 1;
            row.put("notApplicable", notApplicable);
            row.put("naReason", systemRow == null ? null : systemRow.get("naReason"));

            BigDecimal autoScore = percent(value.value());
            row.put("autoScore", autoScore);
            row.put("score", autoScore);
            collectableValues.add(new PerfCollectedValue(itemId, itemCode, itemName, weight, autoScore,
                    value.missing()));
            if (notApplicable) {
                naWeight = naWeight.add(weight);
                row.put("countedInTotal", false);
                warnings.add("指标「" + itemName + "」已标记不适用"
                        + (row.get("naReason") == null ? "" : "（原因：" + row.get("naReason") + "）")
                        + "，按剩余权重归一化（D-06.5）");
            } else {
                scoredWeight = scoredWeight.add(weight);
                weightedSum = weightedSum.add(autoScore.multiply(weight));
                row.put("countedInTotal", true);
                if (value.missing()) {
                    warnings.add("指标「" + itemName + "」无业务数据且未标记不适用，已按 0 计（D-06.5）");
                }
            }
            if (value.note() != null) {
                warnings.add(value.note());
            }
            itemRows.add(row);

            // D-06.3：下钻展示校准量（人工校准发生在 M02 的 KR 进度上，M05 只读展示）
            if (PerfMetricDictionary.KR_ACHIEVEMENT.equals(metric.code())) {
                for (Map<String, Object> kr : krs) {
                    Object calibrationTo = kr.get("calibrationTo");
                    if (calibrationTo == null) {
                        continue;
                    }
                    warnings.add("KR「" + kr.get("krTitle") + "」存在人工校准："
                            + display(kr.get("calibrationFrom")) + "% → " + display(calibrationTo)
                            + "%（原因：" + display(kr.get("calibrationReason"))
                            + "，操作人：" + display(kr.get("calibrationOperator")) + "，时间："
                            + display(kr.get("calibrationTime")) + "）");
                }
            }
        }

        BigDecimal previewTotal = scoredWeight.signum() > 0 ? scale2(weightedSum.divide(scoredWeight, 4, RoundingMode.HALF_UP)) : null;
        if (persist) {
            scoreService.persistCollected(cycle, employeeId, collectableValues);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employeeId);
        result.put("employeeName", employeeName);
        result.put("employeeUsername", employee.get("username"));
        result.put("items", itemRows);
        result.put("previewTotal", previewTotal);
        result.put("scoredWeight", scoredWeight);
        result.put("naWeight", naWeight);
        result.put("krCount", krs.size());
        result.put("taskCount", tasks.size());
        result.put("warnings", warnings);
        return result;
    }

    // ------------------------------------------------------------------
    // 归集主流程
    // ------------------------------------------------------------------

    private Map<String, Object> collect(PerfCollectRequest request, boolean persist) {
        if (request == null || request.getCycleId() == null) {
            throw new BusinessException("绩效周期 ID 不能为空");
        }
        Map<String, Object> cycle = cycleService.requireCycle(request.getCycleId());
        cycleService.requireCollectable(cycle);
        Long templateId = numberAsLong(cycle.get("templateId"));
        if (templateId == null) {
            throw new BusinessException("绩效周期尚未绑定指标模板，无法归集");
        }
        List<Map<String, Object>> items = templateService.requireItems(templateId);
        List<Map<String, Object>> employees = resolveEmployees(request.getEmployeeIds());

        List<Map<String, Object>> employeeResults = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();
        for (Map<String, Object> employee : employees) {
            Map<String, Object> employeeResult = computeForEmployee(cycle, items, employee, persist);
            employeeResults.add(employeeResult);
            for (String warning : asStringList(employeeResult.get("warnings"))) {
                allWarnings.add(employeeResult.get("employeeName") + "：" + warning);
            }
        }
        if (persist) {
            cycleService.markCollectTime(numberAsLong(cycle.get("id")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", numberAsLong(cycle.get("id")));
        result.put("cycleCode", cycle.get("cycleCode"));
        result.put("cycleName", cycle.get("cycleName"));
        result.put("startDate", cycle.get("startDate"));
        result.put("endDate", cycle.get("endDate"));
        result.put("templateId", templateId);
        result.put("templateName", cycle.get("templateName"));
        result.put("mode", persist ? "recalculate" : "preview");
        result.put("employeeCount", employeeResults.size());
        result.put("employees", employeeResults);
        result.put("warnings", allWarnings);
        return result;
    }

    private List<Map<String, Object>> resolveEmployees(List<Long> employeeIds) {
        List<Map<String, Object>> employees = new ArrayList<>();
        if (employeeIds == null || employeeIds.isEmpty()) {
            for (Map<String, Object> user : platformMapper.selectActiveUsers()) {
                Map<String, Object> row = new LinkedHashMap<>(user);
                employees.add(row);
            }
        } else {
            for (Long employeeId : employeeIds) {
                Map<String, Object> user = platformMapper.selectUserById(employeeId);
                if (user == null) {
                    throw new BusinessException("被评人不存在或已删除：employeeId=" + employeeId);
                }
                employees.add(new LinkedHashMap<>(user));
            }
        }
        if (employees.isEmpty()) {
            throw new BusinessException("没有可归集的员工");
        }
        return employees;
    }

    // ------------------------------------------------------------------
    // 口径实现（D-06）
    // ------------------------------------------------------------------

    /** KR 达成率：该员工承接 KR 的进度算术平均；无 KR 时缺失（D-06.5 情形①，默认 0）。 */
    private MetricValue krAchievement(List<Map<String, Object>> krs) {
        if (krs.isEmpty()) {
            return new MetricValue(PerfMetricDictionary.KR_ACHIEVEMENT, BigDecimal.ZERO, true, List.of(),
                    "员工无 KR 数据：按 D-06.5 情形①/③ 默认取 0（如属新入职/长假/借调/试用期未定目标，请由 HR 标记不适用）");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> kr : krs) {
            sum = sum.add(zeroIfNull(numberAsBigDecimal(kr.get("progress"))));
        }
        return new MetricValue(PerfMetricDictionary.KR_ACHIEVEMENT, percent(
                sum.divide(BigDecimal.valueOf(krs.size()), 2, RoundingMode.HALF_UP)), false, krs, null);
    }

    /** 任务完成率：已完成顶层任务数 / 顶层任务总数；无顶层任务时缺失。 */
    private MetricValue taskCompletion(List<Map<String, Object>> tasks) {
        if (tasks.isEmpty()) {
            return new MetricValue(PerfMetricDictionary.TASK_COMPLETION, BigDecimal.ZERO, true, List.of(),
                    "员工无顶层任务数据：按 D-06.5 情形③ 默认取 0（如属例外状态，请由 HR 标记不适用）");
        }
        long done = tasks.stream().filter(task -> "done".equals(task.get("status"))).count();
        BigDecimal value = BigDecimal.valueOf(done)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(tasks.size()), 2, RoundingMode.HALF_UP);
        return new MetricValue(PerfMetricDictionary.TASK_COMPLETION, percent(value), false, taskSources(tasks), null);
    }

    /**
     * 时限达标率（D-06.1 逾期另立指标）：按时完成顶层任务数 / 已完成顶层任务数。
     * 按时 = done_time 的日期 ≤ due_date；due_date 为空视为按时。
     */
    private MetricValue timeCompliance(List<Map<String, Object>> tasks) {
        if (tasks.isEmpty()) {
            return new MetricValue(PerfMetricDictionary.TIME_COMPLIANCE, BigDecimal.ZERO, true, List.of(),
                    "员工无顶层任务数据：时限达标率按 0 计（D-06.5）");
        }
        List<Map<String, Object>> completed = new ArrayList<>();
        for (Map<String, Object> task : taskSources(tasks)) {
            if (Boolean.TRUE.equals(task.get("completed"))) {
                completed.add(task);
            }
        }
        if (completed.isEmpty()) {
            return new MetricValue(PerfMetricDictionary.TIME_COMPLIANCE, BigDecimal.ZERO, false, taskSources(tasks),
                    "员工有任务但无已完成顶层任务，时限达标率按 0 计（D-06.5 情形②）");
        }
        long onTime = completed.stream().filter(task -> Boolean.TRUE.equals(task.get("onTime"))).count();
        BigDecimal value = BigDecimal.valueOf(onTime)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(completed.size()), 2, RoundingMode.HALF_UP);
        return new MetricValue(PerfMetricDictionary.TIME_COMPLIANCE, percent(value), false, taskSources(tasks), null);
    }

    private List<Map<String, Object>> taskSources(List<Map<String, Object>> tasks) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            Map<String, Object> row = new LinkedHashMap<>(task);
            row.put("completed", "done".equals(task.get("status")));
            row.put("onTime", isOnTime(task));
            sources.add(row);
        }
        return sources;
    }

    private boolean isOnTime(Map<String, Object> task) {
        if (!"done".equals(task.get("status"))) {
            return false;
        }
        String dueDate = task.get("dueDate") == null ? null : String.valueOf(task.get("dueDate"));
        if (dueDate == null || dueDate.isBlank()) {
            return true;
        }
        String doneTime = task.get("doneTime") == null ? null : String.valueOf(task.get("doneTime"));
        if (doneTime == null || doneTime.length() < 10) {
            return false;
        }
        return LocalDate.parse(doneTime.substring(0, 10)).compareTo(LocalDate.parse(dueDate)) <= 0;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private LocalDate parseCycleDate(Map<String, Object> cycle, String key) {
        try {
            return LocalDate.parse(String.valueOf(cycle.get(key)));
        } catch (DateTimeParseException exception) {
            throw new BusinessException("绩效周期日期格式异常：" + cycle.get(key));
        }
    }

    private String employeeName(Map<String, Object> employee) {
        Object nickname = employee.get("nickname");
        if (nickname != null && !String.valueOf(nickname).isBlank()) {
            return String.valueOf(nickname);
        }
        return String.valueOf(employee.get("username"));
    }

    private String display(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** 单指标归集结果。 */
    private record MetricValue(
            String code,
            BigDecimal value,
            boolean missing,
            List<Map<String, Object>> sources,
            String note
    ) {
    }
}
