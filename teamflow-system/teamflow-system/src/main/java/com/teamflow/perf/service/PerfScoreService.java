package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.perf.domain.dto.PerfItemScoreRequest;
import com.teamflow.perf.domain.dto.PerfManagerScoreRequest;
import com.teamflow.perf.domain.dto.PerfNotApplicableRequest;
import com.teamflow.perf.domain.dto.PerfSelfScoreRequest;
import com.teamflow.perf.mapper.PerfPlatformMapper;
import com.teamflow.perf.mapper.PerfScoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M05 评分服务：归集值落库（system 行）、自评/上级评（只追加留痕）、例外状态标记。
 *
 * <p><b>R1</b>：可归集类只读，自评/上级评提交可归集指标项会被直接拒绝；主观类必须填评语。</p>
 * <p><b>R3</b>：system 行按 (周期, 员工, 指标项) 覆盖更新，重算幂等、不累加。</p>
 * <p><b>R4</b>：self/manager 行每次保存 version+1 并记录 previous_score（评分人/时间/前后值全留痕）。</p>
 * <p><b>D-06.5</b>：not_applicable 标记只落在 system 行，标记者与原因一并留痕。</p>
 */
@Service
public class PerfScoreService extends PerfServiceSupport {

    private static final String SYSTEM = "system";
    private static final String SELF = "self";
    private static final String MANAGER = "manager";

    private final PerfScoreMapper scoreMapper;
    private final PerfTemplateService templateService;
    private final PerfCycleService cycleService;
    private final PerfPlatformMapper platformMapper;

    public PerfScoreService(
            PerfScoreMapper scoreMapper,
            PerfTemplateService templateService,
            PerfCycleService cycleService,
            PerfPlatformMapper platformMapper
    ) {
        this.scoreMapper = scoreMapper;
        this.templateService = templateService;
        this.cycleService = cycleService;
        this.platformMapper = platformMapper;
    }

    /** 评分明细（当前版本行）分页；数据范围：本人或本人部门下的员工。 */
    @DataScope
    public PageResult<Map<String, Object>> list(Long cycleId, Long employeeId, PageQuery query) {
        Page page = pageOf(query);
        long total = scoreMapper.countScores(cycleId, employeeId, DataScopeContext.current());
        List<Map<String, Object>> rows = scoreMapper.selectScores(
                cycleId, employeeId, DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorateRow);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 自评：被评人恒为当前登录用户。 */
    @Transactional
    public Map<String, Object> saveSelf(PerfSelfScoreRequest request) {
        Map<String, Object> cycle = requireScoringCycle(request.getCycleId());
        Long employeeId = platformMapper.selectUserIdByUsername(currentUsername());
        if (employeeId == null) {
            throw new BusinessException("当前登录用户不存在，无法自评");
        }
        int saved = appendScores(cycle, employeeId, request.getItems(), SELF);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", cycle.get("id"));
        result.put("employeeId", employeeId);
        result.put("raterType", SELF);
        result.put("savedCount", saved);
        return result;
    }

    /** 上级评分：被评人由请求指定；提交前要求本人已完成全部主观项自评。 */
    @Transactional
    public Map<String, Object> saveManager(PerfManagerScoreRequest request) {
        Map<String, Object> cycle = requireScoringCycle(request.getCycleId());
        Long employeeId = request.getEmployeeId();
        if (employeeId == null) {
            throw new BusinessException("上级评分必须指定被评人 employeeId");
        }
        if (platformMapper.countActiveUser(employeeId) == 0) {
            throw new BusinessException("被评人不存在或已停用");
        }
        List<Map<String, Object>> items = templateService.requireItems(numberAsLong(cycle.get("templateId")));
        List<String> missingSelf = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!"subjective".equals(String.valueOf(item.get("itemCategory")))) {
                continue;
            }
            Long itemId = numberAsLong(item.get("id"));
            if (scoreMapper.selectLatestRow(numberAsLong(cycle.get("id")), employeeId, itemId, SELF) == null) {
                missingSelf.add(String.valueOf(item.get("itemName")));
            }
        }
        if (!missingSelf.isEmpty()) {
            throw new BusinessException("本人尚未完成自评，不能提交上级评分；缺少自评的主观指标："
                    + String.join("、", missingSelf));
        }
        int saved = appendScores(cycle, employeeId, request.getItems(), MANAGER);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", cycle.get("id"));
        result.put("employeeId", employeeId);
        result.put("raterType", MANAGER);
        result.put("savedCount", saved);
        return result;
    }

    /**
     * D-06.5 例外状态标记：仅可归集指标项可标 {@code not_applicable}，且必须填写原因。
     * 标记保留在 system 行上，后续重算不会清除。
     */
    @Transactional
    public Map<String, Object> markNotApplicable(PerfNotApplicableRequest request) {
        Map<String, Object> cycle = cycleService.requireCycle(request.getCycleId());
        cycleService.requireEditable(cycle);
        Long cycleId = numberAsLong(cycle.get("id"));
        Long employeeId = request.getEmployeeId();
        if (employeeId == null || platformMapper.countActiveUser(employeeId) == 0) {
            throw new BusinessException("被评人不存在或已停用");
        }
        Long itemId = request.getItemId();
        if (itemId == null) {
            throw new BusinessException("指标项 ID 不能为空");
        }
        Map<String, Object> item = templateService.requireItems(numberAsLong(cycle.get("templateId")))
                .stream().filter(row -> itemId.equals(numberAsLong(row.get("id")))).findFirst()
                .orElseThrow(() -> new BusinessException("指标项不存在或不属于本周期模板"));
        if (!"collectable".equals(String.valueOf(item.get("itemCategory")))) {
            throw new BusinessException("只有可归集指标支持标记「不适用」（主观指标由评分人直接评分）");
        }
        boolean notApplicable = Boolean.TRUE.equals(request.getNotApplicable());
        String reason = trimToNull(request.getReason());
        if (notApplicable && reason == null) {
            throw new BusinessException("标记不适用必须填写原因（D-06.5：仅经 HR/管理者显式标记的例外状态）");
        }
        Map<String, Object> existing = scoreMapper.selectSystemRow(cycleId, employeeId, itemId);
        String operator = currentUsername();
        if (existing == null) {
            Map<String, Object> row = systemRow(cycle, item, employeeId, BigDecimal.ZERO);
            row.put("notApplicable", notApplicable);
            row.put("naReason", notApplicable ? reason : null);
            row.put("naMarkedBy", notApplicable ? operator : null);
            row.put("naMarkedTime", notApplicable ? LocalDateTime.now() : null);
            scoreMapper.insertScore(row, operator);
        } else {
            scoreMapper.updateSystemNotApplicable(numberAsLong(existing.get("id")), notApplicable,
                    notApplicable ? reason : null, notApplicable ? operator : null,
                    notApplicable ? LocalDateTime.now() : null, operator);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleId", cycleId);
        result.put("employeeId", employeeId);
        result.put("itemId", itemId);
        result.put("notApplicable", notApplicable);
        result.put("naReason", notApplicable ? reason : null);
        return result;
    }

    // ------------------------------------------------------------------
    // 供归集/结果服务复用
    // ------------------------------------------------------------------

    /** 归集值落库（system 行覆盖更新；not_applicable 标记保留）。由 {@code PerfCollectService} 调用。 */
    @Transactional
    public void persistCollected(Map<String, Object> cycle, Long employeeId, List<PerfCollectedValue> values) {
        Long cycleId = numberAsLong(cycle.get("id"));
        String operator = currentUsername();
        for (PerfCollectedValue value : values) {
            Map<String, Object> existing = scoreMapper.selectSystemRow(cycleId, employeeId, value.itemId());
            BigDecimal autoScore = percent(value.autoScore());
            if (existing == null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cycleId", cycleId);
                row.put("employeeId", employeeId);
                row.put("templateItemId", value.itemId());
                row.put("itemCode", value.itemCode());
                row.put("itemName", value.itemName());
                row.put("itemCategory", "collectable");
                row.put("weight", value.weight());
                row.put("raterType", SYSTEM);
                row.put("raterId", null);
                row.put("raterName", SYSTEM);
                row.put("autoScore", autoScore);
                row.put("score", autoScore);
                row.put("previousScore", null);
                row.put("version", 1);
                row.put("comment", null);
                row.put("notApplicable", false);
                row.put("naReason", null);
                row.put("naMarkedBy", null);
                row.put("naMarkedTime", null);
                scoreMapper.insertScore(row, operator);
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("itemCode", value.itemCode());
                row.put("itemName", value.itemName());
                row.put("itemCategory", "collectable");
                row.put("weight", value.weight());
                row.put("autoScore", autoScore);
                row.put("score", autoScore);
                row.put("previousScore", numberAsBigDecimal(existing.get("autoScore")));
                scoreMapper.updateSystemRow(numberAsLong(existing.get("id")), row, operator);
            }
        }
    }

    /** 某员工某指标的 system 行（含 not_applicable 标记），无则 null。 */
    public Map<String, Object> systemRow(Long cycleId, Long employeeId, Long itemId) {
        return scoreMapper.selectSystemRow(cycleId, employeeId, itemId);
    }

    /** 某员工某周期的当前版本评分行（system + self + manager）。 */
    public List<Map<String, Object>> latestRows(Long cycleId, Long employeeId) {
        return scoreMapper.selectLatestRows(cycleId, employeeId);
    }

    public List<Long> employeesWithManualScores(Long cycleId) {
        return scoreMapper.selectEmployeesWithManualScores(cycleId);
    }

    public int systemRowCount(Long cycleId, Long employeeId) {
        return scoreMapper.countSystemRows(cycleId, employeeId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Map<String, Object> requireScoringCycle(Long cycleId) {
        Map<String, Object> cycle = cycleService.requireCycle(cycleId);
        cycleService.requireEditable(cycle);
        String status = String.valueOf(cycle.get("status"));
        if (!com.teamflow.perf.status.PerfCycleStatusMachine.ACTIVE.equals(status)
                && !com.teamflow.perf.status.PerfCycleStatusMachine.SCORING.equals(status)) {
            throw new BusinessException("绩效周期当前状态为「"
                    + com.teamflow.perf.status.PerfCycleStatusMachine.label(status)
                    + "」，只有进行中/评分中的周期可以评分（请先开启周期）");
        }
        return cycle;
    }

    private int appendScores(Map<String, Object> cycle, Long employeeId,
                            List<PerfItemScoreRequest> requests, String raterType) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("评分明细不能为空");
        }
        Long cycleId = numberAsLong(cycle.get("id"));
        Map<Long, Map<String, Object>> itemIndex = new LinkedHashMap<>();
        for (Map<String, Object> item : templateService.requireItems(numberAsLong(cycle.get("templateId")))) {
            itemIndex.put(numberAsLong(item.get("id")), item);
        }
        String operator = currentUsername();
        Long raterId = platformMapper.selectUserIdByUsername(operator);
        int saved = 0;
        for (PerfItemScoreRequest request : requests) {
            Long itemId = request.getItemId();
            Map<String, Object> item = itemId == null ? null : itemIndex.get(itemId);
            if (item == null) {
                throw new BusinessException("指标项不存在或不属于本周期模板");
            }
            if (!"subjective".equals(String.valueOf(item.get("itemCategory")))) {
                throw new BusinessException("指标项「" + item.get("itemName")
                        + "」为可归集指标，分值由系统归集且只读，禁止手工覆盖（D-06.3）");
            }
            BigDecimal score = request.getScore();
            if (score == null) {
                throw new BusinessException("指标项「" + item.get("itemName") + "」必须填写分值");
            }
            BigDecimal maxScore = zeroIfNull(numberAsBigDecimal(item.get("maxScore"))).signum() == 0
                    ? HUNDRED : numberAsBigDecimal(item.get("maxScore"));
            if (score.signum() < 0 || score.compareTo(maxScore) > 0) {
                throw new BusinessException("指标项「" + item.get("itemName") + "」分值必须在 0 到 "
                        + maxScore.toPlainString() + " 之间");
            }
            String comment = trimToNull(request.getComment());
            if (comment == null) {
                throw new BusinessException("主观指标「" + item.get("itemName") + "」必须填写评语（R1）");
            }
            Map<String, Object> previous = scoreMapper.selectLatestRow(cycleId, employeeId, itemId, raterType);
            int version = scoreMapper.selectMaxVersion(cycleId, employeeId, itemId, raterType) + 1;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cycleId", cycleId);
            row.put("employeeId", employeeId);
            row.put("templateItemId", itemId);
            row.put("itemCode", item.get("itemCode"));
            row.put("itemName", item.get("itemName"));
            row.put("itemCategory", item.get("itemCategory"));
            row.put("weight", numberAsBigDecimal(item.get("weight")));
            row.put("raterType", raterType);
            row.put("raterId", raterId);
            row.put("raterName", operator);
            row.put("autoScore", null);
            row.put("score", percent(score));
            row.put("previousScore", previous == null ? null : numberAsBigDecimal(previous.get("score")));
            row.put("version", version);
            row.put("comment", comment);
            row.put("notApplicable", false);
            row.put("naReason", null);
            row.put("naMarkedBy", null);
            row.put("naMarkedTime", null);
            scoreMapper.insertScore(row, operator);
            saved++;
        }
        return saved;
    }

    private Map<String, Object> systemRow(Map<String, Object> cycle, Map<String, Object> item, Long employeeId,
                                          BigDecimal autoScore) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cycleId", numberAsLong(cycle.get("id")));
        row.put("employeeId", employeeId);
        row.put("templateItemId", numberAsLong(item.get("id")));
        row.put("itemCode", item.get("itemCode"));
        row.put("itemName", item.get("itemName"));
        row.put("itemCategory", item.get("itemCategory"));
        row.put("weight", numberAsBigDecimal(item.get("weight")));
        row.put("raterType", SYSTEM);
        row.put("raterId", null);
        row.put("raterName", SYSTEM);
        row.put("autoScore", autoScore);
        row.put("score", autoScore);
        row.put("previousScore", null);
        row.put("version", 1);
        row.put("comment", null);
        row.put("notApplicable", false);
        row.put("naReason", null);
        row.put("naMarkedBy", null);
        row.put("naMarkedTime", null);
        return row;
    }

    private void decorateRow(Map<String, Object> row) {
        row.put("notApplicable", intOf(row.get("notApplicable")) == 1);
        String raterType = String.valueOf(row.get("raterType"));
        row.put("raterTypeLabel", switch (raterType) {
            case SYSTEM -> "系统归集";
            case SELF -> "自评";
            case MANAGER -> "上级评";
            default -> raterType;
        });
        row.put("itemCategoryLabel", "subjective".equals(String.valueOf(row.get("itemCategory"))) ? "主观" : "可归集");
    }
}
