package com.teamflow.project.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.okr.service.OkrObjectiveService;
import com.teamflow.project.domain.dto.ProjectWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.stage.ProjectStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14 项目服务（L0 2.5.1「项目立项」）：项目主数据、状态机、与目标的关联、成员与里程碑详情聚合。
 *
 * <p><b>目标关联（硬约束）</b>：项目只存 {@code objective_id} 引用；目标标题与进度经 M02
 * {@link OkrObjectiveService#requireObjective(Long)} **实时读取**，M14 表内不建目标副本、不存标题快照。</p>
 * <p><b>R1 项目不得悬空</b>：{@code objective_id} 与 {@code no_objective_reason} 至少一项，否则拒绝保存。</p>
 * <p><b>R3 完成度不可手填</b>：项目/里程碑完成度均由 {@link ProjectMilestoneService} 经 M03 实时汇总。</p>
 */
@Service
public class ProjectService extends ProjectServiceSupport {

    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");

    private final ProjectMapper projectMapper;
    private final ProjectMemberService memberService;
    private final ProjectMilestoneService milestoneService;
    private final OkrObjectiveService okrObjectiveService;

    public ProjectService(
            ProjectPlatformMapper platformMapper,
            ProjectMapper projectMapper,
            ProjectMemberService memberService,
            ProjectMilestoneService milestoneService,
            OkrObjectiveService okrObjectiveService
    ) {
        super(platformMapper);
        this.projectMapper = projectMapper;
        this.memberService = memberService;
        this.milestoneService = milestoneService;
        this.okrObjectiveService = okrObjectiveService;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status, Long deptId) {
        Page page = pageOf(query);
        long total = projectMapper.countProjects(
                trimToNull(keyword), trimToNull(status), deptId, DataScopeContext.current());
        List<Map<String, Object>> rows = projectMapper.selectProjects(
                trimToNull(keyword), trimToNull(status), deptId, DataScopeContext.current(),
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        return viewOf(requireVisibleProject(id, DataScopeContext.current()));
    }

    @Transactional
    @DataScope
    public ProjectIdResponse create(ProjectWriteRequest request) {
        String code = required(request.getProjectCode(), "项目编号不能为空");
        String name = required(request.getProjectName(), "项目名称不能为空");
        if (projectMapper.countProjectCode(code, null) > 0) {
            throw new BusinessException("项目编号已存在：" + code);
        }
        Long ownerId = request.getOwnerId() == null ? currentUserId() : request.getOwnerId();
        if (ownerId == null || platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("项目负责人不存在或已停用");
        }
        if (request.getDeptId() != null && platformMapper.countDept(request.getDeptId()) == 0) {
            throw new BusinessException("归属部门不存在");
        }
        requireObjectiveOrReason(request.getObjectiveId(), request.getNoObjectiveReason());
        if (request.getObjectiveId() != null) {
            okrObjectiveService.requireObjective(request.getObjectiveId());
        }
        LocalDate start = parseNullableDate(request.getStartDate(), "计划开始日期");
        LocalDate end = parseNullableDate(request.getEndDate(), "计划结束日期");
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException("计划结束日期不能早于计划开始日期");
        }
        String priority = defaultString(request.getPriority(), "normal");
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException("优先级只能为 low / normal / high / urgent");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectCode", code);
        row.put("projectName", name);
        row.put("projectDesc", trimToNull(request.getProjectDesc()));
        row.put("objectiveId", request.getObjectiveId());
        row.put("noObjectiveReason", trimToNull(request.getNoObjectiveReason()));
        row.put("ownerId", ownerId);
        row.put("deptId", request.getDeptId());
        row.put("startDate", start == null ? null : start.toString());
        row.put("endDate", end == null ? null : end.toString());
        row.put("status", ProjectStatusMachine.PROJECT_DRAFT);
        row.put("priority", priority);
        row.put("remark", trimToNull(request.getRemark()));
        projectMapper.insertProject(row, currentUsername());
        Long projectId = numberAsLong(row.get("id"));
        // 负责人同时落一条成员行（成员列表可直接展示负责人；owner 由 owner_id 唯一确定）
        memberService.syncOwnerMember(projectId, ownerId);
        return new ProjectIdResponse(projectId);
    }

    @Transactional
    @DataScope
    public void update(Long id, ProjectWriteRequest request) {
        Map<String, Object> existing = requireEditableProject(id, DataScopeContext.current());
        Long objectiveId = request.getObjectiveId() == null
                ? numberAsLong(existing.get("objectiveId")) : request.getObjectiveId();
        String noObjectiveReason = request.getNoObjectiveReason() == null
                ? trimToNull((String) existing.get("noObjectiveReason"))
                : trimToNull(request.getNoObjectiveReason());
        requireObjectiveOrReason(objectiveId, noObjectiveReason);
        if (request.getObjectiveId() != null && request.getObjectiveId() > 0) {
            okrObjectiveService.requireObjective(request.getObjectiveId());
        }
        Long ownerId = request.getOwnerId() == null
                ? numberAsLong(existing.get("ownerId")) : request.getOwnerId();
        if (ownerId == null || platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("项目负责人不存在或已停用");
        }
        if (request.getDeptId() != null && platformMapper.countDept(request.getDeptId()) == 0) {
            throw new BusinessException("归属部门不存在");
        }
        LocalDate start = request.getStartDate() == null
                ? parseNullableDate((String) existing.get("startDate"), "计划开始日期")
                : parseNullableDate(request.getStartDate(), "计划开始日期");
        LocalDate end = request.getEndDate() == null
                ? parseNullableDate((String) existing.get("endDate"), "计划结束日期")
                : parseNullableDate(request.getEndDate(), "计划结束日期");
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException("计划结束日期不能早于计划开始日期");
        }
        String priority = request.getPriority() == null
                ? String.valueOf(existing.get("priority")) : request.getPriority();
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException("优先级只能为 low / normal / high / urgent");
        }
        Long previousOwnerId = numberAsLong(existing.get("ownerId"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectName", request.getProjectName() == null
                ? existing.get("projectName") : required(request.getProjectName(), "项目名称不能为空"));
        row.put("projectDesc", request.getProjectDesc() == null
                ? existing.get("projectDesc") : trimToNull(request.getProjectDesc()));
        row.put("objectiveId", objectiveId);
        row.put("noObjectiveReason", noObjectiveReason);
        row.put("ownerId", ownerId);
        row.put("deptId", request.getDeptId() == null ? existing.get("deptId") : request.getDeptId());
        row.put("startDate", start == null ? null : start.toString());
        row.put("endDate", end == null ? null : end.toString());
        row.put("priority", priority);
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        projectMapper.updateProject(id, row, currentUsername());
        if (!ownerId.equals(previousOwnerId)) {
            memberService.demoteOwnerMember(id, previousOwnerId);
            memberService.syncOwnerMember(id, ownerId);
        }
    }

    @Transactional
    @DataScope
    public void delete(Long id) {
        Map<String, Object> existing = requireProject(id);
        String status = String.valueOf(existing.get("status"));
        if (!ProjectStatusMachine.PROJECT_DRAFT.equals(status)) {
            throw new BusinessException("只有立项草稿状态的项目可以删除（当前："
                    + ProjectStatusMachine.projectLabel(status) + "）");
        }
        memberService.cascadeSoftDelete(id);
        milestoneService.cascadeSoftDelete(id);
        projectMapper.softDeleteProject(id, currentUsername());
    }

    /** 项目状态流转：draft → active → closed → archived（archived 终态，不可逆）。 */
    @Transactional
    @DataScope
    public Map<String, Object> updateStatus(Long id, String targetStatus) {
        Map<String, Object> existing = requireProject(id);
        String from = String.valueOf(existing.get("status"));
        String to = required(targetStatus, "目标状态不能为空");
        requireTransition(() -> ProjectStatusMachine.requireProjectTransition(from, to));
        projectMapper.updateProjectStatus(id, to, currentUsername());
        return viewOf(requireProject(id));
    }

    /** 状态机定义（唯一来源导出，供前端渲染）。 */
    public Map<String, Object> statusMachine() {
        return ProjectStatusMachine.definition();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 写操作返回视图：内部直接读（避免 @DataScope 自调用丢失数据范围上下文）。 */
    private Map<String, Object> viewOf(Map<String, Object> project) {
        Map<String, Object> row = new LinkedHashMap<>(project);
        decorate(row);
        Long objectiveId = numberAsLong(row.get("objectiveId"));
        row.put("objective", objectiveView(objectiveId));
        List<Map<String, Object>> milestones = milestoneService.listForProject(numberAsLong(row.get("id")));
        row.put("milestones", milestones);
        row.put("members", memberService.listForProject(numberAsLong(row.get("id"))));
        row.put("progress", milestoneService.projectProgress(milestones));
        row.put("objectiveTitleSource", "M02 目标服务实时读取；本模块表内不存目标标题/进度副本");
        row.put("completionSource", "M03 任务服务实时汇总；本模块表内不存任务或完成度快照（R3 完成度不可手填）");
        return row;
    }

    /** 关联目标的实时视图（标题/进度来自 M02 Service，不落本模块表）。 */
    private Map<String, Object> objectiveView(Long objectiveId) {
        if (objectiveId == null) {
            return null;
        }
        try {
            Map<String, Object> objective = okrObjectiveService.requireObjective(objectiveId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", numberAsLong(objective.get("id")));
            view.put("objectiveTitle", objective.get("objectiveTitle"));
            view.put("objectiveDesc", objective.get("objectiveDesc"));
            view.put("periodId", objective.get("periodId"));
            view.put("parentId", objective.get("parentId"));
            view.put("level", objective.get("level"));
            view.put("deptId", objective.get("deptId"));
            view.put("ownerId", objective.get("ownerId"));
            view.put("weight", objective.get("weight"));
            view.put("progress", objective.get("progress"));
            view.put("status", objective.get("status"));
            view.put("source", "M02 目标服务实时读取（本模块只存 objective_id 引用）");
            return view;
        } catch (BusinessException exception) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("id", objectiveId);
            missing.put("missing", true);
            missing.put("note", "关联目标已不存在或已删除（引用悬空）：" + exception.getMessage());
            return missing;
        }
    }

    private void requireObjectiveOrReason(Long objectiveId, String noObjectiveReason) {
        if (objectiveId == null && trimToNull(noObjectiveReason) == null) {
            throw new BusinessException("项目必须关联目标（objectiveId）或填写不关联原因（noObjectiveReason），"
                    + "二者至少一项（R1 项目不得悬空）");
        }
    }

    private Map<String, Object> requireVisibleProject(Long id, DataScopeCriteria scope) {
        if (id == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        Map<String, Object> project = projectMapper.selectProject(id, scope);
        if (project == null) {
            throw new BusinessException("项目不存在或无权访问");
        }
        return project;
    }

    private Map<String, Object> requireEditableProject(Long id, DataScopeCriteria scope) {
        Map<String, Object> project = requireVisibleProject(id, scope);
        String status = String.valueOf(project.get("status"));
        if (!ProjectStatusMachine.isProjectEditable(status)) {
            throw new BusinessException("项目当前状态为「" + ProjectStatusMachine.projectLabel(status)
                    + "」，已结项/已归档项目只读");
        }
        return project;
    }

    Map<String, Object> requireProject(Long id) {
        if (id == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        Map<String, Object> project = projectMapper.selectProjectById(id);
        if (project == null) {
            throw new BusinessException("项目不存在或已删除");
        }
        return project;
    }

    private void decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        row.put("statusLabel", ProjectStatusMachine.projectLabel(status));
        row.put("nextStatuses", ProjectStatusMachine.projectNextStatuses(status));
        row.put("editable", ProjectStatusMachine.isProjectEditable(status));
        row.put("delayed", isProjectDelayed(status, row.get("endDate")));
        row.put("memberRoleOwner", ProjectStatusMachine.ROLE_OWNER);
    }

    /** R7 同口径：计划结束日期早于今天且项目仍在进行 → 延期。 */
    private boolean isProjectDelayed(String status, Object endDate) {
        if (!ProjectStatusMachine.PROJECT_ACTIVE.equals(status) || endDate == null) {
            return false;
        }
        String text = String.valueOf(endDate);
        if (text.length() < 10) {
            return false;
        }
        return LocalDate.parse(text.substring(0, 10)).isBefore(LocalDate.now());
    }

}
