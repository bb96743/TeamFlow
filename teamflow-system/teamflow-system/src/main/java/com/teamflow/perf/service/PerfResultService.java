package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.perf.domain.dto.PerfCycleIdRequest;
import com.teamflow.perf.mapper.PerfPlatformMapper;
import com.teamflow.perf.mapper.PerfResultMapper;
import com.teamflow.perf.mapper.PerfScoreMapper;
import com.teamflow.perf.status.PerfCycleStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M05 绩效结果服务：总分加权计算、结果归档、锁定与下钻追溯。
 *
 * <p><b>R3 幂等</b>：归档时按 (cycle_id, employee_id) 唯一键覆盖写 {@code perf_result}；
 * 归档前的归集重算同样只覆盖 system 行，重复执行结果一致。</p>
 * <p><b>R5 锁定不可逆</b>：归档（archived）→ 锁定（locked，终态）；锁定后任何编辑由
 * {@link PerfCycleService#requireEditable} 拒绝，调整只能走 {@code perf_appeal}。</p>
 * <p><b>D-06.2</b>：归档时对每个员工**重新归集一次**（周期末快照），不使用历史峰值。</p>
 */
@Service
public class PerfResultService extends PerfServiceSupport {

    private final PerfResultMapper resultMapper;
    private final PerfScoreMapper scoreMapper;
    private final PerfCycleService cycleService;
    private final PerfTemplateService templateService;
    private final PerfCollectService collectService;
    private final PerfPlatformMapper platformMapper;

    public PerfResultService(
            PerfResultMapper resultMapper,
            PerfScoreMapper scoreMapper,
            PerfCycleService cycleService,
            PerfTemplateService templateService,
            PerfCollectService collectService,
            PerfPlatformMapper platformMapper
    ) {
        this.resultMapper = resultMapper;
        this.scoreMapper = scoreMapper;
        this.cycleService = cycleService;
        this.templateService = templateService;
        this.collectService = collectService;
        this.platformMapper = platformMapper;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(Long cycleId, PageQuery query) {
        Page page = pageOf(query);
        long total = resultMapper.countResults(cycleId, DataScopeContext.current());
        List<Map<String, Object>> rows = resultMapper.selectResults(
                cycleId, DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        return decorate(requireResult(id));
    }

    /**
     * 可归集指标下钻：返回结果 + 该员工当前归集明细（含具体 KR / 任务清单）与人工校准提示。
     * 用于 DoD「可归集指标的分值能追溯到来源」。
     */
    public Map<String, Object> trace(Long id) {
        Map<String, Object> result = decorate(requireResult(id));
        Map<String, Object> cycle = cycleService.requireCycle(numberAsLong(result.get("cycleId")));
        List<Map<String, Object>> items = templateService.requireItems(numberAsLong(result.get("templateId")));
        Long employeeId = numberAsLong(result.get("employeeId"));
        Map<String, Object> employee = platformMapper.selectUserById(employeeId);
        if (employee == null) {
            employee = new LinkedHashMap<>();
            employee.put("id", employeeId);
            employee.put("username", "unknown");
        }
        Map<String, Object> trace = collectService.computeForEmployee(cycle, items, employee, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("cycle", cycle);
        response.put("detail", trace);
        return response;
    }

    /**
     * 结果归档：要求周期处于「评分中」；对每个已评分员工重算归集（周期末快照）→ 校验主观项上级评分齐全
     * → 按权重加权计算总分 → 写 perf_result（archived）→ 周期置 archived。
     */
    @Transactional
    public Map<String, Object> archive(PerfCycleIdRequest request) {
        Map<String, Object> cycle = cycleService.requireCycle(request == null ? null : request.getCycleId());
        Long cycleId = numberAsLong(cycle.get("id"));
        String status = String.valueOf(cycle.get("status"));
        if (!PerfCycleStatusMachine.SCORING.equals(status)) {
            throw new BusinessException("只有「评分中」的绩效周期可以归档，当前状态为「"
                    + PerfCycleStatusMachine.label(status) + "」；请先流转到评分中");
        }
        Long templateId = numberAsLong(cycle.get("templateId"));
        if (templateId == null) {
            throw new BusinessException("绩效周期尚未绑定指标模板，不能归档");
        }
        templateService.requireLaunchReady(templateId);
        List<Map<String, Object>> items = templateService.requireItems(templateId);
        List<Long> employees = scoreMapper.selectEmployeesWithManualScores(cycleId);
        if (employees.isEmpty()) {
            throw new BusinessException("本周期尚无任何自评/上级评记录，不能归档");
        }

        List<Map<String, Object>> briefs = new ArrayList<>();
        for (Long employeeId : employees) {
            Map<String, Object> employee = platformMapper.selectUserById(employeeId);
            if (employee == null) {
                throw new BusinessException("被评人不存在或已删除：employeeId=" + employeeId);
            }
            // D-06.2：归档即"周期末快照"，重新归集并覆盖 system 行
            collectService.computeForEmployee(cycle, items, employee, true);
            List<Map<String, Object>> latestRows = scoreMapper.selectLatestRows(cycleId, employeeId);
            Totals totals = computeTotals(items, latestRows);
            if (!totals.missingManagerItems().isEmpty()) {
                throw new BusinessException("员工「" + employeeName(employee) + "」的以下主观指标尚未完成上级评分，不能归档："
                        + String.join("、", totals.missingManagerItems()));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cycleId", cycleId);
            row.put("employeeId", employeeId);
            row.put("templateId", templateId);
            row.put("totalScore", totals.totalScore());
            row.put("selfScore", totals.selfScore());
            row.put("collectScore", totals.collectScore());
            row.put("subjectiveScore", totals.subjectiveScore());
            row.put("effectiveWeight", totals.effectiveWeight());
            row.put("naWeight", totals.naWeight());
            row.put("status", "archived");
            row.put("archiveTime", LocalDateTime.now());
            row.put("remark", "周期末快照归集 + 权重加权（D-06.2/D-06.5）");
            resultMapper.upsertResult(row, currentUsername());

            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("employeeId", employeeId);
            brief.put("employeeName", employeeName(employee));
            brief.put("totalScore", totals.totalScore());
            brief.put("effectiveWeight", totals.effectiveWeight());
            brief.put("naWeight", totals.naWeight());
            briefs.add(brief);
        }
        cycleService.markArchived(cycleId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", cycleId);
        result.put("status", PerfCycleStatusMachine.ARCHIVED);
        result.put("statusLabel", PerfCycleStatusMachine.label(PerfCycleStatusMachine.ARCHIVED));
        result.put("archivedCount", briefs.size());
        result.put("results", briefs);
        return result;
    }

    /** 结果锁定：仅归档后的周期可锁定；置结果与周期为 locked（终态，锁定不可逆）。 */
    @Transactional
    public Map<String, Object> lock(PerfCycleIdRequest request) {
        Map<String, Object> cycle = cycleService.requireCycle(request == null ? null : request.getCycleId());
        Long cycleId = numberAsLong(cycle.get("id"));
        String status = String.valueOf(cycle.get("status"));
        if (!PerfCycleStatusMachine.ARCHIVED.equals(status)) {
            throw new BusinessException("只有「已归档」的绩效周期可以锁定，当前状态为「"
                    + PerfCycleStatusMachine.label(status) + "」；锁定前请先归档结果");
        }
        List<Map<String, Object>> briefs = resultMapper.selectResultBriefs(cycleId);
        if (briefs.isEmpty()) {
            throw new BusinessException("本周期尚无归档结果，不能锁定");
        }
        int locked = resultMapper.lockResults(cycleId, LocalDateTime.now(), currentUsername());
        cycleService.markLocked(cycleId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", cycleId);
        result.put("status", PerfCycleStatusMachine.LOCKED);
        result.put("statusLabel", PerfCycleStatusMachine.label(PerfCycleStatusMachine.LOCKED));
        result.put("lockedCount", locked);
        result.put("locked", true);
        return result;
    }

    public Map<String, Object> requireResult(Long id) {
        if (id == null) {
            throw new BusinessException("绩效结果 ID 不能为空");
        }
        Map<String, Object> row = resultMapper.selectResultById(id);
        if (row == null) {
            throw new BusinessException("绩效结果不存在或已删除");
        }
        return row;
    }

    public Map<String, Object> findByEmployee(Long cycleId, Long employeeId) {
        return resultMapper.selectResultByEmployee(cycleId, employeeId);
    }

    // ------------------------------------------------------------------
    // 总分加权（R 总分计算 + D-06.5 剩余权重归一化）
    // ------------------------------------------------------------------

    private Totals computeTotals(List<Map<String, Object>> items, List<Map<String, Object>> latestRows) {
        Map<Long, Map<String, Map<String, Object>>> byItem = new LinkedHashMap<>();
        for (Map<String, Object> row : latestRows) {
            Long itemId = numberAsLong(row.get("templateItemId"));
            String raterType = String.valueOf(row.get("raterType"));
            byItem.computeIfAbsent(itemId, key -> new LinkedHashMap<>()).put(raterType, row);
        }
        BigDecimal totalSum = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal collectSum = BigDecimal.ZERO;
        BigDecimal collectWeight = BigDecimal.ZERO;
        BigDecimal subjectiveSum = BigDecimal.ZERO;
        BigDecimal subjectiveWeight = BigDecimal.ZERO;
        BigDecimal selfSum = BigDecimal.ZERO;
        BigDecimal naWeight = BigDecimal.ZERO;
        List<String> missingManagerItems = new ArrayList<>();

        for (Map<String, Object> item : items) {
            Long itemId = numberAsLong(item.get("id"));
            BigDecimal weight = zeroIfNull(numberAsBigDecimal(item.get("weight")));
            Map<String, Map<String, Object>> raters = byItem.getOrDefault(itemId, Map.of());
            boolean collectable = "collectable".equals(String.valueOf(item.get("itemCategory")));
            if (collectable) {
                Map<String, Object> system = raters.get("system");
                boolean notApplicable = system != null && intOf(system.get("notApplicable")) == 1;
                if (notApplicable) {
                    naWeight = naWeight.add(weight);
                    continue;
                }
                BigDecimal score = system == null ? BigDecimal.ZERO
                        : zeroIfNull(numberAsBigDecimal(system.get("autoScore")));
                totalSum = totalSum.add(score.multiply(weight));
                totalWeight = totalWeight.add(weight);
                collectSum = collectSum.add(score.multiply(weight));
                collectWeight = collectWeight.add(weight);
                selfSum = selfSum.add(score.multiply(weight));
            } else {
                Map<String, Object> manager = raters.get("manager");
                BigDecimal score;
                if (manager == null) {
                    missingManagerItems.add(String.valueOf(item.get("itemName")));
                    score = BigDecimal.ZERO;
                } else {
                    score = zeroIfNull(numberAsBigDecimal(manager.get("score")));
                }
                totalSum = totalSum.add(score.multiply(weight));
                totalWeight = totalWeight.add(weight);
                subjectiveSum = subjectiveSum.add(score.multiply(weight));
                subjectiveWeight = subjectiveWeight.add(weight);
                Map<String, Object> self = raters.get("self");
                BigDecimal selfScore = self == null ? BigDecimal.ZERO
                        : zeroIfNull(numberAsBigDecimal(self.get("score")));
                selfSum = selfSum.add(selfScore.multiply(weight));
            }
        }

        BigDecimal totalScore = totalWeight.signum() > 0
                ? totalSum.divide(totalWeight, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal selfScore = totalWeight.signum() > 0
                ? selfSum.divide(totalWeight, 2, RoundingMode.HALF_UP) : null;
        BigDecimal collectScore = collectWeight.signum() > 0
                ? collectSum.divide(collectWeight, 2, RoundingMode.HALF_UP) : null;
        BigDecimal subjectiveScore = subjectiveWeight.signum() > 0
                ? subjectiveSum.divide(subjectiveWeight, 2, RoundingMode.HALF_UP) : null;
        return new Totals(scale2(totalScore), scale2(selfScore), scale2(collectScore), scale2(subjectiveScore),
                scale2(totalWeight), scale2(naWeight), missingManagerItems);
    }

    private String employeeName(Map<String, Object> employee) {
        Object nickname = employee.get("nickname");
        if (nickname != null && !String.valueOf(nickname).isBlank()) {
            return String.valueOf(nickname);
        }
        return String.valueOf(employee.get("username"));
    }

    private Map<String, Object> decorate(Map<String, Object> row) {
        row.put("statusLabel", switch (String.valueOf(row.get("status"))) {
            case "archived" -> "已归档";
            case "locked" -> "已锁定";
            case "draft" -> "草稿";
            default -> String.valueOf(row.get("status"));
        });
        return row;
    }

    /** 加权计算中间结果。 */
    private record Totals(
            BigDecimal totalScore,
            BigDecimal selfScore,
            BigDecimal collectScore,
            BigDecimal subjectiveScore,
            BigDecimal effectiveWeight,
            BigDecimal naWeight,
            List<String> missingManagerItems
    ) {
    }
}
