package com.teamflow.okr.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.okr.domain.dto.OkrPeriodWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.mapper.OkrObjectiveMapper;
import com.teamflow.okr.mapper.OkrPeriodMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M02 目标周期服务：季度/月度周期的增删改查与状态流转（开启、归档）。
 *
 * <p>R5 归档只读：周期进入 archived 后，其下目标与 KR 的所有写操作都会被
 * {@link #requireEditable(Long)} 拦截并返回明确业务异常。</p>
 */
@Service
public class OkrPeriodService extends OkrServiceSupport {

    private static final Set<String> PERIOD_TYPES = Set.of("quarter", "month");
    private static final Set<String> STATUSES = Set.of("draft", "active", "archived");
    /** 允许的状态迁移：草稿→进行中→已归档（已归档为终态）。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "draft", Set.of("active", "archived"),
            "active", Set.of("archived"),
            "archived", Set.of()
    );

    private final OkrPeriodMapper periodMapper;
    private final OkrObjectiveMapper objectiveMapper;

    public OkrPeriodService(OkrPeriodMapper periodMapper, OkrObjectiveMapper objectiveMapper) {
        this.periodMapper = periodMapper;
        this.objectiveMapper = objectiveMapper;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status) {
        Page page = pageOf(query);
        long total = periodMapper.countPeriods(keyword, status, DataScopeContext.current());
        List<Map<String, Object>> rows = periodMapper.selectPeriods(
                keyword, status, DataScopeContext.current(), page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = periodMapper.selectPeriod(id, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("周期不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public OkrIdResponse create(OkrPeriodWriteRequest request) {
        String name = required(request.getPeriodName(), "周期名称不能为空");
        String type = defaultString(request.getPeriodType(), "quarter");
        if (!PERIOD_TYPES.contains(type)) {
            throw new BusinessException("周期类型只能为 quarter 或 month");
        }
        LocalDate start = parseDate(request.getStartDate(), "周期开始日期");
        LocalDate end = parseDate(request.getEndDate(), "周期结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("周期结束日期不能早于开始日期");
        }
        if (periodMapper.countPeriodName(name, null) > 0) {
            throw new BusinessException("周期名称已存在");
        }
        String status = defaultString(request.getStatus(), "draft");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("周期状态只能为 draft、active 或 archived");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodName", name);
        row.put("periodType", type);
        row.put("startDate", start.toString());
        row.put("endDate", end.toString());
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        periodMapper.insertPeriod(row, currentUsername());
        return new OkrIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, OkrPeriodWriteRequest request) {
        Map<String, Object> existing = requirePeriod(id);
        requireNotArchived(existing, "周期已归档，不允许修改");
        String name = request.getPeriodName() == null ? String.valueOf(existing.get("periodName"))
                : required(request.getPeriodName(), "周期名称不能为空");
        String type = request.getPeriodType() == null ? String.valueOf(existing.get("periodType"))
                : defaultString(request.getPeriodType(), "quarter");
        if (!PERIOD_TYPES.contains(type)) {
            throw new BusinessException("周期类型只能为 quarter 或 month");
        }
        LocalDate start = request.getStartDate() == null ? LocalDate.parse(String.valueOf(existing.get("startDate")))
                : parseDate(request.getStartDate(), "周期开始日期");
        LocalDate end = request.getEndDate() == null ? LocalDate.parse(String.valueOf(existing.get("endDate")))
                : parseDate(request.getEndDate(), "周期结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("周期结束日期不能早于开始日期");
        }
        if (periodMapper.countPeriodName(name, id) > 0) {
            throw new BusinessException("周期名称已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodName", name);
        row.put("periodType", type);
        row.put("startDate", start.toString());
        row.put("endDate", end.toString());
        row.put("status", existing.get("status"));
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        periodMapper.updatePeriod(id, row, currentUsername());
    }

    @Transactional
    public void updateStatus(Long id, String targetStatus) {
        Map<String, Object> existing = requirePeriod(id);
        String currentStatus = String.valueOf(existing.get("status"));
        String status = required(targetStatus, "目标状态不能为空");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("周期状态只能为 draft、active 或 archived");
        }
        if (status.equals(currentStatus)) {
            return;
        }
        if (currentStatus.equals("archived")) {
            throw new BusinessException("周期已归档，不可变更状态");
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(status)) {
            throw new BusinessException("周期状态不允许从「" + statusText(currentStatus) + "」变更为「" + statusText(status) + "」");
        }
        periodMapper.updatePeriodStatus(id, status, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requirePeriod(id);
        requireNotArchived(existing, "周期已归档，不允许删除");
        if (objectiveMapper.countObjectivesByPeriod(id) > 0) {
            throw new BusinessException("周期下存在目标，不能删除");
        }
        periodMapper.softDeletePeriod(id, currentUsername());
    }

    /**
     * 目标 / KR / 对齐写入前的归档只读校验（M02 RULE R5）。
     *
     * @param periodId 目标所属周期
     */
    public void requireEditable(Long periodId) {
        Map<String, Object> period = requirePeriod(periodId);
        requireNotArchived(period, "周期已归档，其下目标与 KR 均只读，不允许修改");
    }

    public Map<String, Object> requirePeriod(Long id) {
        if (id == null) {
            throw new BusinessException("周期 ID 不能为空");
        }
        Map<String, Object> period = periodMapper.selectPeriodById(id);
        if (period == null) {
            throw new BusinessException("周期不存在");
        }
        return period;
    }

    private void requireNotArchived(Map<String, Object> period, String message) {
        if ("archived".equals(String.valueOf(period.get("status")))) {
            throw new BusinessException(message);
        }
    }

    private LocalDate parseDate(String value, String label) {
        String text = required(value, label + "不能为空");
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }
}
