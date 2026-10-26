package com.teamflow.project.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.project.domain.dto.ProjectMilestoneWriteRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectMilestoneMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.stage.ProjectStatusMachine;
import com.teamflow.task.service.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M14 里程碑服务（L0 2.5.1「里程碑：定义、计划/实际完成时间、完成度汇总」）。
 *
 * <p><b>R3 完成度不可手填</b>：完成度 = 里程碑所关联 KR 的**顶层任务完成率**，由本服务经
 * **M03 的 {@link TaskService#list}** 实时统计（不直连任务表、不在本模块落任务快照）；
 * 未绑定 KR 时完成度返回 {@code null} 并注明原因，接口不接受手填完成度字段。</p>
 *
 * <p><b>R7 延期判定口径唯一</b>：本类 {@link #isDelayed} 是唯一实现——「计划完成日期早于今天且
 * 里程碑未完成/未取消」即延期；前端不得自行判断。</p>
 */
@Service
public class ProjectMilestoneService extends ProjectServiceSupport {

    private static final int TASK_PAGE_SIZE = 100;
    private static final int TASK_MAX_PAGES = 5;

    private final ProjectMilestoneMapper milestoneMapper;
    private final ProjectMapper projectMapper;
    private final TaskService taskService;

    public ProjectMilestoneService(
            ProjectPlatformMapper platformMapper,
            ProjectMilestoneMapper milestoneMapper,
            ProjectMapper projectMapper,
            TaskService taskService
    ) {
        super(platformMapper);
        this.milestoneMapper = milestoneMapper;
        this.projectMapper = projectMapper;
        this.taskService = taskService;
    }

    @DataScope
    public List<Map<String, Object>> list(Long projectId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        return listForProject(projectId);
    }

    @DataScope
    public Map<String, Object> detail(Long projectId, Long milestoneId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        return viewOf(requireMilestone(projectId, milestoneId));
    }

    @Transactional
    @DataScope
    public ProjectIdResponse create(Long projectId, ProjectMilestoneWriteRequest request) {
        Map<String, Object> project = requireEditableProject(projectId, DataScopeContext.current());
        String name = required(request.getMilestoneName(), "里程碑名称不能为空");
        LocalDate planStart = parseNullableDate(request.getPlanStartDate(), "计划开始日期");
        LocalDate planEnd = parseNullableDate(request.getPlanEndDate(), "计划完成日期");
        requireDateOrder(planStart, planEnd);
        String status = defaultString(request.getStatus(), ProjectStatusMachine.MILESTONE_PLANNED);
        if (!ProjectStatusMachine.isMilestoneStatus(status)) {
            throw new BusinessException("里程碑状态只能为 planned / in_progress / completed / cancelled");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", numberAsLong(project.get("id")));
        row.put("milestoneName", name);
        row.put("milestoneDesc", trimToNull(request.getMilestoneDesc()));
        row.put("krId", request.getKrId());
        row.put("planStartDate", planStart == null ? null : planStart.toString());
        row.put("planEndDate", planEnd == null ? null : planEnd.toString());
        row.put("actualStartDate", request.getActualStartDate() == null
                ? null : parseDate(request.getActualStartDate(), "实际开始日期").toString());
        row.put("actualEndDate", request.getActualEndDate() == null
                ? null : parseDate(request.getActualEndDate(), "实际完成日期").toString());
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        milestoneMapper.insertMilestone(row, currentUsername());
        return new ProjectIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    @DataScope
    public Map<String, Object> update(Long projectId, Long milestoneId, ProjectMilestoneWriteRequest request) {
        Map<String, Object> project = requireEditableProject(projectId, DataScopeContext.current());
        Map<String, Object> existing = requireMilestone(projectId, milestoneId);
        String fromStatus = String.valueOf(existing.get("status"));
        String targetStatus = request.getStatus() == null ? fromStatus : request.getStatus();
        if (!ProjectStatusMachine.isMilestoneStatus(targetStatus)) {
            throw new BusinessException("里程碑状态只能为 planned / in_progress / completed / cancelled");
        }
        if (!targetStatus.equals(fromStatus)) {
            requireTransition(() -> ProjectStatusMachine.requireMilestoneTransition(fromStatus, targetStatus));
        }
        LocalDate planStart = request.getPlanStartDate() == null
                ? parseNullableDate((String) existing.get("planStartDate"), "计划开始日期")
                : parseNullableDate(request.getPlanStartDate(), "计划开始日期");
        LocalDate planEnd = request.getPlanEndDate() == null
                ? parseNullableDate((String) existing.get("planEndDate"), "计划完成日期")
                : parseNullableDate(request.getPlanEndDate(), "计划完成日期");
        requireDateOrder(planStart, planEnd);
        LocalDate actualStart = request.getActualStartDate() == null
                ? parseNullableDate((String) existing.get("actualStartDate"), "实际开始日期")
                : parseDate(request.getActualStartDate(), "实际开始日期");
        LocalDate actualEnd = request.getActualEndDate() == null
                ? parseNullableDate((String) existing.get("actualEndDate"), "实际完成日期")
                : parseDate(request.getActualEndDate(), "实际完成日期");
        // 状态推进时自动补实际时间（人只需改状态；实际时间可被显式覆盖）
        if (ProjectStatusMachine.MILESTONE_IN_PROGRESS.equals(targetStatus)
                && !ProjectStatusMachine.MILESTONE_IN_PROGRESS.equals(fromStatus) && actualStart == null) {
            actualStart = LocalDate.now();
        }
        if (ProjectStatusMachine.MILESTONE_COMPLETED.equals(targetStatus) && actualEnd == null) {
            actualEnd = LocalDate.now();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("milestoneName", request.getMilestoneName() == null
                ? existing.get("milestoneName") : required(request.getMilestoneName(), "里程碑名称不能为空"));
        row.put("milestoneDesc", request.getMilestoneDesc() == null
                ? existing.get("milestoneDesc") : trimToNull(request.getMilestoneDesc()));
        row.put("krId", request.getKrId() == null ? existing.get("krId") : request.getKrId());
        row.put("planStartDate", planStart == null ? null : planStart.toString());
        row.put("planEndDate", planEnd == null ? null : planEnd.toString());
        row.put("actualStartDate", actualStart == null ? null : actualStart.toString());
        row.put("actualEndDate", actualEnd == null ? null : actualEnd.toString());
        row.put("sort", request.getSort() == null ? longOf(existing.get("sort")) : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        milestoneMapper.updateMilestone(milestoneId, row, currentUsername());
        if (!targetStatus.equals(fromStatus)) {
            milestoneMapper.updateStatus(milestoneId, targetStatus, actualStart, actualEnd, currentUsername());
        }
        return viewOf(requireMilestone(projectId, milestoneId));
    }

    @Transactional
    @DataScope
    public void delete(Long projectId, Long milestoneId) {
        requireEditableProject(projectId, DataScopeContext.current());
        requireMilestone(projectId, milestoneId);
        milestoneMapper.softDeleteMilestone(milestoneId, currentUsername());
    }

    // ------------------------------------------------------------------
    // 供项目服务复用的内部读取（调用方已完成项目可见性校验）
    // ------------------------------------------------------------------

    /** 项目里程碑列表（含完成度、延期标记），不再重复做项目可见性校验。 */
    public List<Map<String, Object>> listForProject(Long projectId) {
        List<Map<String, Object>> rows = milestoneMapper.selectByProject(projectId);
        for (Map<String, Object> row : rows) {
            decorate(row);
        }
        return rows;
    }

    /** 项目完成度汇总：已绑定 KR 且未取消的里程碑完成度平均值（无数据返回 null）。 */
    public Map<String, Object> projectProgress(List<Map<String, Object>> milestones) {
        long total = milestones.size();
        long completed = milestones.stream()
                .filter(row -> ProjectStatusMachine.MILESTONE_COMPLETED.equals(String.valueOf(row.get("status"))))
                .count();
        long cancelled = milestones.stream()
                .filter(row -> ProjectStatusMachine.MILESTONE_CANCELLED.equals(String.valueOf(row.get("status"))))
                .count();
        long delayed = milestones.stream().filter(row -> Boolean.TRUE.equals(row.get("delayed"))).count();
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        int linked = 0;
        for (Map<String, Object> row : milestones) {
            Object completion = row.get("completion");
            if (completion instanceof java.math.BigDecimal decimal
                    && !ProjectStatusMachine.MILESTONE_CANCELLED.equals(String.valueOf(row.get("status")))) {
                sum = sum.add(decimal);
                linked++;
            }
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("milestoneCount", total);
        progress.put("completedMilestoneCount", completed);
        progress.put("cancelledMilestoneCount", cancelled);
        progress.put("delayedMilestoneCount", delayed);
        progress.put("linkedMilestoneCount", linked);
        progress.put("projectProgress", linked == 0 ? null
                : scale2(sum.divide(java.math.BigDecimal.valueOf(linked), 2, java.math.RoundingMode.HALF_UP)));
        progress.put("note", linked == 0 ? "里程碑均未绑定 KR（或全部取消），项目完成度无法汇总（R3 完成度不可手填）" : null);
        return progress;
    }

    /** 项目删除时级联软删里程碑（调用方已校验项目为草稿且可见）。 */
    @Transactional
    public void cascadeSoftDelete(Long projectId) {
        milestoneMapper.softDeleteByProject(projectId, currentUsername());
    }

    public Map<String, Object> requireMilestone(Long projectId, Long milestoneId) {
        if (milestoneId == null) {
            throw new BusinessException("里程碑 ID 不能为空");
        }
        Map<String, Object> row = milestoneMapper.selectById(milestoneId);
        if (row == null) {
            throw new BusinessException("里程碑不存在或已删除");
        }
        if (!numberAsLong(row.get("projectId")).equals(projectId)) {
            throw new BusinessException("里程碑不属于该项目");
        }
        return row;
    }

    // ------------------------------------------------------------------
    // 完成度（M03 Service）与延期判定（R7 唯一实现）
    // ------------------------------------------------------------------

    private void decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        row.put("statusLabel", ProjectStatusMachine.milestoneLabel(status));
        row.put("nextStatuses", ProjectStatusMachine.milestoneNextStatuses(status));
        row.put("delayed", isDelayed(row));
        row.putAll(taskSummary(numberAsLong(row.get("krId"))));
    }

    private Map<String, Object> viewOf(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        decorate(copy);
        return copy;
    }

    /** R7：计划完成日期早于今天且未完成/未取消 → 延期。 */
    private boolean isDelayed(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        if (ProjectStatusMachine.MILESTONE_COMPLETED.equals(status)
                || ProjectStatusMachine.MILESTONE_CANCELLED.equals(status)) {
            return false;
        }
        String planEnd = row.get("planEndDate") == null ? null : String.valueOf(row.get("planEndDate"));
        if (planEnd == null || planEnd.isBlank()) {
            return false;
        }
        return LocalDate.parse(planEnd).isBefore(LocalDate.now());
    }

    /**
     * 里程碑完成度：经 M03 {@link TaskService#list} 按 KR 统计顶层任务（子任务不计）完成率。
     *
     * <p>TaskService 自带数据范围过滤；单次最多读取 5 页 × 100 条，超出时 {@code sampled=true}
     * 并以样本近似（RULE §九 已登记）。</p>
     */
    private Map<String, Object> taskSummary(Long krId) {
        if (krId == null) {
            return summary(false, 0, 0, false, "未绑定 KR：完成度无法汇总（R3 完成度不可手填）");
        }
        long total = 0;
        long done = 0;
        long fetched = 0;
        boolean sampled = false;
        for (int page = 1; page <= TASK_MAX_PAGES; page++) {
            PageResult<Map<String, Object>> pageResult = taskService.list(
                    new PageQuery(page, TASK_PAGE_SIZE, null, null), null, null, null, krId, null);
            List<Map<String, Object>> rows = pageResult.rows();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> task : rows) {
                fetched++;
                if (task.get("parentTaskId") != null) {
                    continue;
                }
                total++;
                if ("done".equals(String.valueOf(task.get("status")))) {
                    done++;
                }
            }
            if (rows.size() < TASK_PAGE_SIZE) {
                break;
            }
            if (page == TASK_MAX_PAGES) {
                sampled = pageResult.total() > (long) TASK_PAGE_SIZE * TASK_MAX_PAGES;
            }
        }
        if (total == 0) {
            return summary(true, 0, 0, sampled, "该 KR 暂无关联顶层任务：完成度无数据（不显示 0%）");
        }
        java.math.BigDecimal completion = percent(java.math.BigDecimal.valueOf(done),
                java.math.BigDecimal.valueOf(total));
        Map<String, Object> result = summary(true, total, done, sampled,
                sampled ? "任务超过采样上限（500 条），完成度为样本近似" : null);
        result.put("completion", completion);
        return result;
    }

    private Map<String, Object> summary(boolean linked, long total, long done, boolean sampled, String note) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("krLinked", linked);
        summary.put("taskTotal", total);
        summary.put("taskDone", done);
        summary.put("completion", null);
        summary.put("sampled", sampled);
        summary.put("completionNote", note);
        return summary;
    }

    // ------------------------------------------------------------------
    // 项目校验
    // ------------------------------------------------------------------

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

    private Map<String, Object> requireEditableProject(Long projectId, DataScopeCriteria scope) {
        Map<String, Object> project = requireVisibleProject(projectId, scope);
        String status = String.valueOf(project.get("status"));
        if (!ProjectStatusMachine.isProjectEditable(status)) {
            throw new BusinessException("项目当前状态为「" + ProjectStatusMachine.projectLabel(status)
                    + "」，已结项/已归档项目的里程碑只读");
        }
        return project;
    }

    private void requireDateOrder(LocalDate planStart, LocalDate planEnd) {
        if (planStart != null && planEnd != null && planEnd.isBefore(planStart)) {
            throw new BusinessException("计划完成日期不能早于计划开始日期");
        }
    }


}
