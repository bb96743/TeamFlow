package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.perf.domain.dto.PerfCycleWriteRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.mapper.PerfCycleMapper;
import com.teamflow.perf.status.PerfCycleStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M05 绩效周期服务：周期 CRUD 与状态机流转（R5：锁定不可逆）。
 *
 * <p>状态唯一来源是 {@link PerfCycleStatusMachine}；归档与锁定因带副作用（固化结果、批量置只读）
 * 分别由 {@code PerfResultService.archive / lock} 触发，本服务的状态接口只处理
 * {@code draft→active}（开启，含模板完整性校验）与 {@code active→scoring}（进入评分）。</p>
 */
@Service
public class PerfCycleService extends PerfServiceSupport {

    private static final Set<String> CYCLE_TYPES = Set.of("quarter", "month");
    /** 状态接口允许直接流转的目标（归档/锁定走结果接口）。 */
    private static final Set<String> DIRECT_STATUS_TARGETS = Set.of(PerfCycleStatusMachine.ACTIVE,
            PerfCycleStatusMachine.SCORING);

    private final PerfCycleMapper cycleMapper;
    private final PerfTemplateService templateService;

    public PerfCycleService(PerfCycleMapper cycleMapper, PerfTemplateService templateService) {
        this.cycleMapper = cycleMapper;
        this.templateService = templateService;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status) {
        Page page = pageOf(query);
        long total = cycleMapper.countCycles(trimToNull(keyword), trimToNull(status));
        List<Map<String, Object>> rows = cycleMapper.selectCycles(
                trimToNull(keyword), trimToNull(status), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        return decorate(requireCycle(id));
    }

    @Transactional
    public PerfIdResponse create(PerfCycleWriteRequest request) {
        String code = required(request.getCycleCode(), "周期编码不能为空");
        String name = required(request.getCycleName(), "周期名称不能为空");
        String type = defaultString(request.getCycleType(), "quarter");
        if (!CYCLE_TYPES.contains(type)) {
            throw new BusinessException("周期类型只能为 quarter 或 month");
        }
        LocalDate start = parseDate(request.getStartDate(), "周期开始日期");
        LocalDate end = parseDate(request.getEndDate(), "周期结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("周期结束日期不能早于开始日期");
        }
        if (cycleMapper.countCycleCode(code, null) > 0) {
            throw new BusinessException("周期编码已存在：" + code);
        }
        Long templateId = request.getTemplateId();
        if (templateId != null) {
            templateService.requireTemplate(templateId);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cycleCode", code);
        row.put("cycleName", name);
        row.put("cycleType", type);
        row.put("startDate", start.toString());
        row.put("endDate", end.toString());
        row.put("templateId", templateId);
        row.put("status", PerfCycleStatusMachine.DRAFT);
        row.put("remark", trimToNull(request.getRemark()));
        cycleMapper.insertCycle(row, currentUsername());
        return new PerfIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, PerfCycleWriteRequest request) {
        Map<String, Object> existing = requireCycle(id);
        requireEditable(existing);
        String type = request.getCycleType() == null
                ? String.valueOf(existing.get("cycleType")) : request.getCycleType();
        if (!CYCLE_TYPES.contains(type)) {
            throw new BusinessException("周期类型只能为 quarter 或 month");
        }
        LocalDate start = request.getStartDate() == null
                ? LocalDate.parse(String.valueOf(existing.get("startDate")))
                : parseDate(request.getStartDate(), "周期开始日期");
        LocalDate end = request.getEndDate() == null
                ? LocalDate.parse(String.valueOf(existing.get("endDate")))
                : parseDate(request.getEndDate(), "周期结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("周期结束日期不能早于开始日期");
        }
        Long templateId = request.getTemplateId() == null
                ? numberAsLong(existing.get("templateId")) : request.getTemplateId();
        if (templateId != null && templateId != 0L) {
            templateService.requireTemplate(templateId);
        } else {
            templateId = null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cycleName", request.getCycleName() == null
                ? String.valueOf(existing.get("cycleName"))
                : required(request.getCycleName(), "周期名称不能为空"));
        row.put("cycleType", type);
        row.put("startDate", start.toString());
        row.put("endDate", end.toString());
        row.put("templateId", templateId);
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        cycleMapper.updateCycle(id, row, currentUsername());
    }

    /**
     * 状态流转（仅 draft→active 开启、active→scoring 进入评分；归档/锁定走结果接口）。
     * 开启时按 R6 校验模板：启用状态、指标项非空、权重合计 =100、可归集指标均已登记字典四要素。
     */
    @Transactional
    public Map<String, Object> updateStatus(Long id, String targetStatus) {
        Map<String, Object> existing = requireCycle(id);
        String currentStatus = String.valueOf(existing.get("status"));
        String status = required(targetStatus, "目标状态不能为空");
        if (status.equals(currentStatus)) {
            return decorate(requireCycle(id));
        }
        if (PerfCycleStatusMachine.isLocked(currentStatus)) {
            throw new BusinessException("绩效周期已锁定，状态不可再变更（锁定不可逆，R5）");
        }
        if (!DIRECT_STATUS_TARGETS.contains(status)) {
            throw new BusinessException("状态「" + PerfCycleStatusMachine.label(status)
                    + "」不能通过状态接口流转：归档请调用结果归档接口、锁定请调用结果锁定接口");
        }
        try {
            PerfCycleStatusMachine.requireTransition(currentStatus, status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
        if (PerfCycleStatusMachine.ACTIVE.equals(status)) {
            requireLaunchReady(existing);
        }
        cycleMapper.updateCycleStatus(id, status, currentUsername());
        return decorate(requireCycle(id));
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requireCycle(id);
        if (!PerfCycleStatusMachine.DRAFT.equals(String.valueOf(existing.get("status")))) {
            throw new BusinessException("只有草稿状态的绩效周期可以删除");
        }
        cycleMapper.softDeleteCycle(id, currentUsername());
    }

    // ------------------------------------------------------------------
    // 供归集 / 评分 / 结果服务复用的校验
    // ------------------------------------------------------------------

    public Map<String, Object> requireCycle(Long id) {
        if (id == null) {
            throw new BusinessException("绩效周期 ID 不能为空");
        }
        Map<String, Object> row = cycleMapper.selectCycleById(id);
        if (row == null) {
            throw new BusinessException("绩效周期不存在或已删除");
        }
        return row;
    }

    /** 周期可编辑（草稿/进行中/评分中）；归档/锁定后拒绝并给出明确提示。 */
    public void requireEditable(Map<String, Object> cycle) {
        String status = String.valueOf(cycle.get("status"));
        if (PerfCycleStatusMachine.isEditable(status)) {
            return;
        }
        if (PerfCycleStatusMachine.LOCKED.equals(status)) {
            throw new BusinessException("绩效周期「" + cycle.get("cycleName")
                    + "」已锁定，结果只读；如需调整只能通过申诉流程（perf_appeal）");
        }
        throw new BusinessException("绩效周期「" + cycle.get("cycleName") + "」已归档，结果只读，不允许修改");
    }

    /** 周期可归集（草稿可预演、进行中与评分中可重算；归档/锁定后不得改动结果）。 */
    public void requireCollectable(Map<String, Object> cycle) {
        String status = String.valueOf(cycle.get("status"));
        if (PerfCycleStatusMachine.isCollectable(status)) {
            return;
        }
        throw new BusinessException("绩效周期「" + cycle.get("cycleName") + "」当前状态为「"
                + PerfCycleStatusMachine.label(status) + "」，归档/锁定后不允许重算归集");
    }

    /** 归集重算后更新归集时间。 */
    public void markCollectTime(Long cycleId) {
        cycleMapper.markCollectTime(cycleId, LocalDateTime.now(), currentUsername());
    }

    public void markArchived(Long cycleId) {
        cycleMapper.markArchived(cycleId, LocalDateTime.now(), currentUsername());
    }

    public void markLocked(Long cycleId) {
        cycleMapper.markLocked(cycleId, LocalDateTime.now(), currentUsername());
    }

    private void requireLaunchReady(Map<String, Object> cycle) {
        Long templateId = numberAsLong(cycle.get("templateId"));
        if (templateId == null) {
            throw new BusinessException("开启绩效周期前必须绑定指标模板");
        }
        templateService.requireLaunchReady(templateId);
    }

    private Map<String, Object> decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        row.put("statusLabel", PerfCycleStatusMachine.label(status));
        row.put("allowedTransitions", PerfCycleStatusMachine.allowedTargets(status));
        row.put("locked", PerfCycleStatusMachine.isLocked(status));
        row.put("editable", PerfCycleStatusMachine.isEditable(status));
        return row;
    }
}
