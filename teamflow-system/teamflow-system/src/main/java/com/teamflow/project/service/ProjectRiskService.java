package com.teamflow.project.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.project.domain.dto.ProjectRiskStatusRequest;
import com.teamflow.project.domain.dto.ProjectRiskWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.mapper.ProjectRiskMapper;
import com.teamflow.project.stage.ProjectRiskStatusMachine;
import com.teamflow.project.stage.ProjectStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M14 项目风险登记服务（需求 2.5.3）。
 *
 * <p><b>状态机单一来源</b>：{@link ProjectRiskStatusMachine}；每次状态变更同时写一条
 * {@code proj_risk_log} 留痕（append-only），非法流转由状态机拒绝并返回 400。
 * 风险归属项目，读写均先按项目数据范围校验可见性；项目归档后风险只读。</p>
 */
@Service
public class ProjectRiskService extends ProjectServiceSupport {

    private final ProjectMapper projectMapper;
    private final ProjectRiskMapper riskMapper;

    public ProjectRiskService(
            ProjectPlatformMapper platformMapper,
            ProjectMapper projectMapper,
            ProjectRiskMapper riskMapper
    ) {
        super(platformMapper);
        this.projectMapper = projectMapper;
        this.riskMapper = riskMapper;
    }

    /** 风险分页（可按状态筛选）。 */
    @DataScope
    public PageResult<Map<String, Object>> list(Long projectId, PageQuery query, String status) {
        requireVisibleProject(projectId, DataScopeContext.current());
        Page page = pageOf(query);
        String statusFilter = trimToNull(status);
        long total = riskMapper.countRisks(projectId, statusFilter);
        List<Map<String, Object>> rows = riskMapper.selectRisks(projectId, statusFilter, page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long projectId, Long riskId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        Map<String, Object> risk = requireRisk(projectId, riskId);
        decorate(risk);
        risk.put("logs", riskMapper.selectLogs(riskId));
        return risk;
    }

    @Transactional
    @DataScope
    public ProjectIdResponse create(Long projectId, ProjectRiskWriteRequest request) {
        requireWritableProject(projectId, DataScopeContext.current());
        Map<String, Object> row = buildRow(request, null);
        row.put("projectId", projectId);
        row.put("status", ProjectRiskStatusMachine.RISK_IDENTIFIED);
        riskMapper.insertRisk(row, currentUsername());
        Long riskId = numberAsLong(row.get("id"));
        Long operatorId = currentUserId();
        riskMapper.insertLog(riskId, null, ProjectRiskStatusMachine.RISK_IDENTIFIED,
                "登记风险", operatorId, currentUsername());
        return new ProjectIdResponse(riskId);
    }

    @Transactional
    @DataScope
    public void update(Long projectId, Long riskId, ProjectRiskWriteRequest request) {
        requireWritableProject(projectId, DataScopeContext.current());
        Map<String, Object> existing = requireRisk(projectId, riskId);
        Map<String, Object> row = buildRow(request, existing);
        riskMapper.updateRisk(riskId, row, currentUsername());
    }

    /** 状态流转：校验状态机 → 更新 → 写留痕（from/to/意见/操作人）。 */
    @Transactional
    @DataScope
    public Map<String, Object> changeStatus(Long projectId, Long riskId, ProjectRiskStatusRequest request) {
        requireWritableProject(projectId, DataScopeContext.current());
        Map<String, Object> existing = requireRisk(projectId, riskId);
        String from = String.valueOf(existing.get("status"));
        String to = required(request == null ? null : request.status(), "目标状态不能为空");
        requireTransition(() -> ProjectRiskStatusMachine.requireTransition(from, to));
        riskMapper.updateRiskStatus(riskId, to, currentUsername());
        riskMapper.insertLog(riskId, from, to, trimToNull(request == null ? null : request.note()),
                currentUserId(), currentUsername());
        Map<String, Object> risk = requireRisk(projectId, riskId);
        decorate(risk);
        risk.put("logs", riskMapper.selectLogs(riskId));
        return risk;
    }

    /** 状态流转留痕（append-only，按时间正序）。 */
    @DataScope
    public List<Map<String, Object>> logs(Long projectId, Long riskId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        requireRisk(projectId, riskId);
        return riskMapper.selectLogs(riskId);
    }

    @Transactional
    @DataScope
    public void delete(Long projectId, Long riskId) {
        requireWritableProject(projectId, DataScopeContext.current());
        requireRisk(projectId, riskId);
        riskMapper.softDeleteRisk(riskId, currentUsername());
    }

    /** 状态机定义（唯一来源导出，供前端渲染可流转状态）。 */
    public Map<String, Object> statusMachine() {
        return ProjectRiskStatusMachine.definition();
    }

    /** 项目删除时级联软删风险（调用方已校验项目为草稿且可见）。 */
    @Transactional
    public void cascadeSoftDelete(Long projectId) {
        riskMapper.softDeleteByProject(projectId, currentUsername());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildRow(ProjectRiskWriteRequest request, Map<String, Object> existing) {
        String title;
        String desc;
        String level;
        String type;
        Long ownerId;
        String responsePlan;
        String dueDate;
        String remark;
        if (existing == null) {
            if (request == null) {
                throw new BusinessException("风险登记请求不能为空");
            }
            title = required(request.riskTitle(), "风险标题不能为空");
            desc = trimToNull(request.riskDesc());
            level = defaultString(request.riskLevel(), "medium");
            type = defaultString(request.riskType(), "schedule");
            ownerId = request.ownerId();
            responsePlan = trimToNull(request.responsePlan());
            dueDate = trimToNull(request.dueDate());
            remark = trimToNull(request.remark());
        } else {
            title = request == null || request.riskTitle() == null
                    ? stringOf(existing.get("riskTitle"))
                    : required(request.riskTitle(), "风险标题不能为空");
            desc = request == null || request.riskDesc() == null
                    ? stringOf(existing.get("riskDesc")) : trimToNull(request.riskDesc());
            level = request == null || request.riskLevel() == null
                    ? stringOf(existing.get("riskLevel")) : request.riskLevel();
            type = request == null || request.riskType() == null
                    ? stringOf(existing.get("riskType")) : request.riskType();
            ownerId = request == null || request.ownerId() == null
                    ? numberAsLong(existing.get("ownerId")) : request.ownerId();
            responsePlan = request == null || request.responsePlan() == null
                    ? stringOf(existing.get("responsePlan")) : trimToNull(request.responsePlan());
            dueDate = request == null || request.dueDate() == null
                    ? stringOf(existing.get("dueDate")) : trimToNull(request.dueDate());
            remark = request == null || request.remark() == null
                    ? stringOf(existing.get("remark")) : trimToNull(request.remark());
        }
        if (!ProjectRiskStatusMachine.LEVELS.contains(level)) {
            throw new BusinessException("风险等级只能为 low、medium、high 或 critical");
        }
        if (!ProjectRiskStatusMachine.TYPES.contains(type)) {
            throw new BusinessException("风险类别只能为 schedule、cost、quality、resource 或 other");
        }
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("风险责任人不存在或已停用");
        }
        if (dueDate != null) {
            parseDate(dueDate, "计划应对完成日期");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("riskTitle", title);
        row.put("riskDesc", desc);
        row.put("riskLevel", level);
        row.put("riskType", type);
        row.put("ownerId", ownerId);
        row.put("responsePlan", responsePlan);
        row.put("dueDate", dueDate);
        row.put("remark", remark);
        return row;
    }

    private void decorate(Map<String, Object> risk) {
        String status = stringOf(risk.get("status"));
        risk.put("statusLabel", ProjectRiskStatusMachine.label(status));
        risk.put("nextStatuses", ProjectRiskStatusMachine.nextStatuses(status));
        risk.put("riskLevelLabel", ProjectRiskStatusMachine.levelLabel(stringOf(risk.get("riskLevel"))));
        risk.put("riskTypeLabel", ProjectRiskStatusMachine.typeLabel(stringOf(risk.get("riskType"))));
        risk.put("delayed", isOdd(risk));
    }

    /** 计划应对日期已过且风险未关闭/未取消 → 逾期（仅展示标记，不改变风险状态）。 */
    private boolean isOdd(Map<String, Object> risk) {
        String status = stringOf(risk.get("status"));
        if (ProjectRiskStatusMachine.RISK_CLOSED.equals(status)
                || ProjectRiskStatusMachine.RISK_CANCELLED.equals(status)) {
            return false;
        }
        String dueDate = stringOf(risk.get("dueDate"));
        if (dueDate == null || dueDate.isBlank()) {
            return false;
        }
        try {
            return java.time.LocalDate.parse(dueDate, DATE_FORMAT).isBefore(java.time.LocalDate.now());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Map<String, Object> requireVisibleProject(Long projectId, DataScopeCriteria scope) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        Map<String, Object> project = projectMapper.selectProject(projectId, scope);
        if (project == null) {
            throw new BusinessException("项目不存在或无权访问");
        }
        return project;
    }

    /** 风险写操作：项目未归档即可（结项后仍可关闭遗留风险，归档后全页只读）。 */
    private Map<String, Object> requireWritableProject(Long projectId, DataScopeCriteria scope) {
        Map<String, Object> project = requireVisibleProject(projectId, scope);
        String status = stringOf(project.get("status"));
        if (ProjectStatusMachine.PROJECT_ARCHIVED.equals(status)) {
            throw new BusinessException("项目已归档，风险登记只读");
        }
        return project;
    }

    private Map<String, Object> requireRisk(Long projectId, Long riskId) {
        if (riskId == null) {
            throw new BusinessException("风险 ID 不能为空");
        }
        Map<String, Object> risk = riskMapper.selectRiskById(riskId);
        if (risk == null) {
            throw new BusinessException("风险不存在或已删除");
        }
        if (!projectId.equals(numberAsLong(risk.get("projectId")))) {
            throw new BusinessException("风险不属于该项目");
        }
        return risk;
    }

    private String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
