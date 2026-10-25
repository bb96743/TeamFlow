package com.teamflow.task.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.task.domain.dto.TaskAssignRequest;
import com.teamflow.task.domain.dto.TaskUpdateRequest;
import com.teamflow.task.domain.dto.TaskWriteRequest;
import com.teamflow.task.domain.vo.TaskIdResponse;
import com.teamflow.task.mapper.TaskAssigneeMapper;
import com.teamflow.task.mapper.TaskMapper;
import com.teamflow.task.mapper.TaskPlatformMapper;
import com.teamflow.task.mapper.TaskRelationMapper;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M03 任务服务：任务增删改查、指派/转派、认领、我的任务、待认领池。
 *
 * <p><b>状态变更一律不在这里直接写</b>：创建/指派导致的初始状态由 {@link TaskStatusService}
 * 与 {@link TaskFlowTraceService} 协同留痕，显式流转走 {@link TaskStatusService#transition}——
 * 保证「合法性只在 TaskStatusMachine 判断一次、每次流转都留痕、每次都发事件」。</p>
 *
 * <p><b>职责边界</b>（M03 RULE §二/§四）：只读写 {@code task_*}/{@code flow_*}；评论归 M09
 * （不建 {@code task_comment}）、附件走 M00、消息走 M09、统计归 M13；不读写 {@code okr_*}，
 * 任务↔KR 关联只存 {@code task_task.kr_id} 并发事件（D-2026-10-25-05）。</p>
 */
@Service
public class TaskService extends TaskServiceSupport {

    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TaskMapper taskMapper;
    private final TaskRelationMapper relationMapper;
    private final TaskAssigneeMapper assigneeMapper;
    private final TaskPlatformMapper platformMapper;
    private final TaskHierarchyService hierarchyService;
    private final TaskFlowTraceService traceService;
    private final TaskEventPublisher eventPublisher;
    private final TaskStatusService statusService;

    public TaskService(
            TaskMapper taskMapper,
            TaskRelationMapper relationMapper,
            TaskAssigneeMapper assigneeMapper,
            TaskPlatformMapper platformMapper,
            TaskHierarchyService hierarchyService,
            TaskFlowTraceService traceService,
            TaskEventPublisher eventPublisher,
            TaskStatusService statusService
    ) {
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
        this.assigneeMapper = assigneeMapper;
        this.platformMapper = platformMapper;
        this.hierarchyService = hierarchyService;
        this.traceService = traceService;
        this.eventPublisher = eventPublisher;
        this.statusService = statusService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @DataScope
    public PageResult<Map<String, Object>> list(
            PageQuery query, String keyword, String status, Long assigneeId, Long krId, String priority) {
        Page page = pageOf(query);
        long total = taskMapper.countTasks(
                trimToNull(keyword), trimToNull(status), assigneeId, krId, trimToNull(priority), false,
                DataScopeContext.current());
        List<Map<String, Object>> rows = taskMapper.selectTasks(
                trimToNull(keyword), trimToNull(status), assigneeId, krId, trimToNull(priority), false,
                DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorateListRow);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 待认领池（R4）：无责任人任务只允许出现在这里。 */
    @DataScope
    public PageResult<Map<String, Object>> pool(PageQuery query, String keyword) {
        Page page = pageOf(query);
        long total = taskMapper.countTasks(trimToNull(keyword), null, null, null, null, true, DataScopeContext.current());
        List<Map<String, Object>> rows = taskMapper.selectTasks(trimToNull(keyword), null, null, null, null, true,
                DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorateListRow);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 我的任务：责任人是当前登录用户。 */
    @DataScope
    public PageResult<Map<String, Object>> myTasks(PageQuery query, String status) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        if (userId == null) {
            return PageResult.of(List.of(), 0, page.pageNum(), page.pageSize());
        }
        long total = taskMapper.countTasks(null, trimToNull(status), userId, null, null, false,
                DataScopeContext.current());
        List<Map<String, Object>> rows = taskMapper.selectTasks(null, trimToNull(status), userId, null, null, false,
                DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorateListRow);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long taskId) {
        Map<String, Object> row = taskMapper.selectTask(taskId, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("任务不存在或无权访问");
        }
        return decorateDetail(row);
    }

    /** 任务的全部流程轨迹（R2 可查：创建 → 指派 → 每次流转，含操作人/前后状态/时间/意见）。 */
    @DataScope
    public List<Map<String, Object>> traces(Long taskId) {
        Map<String, Object> row = taskMapper.selectTask(taskId, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("任务不存在或无权访问");
        }
        return traceService.taskTraces(taskId);
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    @Transactional
    public TaskIdResponse create(TaskWriteRequest request) {
        String title = required(request.getTaskTitle(), "任务标题不能为空");
        String priority = defaultString(request.getPriority(), "normal");
        requirePriority(priority);
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);

        Long assigneeId = request.getAssigneeId();
        if (assigneeId != null && platformMapper.countActiveUser(assigneeId) == 0) {
            throw new BusinessException("责任人不存在或已停用");
        }
        Long parentTaskId = request.getParentTaskId();
        Long krId = request.getKrId();
        if (parentTaskId != null) {
            Map<String, Object> parent = statusService.requireTask(parentTaskId);
            if (krId == null) {
                krId = numberAsLong(parent.get("krId"));
            }
        }
        Long deptId = assigneeId == null
                ? platformMapper.selectDeptIdByUserId(operatorId)
                : platformMapper.selectDeptIdByUserId(assigneeId);

        String status = assigneeId == null ? TaskStatusMachine.POOL : TaskStatusMachine.TODO;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskTitle", title);
        row.put("taskDesc", trimToNull(request.getTaskDesc()));
        row.put("taskType", parentTaskId == null ? "general" : "subtask");
        row.put("krId", krId);
        row.put("priority", priority);
        row.put("status", status);
        row.put("assigneeId", assigneeId);
        row.put("creatorId", operatorId);
        row.put("deptId", deptId);
        row.put("startDate", parseDate(request.getStartDate(), "计划开始日期"));
        row.put("dueDate", parseDate(request.getDueDate(), "截止日期"));
        row.put("subTaskCount", 0);
        row.put("doneSubTaskCount", 0);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        taskMapper.insertTask(row, username);
        Long taskId = numberAsLong(row.get("id"));

        if (parentTaskId != null) {
            relationMapper.insertRelation(taskId, parentTaskId, "subtask", null, username);
            taskMapper.addSubTaskCount(parentTaskId, 1, 0, username);
        }
        if (assigneeId != null) {
            assigneeMapper.upsertAssignee(taskId, assigneeId, "owner", operatorId, "创建任务时指派", username);
        }
        traceService.recordCreation(taskId, title, status, operatorId, username, "创建任务");

        // R3：创建也是一次状态落地（无 → 初始状态），同样发事件，保证 KR 分母从一开始就正确
        eventPublisher.publishStatusChanged(
                taskId, krId, hierarchyService.rootTaskId(taskId), hierarchyService.isRoot(taskId),
                null, status, operatorId);
        return new TaskIdResponse(taskId);
    }

    @Transactional
    public void update(Long taskId, TaskUpdateRequest request) {
        Map<String, Object> existing = statusService.requireTask(taskId);
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);

        String title = request.getTaskTitle() == null
                ? String.valueOf(existing.get("taskTitle"))
                : required(request.getTaskTitle(), "任务标题不能为空");
        String priority = request.getPriority() == null
                ? String.valueOf(existing.get("priority")) : request.getPriority();
        requirePriority(priority);

        Long oldKrId = numberAsLong(existing.get("krId"));
        Long newKrId = resolveNewKrId(request.getKrId(), oldKrId);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskTitle", title);
        row.put("taskDesc", request.getTaskDesc() == null ? existing.get("taskDesc") : trimToNull(request.getTaskDesc()));
        row.put("krId", newKrId);
        row.put("priority", priority);
        row.put("startDate", request.getStartDate() == null
                ? parseDate(dateText(existing.get("startDate")), "计划开始日期")
                : parseDate(request.getStartDate(), "计划开始日期"));
        row.put("dueDate", request.getDueDate() == null
                ? parseDate(dateText(existing.get("dueDate")), "截止日期")
                : parseDate(request.getDueDate(), "截止日期"));
        row.put("sort", request.getSort() == null ? numberAsLong(existing.get("sort")).intValue() : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        taskMapper.updateTask(taskId, row, username);

        if (!java.util.Objects.equals(oldKrId, newKrId)) {
            // 补 G-3 遗留事件：改挂 KR 必须通知 M02 清理旧投影，否则旧 KR 进度永久虚高
            String taskStatus = String.valueOf(existing.get("status"));
            eventPublisher.publishKrChanged(
                    taskId, oldKrId, newKrId, hierarchyService.rootTaskId(taskId),
                    hierarchyService.isRoot(taskId), taskStatus, operatorId);
        }
    }

    @Transactional
    public Map<String, Object> assign(Long taskId, TaskAssignRequest request) {
        Map<String, Object> existing = statusService.requireTask(taskId);
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);

        Long assigneeId = request.getAssigneeId();
        if (assigneeId != null && assigneeId == 0L) {
            assigneeId = null;
        }
        if (assigneeId != null && platformMapper.countActiveUser(assigneeId) == 0) {
            throw new BusinessException("责任人不存在或已停用");
        }
        String fromStatus = String.valueOf(existing.get("status"));
        String toStatus = fromStatus;
        if (assigneeId == null) {
            // 收回指派：任务退回待认领池（R4 要求无责任人任务只能存在于待认领池）
            toStatus = TaskStatusMachine.POOL;
        } else if (TaskStatusMachine.POOL.equals(fromStatus)) {
            toStatus = TaskStatusMachine.TODO;
        }
        Long deptId = assigneeId == null ? null : platformMapper.selectDeptIdByUserId(assigneeId);
        taskMapper.updateTaskAssignee(taskId, assigneeId, deptId, toStatus, username);

        assigneeMapper.deactivateOwners(taskId, username);
        if (assigneeId != null) {
            assigneeMapper.upsertAssignee(taskId, assigneeId, "owner", operatorId,
                    defaultString(request.getRemark(), "指派/转派"), username);
        }

        String opinion = assigneeId == null
                ? "收回指派：" + username + " 将任务退回待认领池"
                : "指派给 " + assigneeId + "（" + defaultString(platformMapper.selectNicknameById(assigneeId), "未知用户") + "）";
        if (!toStatus.equals(fromStatus)) {
            // 指派导致的状态变化（pool → todo，或无责任人时 → pool）按状态流转口径留痕 + 发事件
            traceService.recordStatusTransition(existing, fromStatus, toStatus, operatorId, username, opinion);
            eventPublisher.publishStatusChanged(
                    taskId, numberAsLong(existing.get("krId")), hierarchyService.rootTaskId(taskId),
                    hierarchyService.isRoot(taskId), fromStatus, toStatus, operatorId);
        } else {
            traceService.recordAssignment(existing, fromStatus, operatorId, username, opinion);
        }
        // 注意：这里不能调 this.detail(taskId)——同类自调用不会经过 @DataScope 代理，
        // 会拿到默认拒绝的空数据范围而误报「任务不存在」；写操作已用内部查询校验过存在性。
        return decorateDetail(statusService.requireTask(taskId));
    }

    /** 认领：把待认领池中的任务指派给当前登录用户，并进入待办。 */
    @Transactional
    public Map<String, Object> claim(Long taskId) {
        Map<String, Object> existing = statusService.requireTask(taskId);
        String fromStatus = String.valueOf(existing.get("status"));
        if (!TaskStatusMachine.POOL.equals(fromStatus)) {
            throw new BusinessException("任务不在待认领池（当前状态：" + TaskStatusMachine.label(fromStatus) + "），不能认领");
        }
        if (numberAsLong(existing.get("assigneeId")) != null) {
            throw new BusinessException("任务已有责任人，请使用转派接口");
        }
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        if (operatorId == null) {
            throw new BusinessException("当前登录用户不存在，无法认领");
        }
        taskMapper.updateTaskAssignee(taskId, operatorId, platformMapper.selectDeptIdByUserId(operatorId),
                TaskStatusMachine.TODO, username);
        assigneeMapper.deactivateOwners(taskId, username);
        assigneeMapper.upsertAssignee(taskId, operatorId, "owner", operatorId, "任务认领", username);
        traceService.recordStatusTransition(existing, fromStatus, TaskStatusMachine.TODO, operatorId, username,
                "认领任务");
        eventPublisher.publishStatusChanged(
                taskId, numberAsLong(existing.get("krId")), hierarchyService.rootTaskId(taskId),
                hierarchyService.isRoot(taskId), fromStatus, TaskStatusMachine.TODO, operatorId);
        return decorateDetail(statusService.requireTask(taskId));
    }

    @Transactional
    public void delete(Long taskId) {
        Map<String, Object> existing = statusService.requireTask(taskId);
        String username = currentUsername();
        Long operatorId = platformMapper.selectUserIdByUsername(username);
        if (relationMapper.countChildren(taskId) > 0) {
            throw new BusinessException("任务存在子任务，不能删除（请先删除或转移子任务）");
        }
        String status = String.valueOf(existing.get("status"));
        hierarchyService.detachFromParent(taskId, status, username);
        taskMapper.softDeleteTask(taskId, username);
        traceService.terminateStatusInstance(taskId, username);
        // 补 G-3 遗留事件：删除后必须让 M02 清理该任务在所有 KR 上的投影行
        eventPublisher.publishDeleted(
                taskId, numberAsLong(existing.get("krId")), hierarchyService.rootTaskId(taskId),
                hierarchyService.isRoot(taskId), operatorId);
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 请求里的 krId：null 表示不变，0 表示解除关联，其他值为新 KR。 */
    private Long resolveNewKrId(Long requested, Long oldKrId) {
        if (requested == null) {
            return oldKrId;
        }
        return requested == 0L ? null : requested;
    }

    private void requirePriority(String priority) {
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException("任务优先级只能为 low、normal、high、urgent");
        }
    }

    private Long currentUserId() {
        return platformMapper.selectUserIdByUsername(currentUsername());
    }

    private void decorateListRow(Map<String, Object> row) {
        String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
        row.put("statusLabel", TaskStatusMachine.label(status));
        row.put("subProgress", subProgress(row));
    }

    private Map<String, Object> decorateDetail(Map<String, Object> row) {
        Long taskId = numberAsLong(row.get("id"));
        String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
        row.put("statusLabel", TaskStatusMachine.label(status));
        row.put("isRoot", hierarchyService.isRoot(taskId));
        row.put("rootTaskId", hierarchyService.rootTaskId(taskId));
        row.put("allowedTransitions", TaskStatusMachine.allowedTargets(status));
        row.put("subProgress", subProgress(row));
        row.put("assignees", assigneeMapper.selectAssignees(taskId));
        row.put("children", relationMapper.selectChildren(taskId));
        return row;
    }

    /** 子任务完成度（DoD）：已完成直接子任务数 / 直接子任务总数，保留 2 位小数。 */
    private BigDecimal subProgress(Map<String, Object> row) {
        Long total = numberAsLong(row.get("subTaskCount"));
        Long done = numberAsLong(row.get("doneSubTaskCount"));
        int totalCount = total == null ? 0 : total.intValue();
        int doneCount = done == null ? 0 : done.intValue();
        if (totalCount <= 0) {
            return BigDecimal.ZERO;
        }
        int effectiveDone = Math.min(Math.max(doneCount, 0), totalCount);
        return BigDecimal.valueOf(effectiveDone)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
    }
}
