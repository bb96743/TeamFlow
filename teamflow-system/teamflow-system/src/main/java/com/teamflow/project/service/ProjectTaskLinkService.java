package com.teamflow.project.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.project.domain.dto.ProjectTaskLinkRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.mapper.ProjectTaskLinkMapper;
import com.teamflow.project.stage.ProjectStatusMachine;
import com.teamflow.task.service.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14 项目-任务直连关联与甘特数据服务（需求 2.5.2 + M2-1 遗留）。
 *
 * <p><b>分工（领导者裁定）</b>：{@code proj_task_link} 由本模块持有（project_id + task_id + 关联类型），
 * 但**任务存在性与任务事实一律经任务模块 Service 只读获取**（{@link TaskService#detail(Long)}），
 * 本模块不直连任务表、不复制任务标题/状态/时间快照；任务模块侧也不加任何项目字段
 * （D-05「任务↔KR 权威关联在任务模块」不变，项目关联是另一个维度）。</p>
 *
 * <p><b>甘特数据</b>：按项目读取关联任务，逐条经任务模块读取计划起止、实际完成时间、状态与完成度（子任务 DoD），
 * 组装时间线行；里程碑时间线一并返回。关键路径/依赖图需要任务依赖端口，本期未实现（见 RULE §九）。</p>
 */
@Service
public class ProjectTaskLinkService extends ProjectServiceSupport {

    private static final Set<String> LINK_TYPES = Set.of("plan", "execution");
    /** 单个项目时间线最多读取的任务数（防止超大项目拖垮接口）。 */
    private static final int MAX_TIMELINE_TASKS = 300;

    private final ProjectMapper projectMapper;
    private final ProjectTaskLinkMapper linkMapper;
    private final ProjectMilestoneService milestoneService;
    /** M03 只读端口：任务事实的唯一来源（不直连任务表）。 */
    private final TaskService taskService;

    public ProjectTaskLinkService(
            ProjectPlatformMapper platformMapper,
            ProjectMapper projectMapper,
            ProjectTaskLinkMapper linkMapper,
            ProjectMilestoneService milestoneService,
            TaskService taskService
    ) {
        super(platformMapper);
        this.projectMapper = projectMapper;
        this.linkMapper = linkMapper;
        this.milestoneService = milestoneService;
        this.taskService = taskService;
    }

    /** 项目已关联任务列表（只有关联与引用，不含任务事实快照）。 */
    @DataScope
    public List<Map<String, Object>> listLinks(Long projectId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        List<Map<String, Object>> rows = linkMapper.selectByProject(projectId);
        rows.forEach(row -> row.put("linkTypeLabel", linkTypeLabel(stringOf(row.get("linkType")))));
        return rows;
    }

    /** 关联任务：任务必须存在且当前用户可见（经任务模块 Service 校验）。 */
    @Transactional
    @DataScope
    public ProjectIdResponse addLink(Long projectId, ProjectTaskLinkRequest request) {
        Map<String, Object> project = requireEditableProject(projectId, DataScopeContext.current());
        Long taskId = request == null ? null : request.taskId();
        if (taskId == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        String linkType = defaultString(request.linkType(), "execution");
        if (!LINK_TYPES.contains(linkType)) {
            throw new BusinessException("关联类型只能为 plan 或 execution");
        }
        if (linkMapper.countLink(projectId, taskId) > 0) {
            throw new BusinessException("该任务已关联本项目，无需重复关联");
        }
        // 任务存在性经 M03 Service 校验（同时校验当前用户对任务的数据范围）
        taskService.detail(taskId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", projectId);
        row.put("taskId", taskId);
        row.put("linkType", linkType);
        row.put("remark", trimToNull(request.remark()));
        linkMapper.insertLink(row, currentUsername());
        return new ProjectIdResponse(numberAsLong(row.get("id")));
    }

    /** 取消关联（逻辑删除；只删关联，不动任务本身）。 */
    @Transactional
    @DataScope
    public void removeLink(Long projectId, Long linkId) {
        requireEditableProject(projectId, DataScopeContext.current());
        if (linkMapper.selectLink(projectId, linkId) == null) {
            throw new BusinessException("项目任务关联不存在或已取消");
        }
        linkMapper.softDeleteLink(projectId, linkId, currentUsername());
    }

    /**
     * 甘特/时间线数据：项目 + 关联任务（任务事实经 M03 只读端口）+ 里程碑。
     *
     * <p>约束：不返回任何任务表字段快照；任务不可见/已删除时该行标记 {@code missing=true}，接口不整体失败。</p>
     */
    @DataScope
    public Map<String, Object> gantt(Long projectId) {
        Map<String, Object> project = requireVisibleProject(projectId, DataScopeContext.current());
        List<Map<String, Object>> links = linkMapper.selectByProject(projectId);
        if (links.size() > MAX_TIMELINE_TASKS) {
            throw new BusinessException("项目关联任务过多（" + links.size() + " 个），时间线单次最多返回 "
                    + MAX_TIMELINE_TASKS + " 个，请先分批取消关联");
        }
        List<Map<String, Object>> tasks = new ArrayList<>(links.size());
        int completed = 0;
        int inProgress = 0;
        int notStarted = 0;
        int delayed = 0;
        for (Map<String, Object> link : links) {
            Long taskId = numberAsLong(link.get("taskId"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("linkId", numberAsLong(link.get("id")));
            row.put("taskId", taskId);
            row.put("linkType", stringOf(link.get("linkType")));
            row.put("linkTypeLabel", linkTypeLabel(stringOf(link.get("linkType"))));
            row.put("linkRemark", link.get("remark"));
            try {
                Map<String, Object> task = taskService.detail(taskId);
                String status = stringOf(task.get("status"));
                row.put("taskTitle", task.get("taskTitle"));
                row.put("status", status);
                row.put("statusLabel", task.get("statusLabel"));
                row.put("priority", task.get("priority"));
                row.put("startDate", task.get("startDate"));
                row.put("dueDate", task.get("dueDate"));
                row.put("doneTime", task.get("doneTime"));
                row.put("assigneeName", task.get("assigneeName"));
                // 完成度口径与任务模块一致：M03 子任务 DoD（subProgress），不自行计算
                row.put("completionPercent", task.get("subProgress"));
                boolean isDone = "done".equals(status) || "closed".equals(status);
                row.put("delayed", !isDone && isOverdue(stringOf(task.get("dueDate"))));
                row.put("missing", false);
                if ("done".equals(status)) {
                    completed++;
                } else if ("in_progress".equals(status)) {
                    inProgress++;
                } else {
                    notStarted++;
                }
                if (Boolean.TRUE.equals(row.get("delayed"))) {
                    delayed++;
                }
            } catch (BusinessException exception) {
                // 任务已删除或当前用户无权查看：保留关联行并标记缺失，避免整页失败
                row.put("taskTitle", null);
                row.put("missing", true);
                row.put("missingReason", exception.getMessage());
            }
            tasks.add(row);
        }
        List<Map<String, Object>> milestones = milestoneService.listForProject(projectId);
        Map<String, Object> milestoneSummary = milestoneService.projectProgress(milestones);
        int linkedTasks = tasks.stream().filter(row -> !Boolean.TRUE.equals(row.get("missing"))).toList().size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("linkedTaskCount", links.size());
        summary.put("visibleTaskCount", linkedTasks);
        summary.put("completedTaskCount", completed);
        summary.put("inProgressTaskCount", inProgress);
        summary.put("notStartedTaskCount", notStarted);
        summary.put("delayedTaskCount", delayed);
        summary.put("taskCompletionPercent", linkedTasks == 0 ? null : scale2(BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(linkedTasks), 2, java.math.RoundingMode.HALF_UP)));
        summary.put("milestoneCount", milestoneSummary.get("milestoneCount"));
        summary.put("completedMilestoneCount", milestoneSummary.get("completedMilestoneCount"));
        summary.put("delayedMilestoneCount", milestoneSummary.get("delayedMilestoneCount"));
        summary.put("projectProgress", milestoneSummary.get("projectProgress"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", numberAsLong(project.get("id")));
        result.put("projectCode", project.get("projectCode"));
        result.put("projectName", project.get("projectName"));
        result.put("status", project.get("status"));
        result.put("statusLabel", ProjectStatusMachine.projectLabel(stringOf(project.get("status"))));
        result.put("startDate", project.get("startDate"));
        result.put("endDate", project.get("endDate"));
        result.put("ownerName", project.get("ownerName"));
        result.put("tasks", tasks);
        result.put("milestones", milestones);
        result.put("summary", summary);
        result.put("taskSource", "任务事实经任务模块只读端口实时读取；本模块只保存关联关系，不存任务快照");
        result.put("criticalPathSupported", false);
        result.put("criticalPathNote", "依赖与关键路径需要任务依赖关系端口，本期未实现；时间线按任务计划起止与完成度展示");
        return result;
    }

    /** 项目删除时级联软删关联（调用方已校验项目为草稿且可见）。 */
    @Transactional
    public void cascadeSoftDelete(Long projectId) {
        linkMapper.softDeleteByProject(projectId, currentUsername());
    }

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
        String status = stringOf(project.get("status"));
        if (!ProjectStatusMachine.isProjectEditable(status)) {
            throw new BusinessException("项目当前状态为「" + ProjectStatusMachine.projectLabel(status)
                    + "」，已结项/已归档项目的任务关联只读");
        }
        return project;
    }

    private boolean isOverdue(String dueDate) {
        if (dueDate == null || dueDate.isBlank()) {
            return false;
        }
        try {
            return LocalDate.parse(dueDate, DATE_FORMAT).isBefore(LocalDate.now());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String linkTypeLabel(String linkType) {
        return switch (linkType == null ? "" : linkType) {
            case "plan" -> "计划承接";
            case "execution" -> "执行任务";
            default -> linkType;
        };
    }

    private String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
