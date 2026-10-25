package com.teamflow.task.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.task.domain.dto.TaskApprovalRequest;
import com.teamflow.task.domain.dto.TaskAssignRequest;
import com.teamflow.task.domain.dto.TaskStatusRequest;
import com.teamflow.task.domain.dto.TaskUpdateRequest;
import com.teamflow.task.domain.dto.TaskWriteRequest;
import com.teamflow.task.domain.vo.TaskIdResponse;
import com.teamflow.task.service.TaskFlowService;
import com.teamflow.task.service.TaskService;
import com.teamflow.task.service.TaskStatusService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M03 任务接口（{@code /api/task/tasks}），M1 主链路「派任务」的落地点。
 *
 * <p>横切约定：每个接口都有 {@code @RequiresPermission}（权限码 {@code task:*}）；
 * 写接口加 {@code @OperLog}；列表/详情/轨迹在 Service 层加 {@code @DataScope}（G-2 默认拒绝）。</p>
 */
@RestController
@RequestMapping("/api/task/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskStatusService statusService;
    private final TaskFlowService flowService;

    public TaskController(TaskService taskService, TaskStatusService statusService, TaskFlowService flowService) {
        this.taskService = taskService;
        this.statusService = statusService;
        this.flowService = flowService;
    }

    @GetMapping
    @RequiresPermission("task:task:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long krId,
            @RequestParam(required = false) String priority
    ) {
        return R.success(taskService.list(query, keyword, status, assigneeId, krId, priority));
    }

    @GetMapping("/my")
    @RequiresPermission("task:task:list")
    public R<PageResult<Map<String, Object>>> myTasks(
            PageQuery query,
            @RequestParam(required = false) String status
    ) {
        return R.success(taskService.myTasks(query, status));
    }

    @GetMapping("/pool")
    @RequiresPermission("task:task:list")
    public R<PageResult<Map<String, Object>>> pool(
            PageQuery query,
            @RequestParam(required = false) String keyword
    ) {
        return R.success(taskService.pool(query, keyword));
    }

    @GetMapping("/{id}")
    @RequiresPermission("task:task:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(taskService.detail(id));
    }

    @GetMapping("/{id}/traces")
    @RequiresPermission("task:task:list")
    public R<List<Map<String, Object>>> traces(@PathVariable Long id) {
        return R.success(taskService.traces(id));
    }

    @PostMapping
    @RequiresPermission("task:task:add")
    @OperLog(module = "任务管理", action = "新增")
    public R<TaskIdResponse> create(@RequestBody TaskWriteRequest request) {
        return R.success(taskService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("task:task:edit")
    @OperLog(module = "任务管理", action = "修改")
    public R<Void> update(@PathVariable Long id, @RequestBody TaskUpdateRequest request) {
        taskService.update(id, request);
        return R.success(null);
    }

    @PutMapping("/{id}/assign")
    @RequiresPermission("task:task:assign")
    @OperLog(module = "任务管理", action = "指派/转派")
    public R<Map<String, Object>> assign(@PathVariable Long id, @RequestBody TaskAssignRequest request) {
        return R.success(taskService.assign(id, request));
    }

    @PutMapping("/{id}/claim")
    @RequiresPermission("task:task:assign")
    @OperLog(module = "任务管理", action = "认领")
    public R<Map<String, Object>> claim(@PathVariable Long id) {
        return R.success(taskService.claim(id));
    }

    @PutMapping("/{id}/status")
    @RequiresPermission("task:task:status")
    @OperLog(module = "任务管理", action = "状态流转")
    public R<Map<String, Object>> transition(@PathVariable Long id, @RequestBody TaskStatusRequest request) {
        return R.success(statusService.transition(id, request));
    }

    /** 发起线性单节点审批（通过后才真正流转状态；复杂审批属 P2 的 3.1 审批中心）。 */
    @PostMapping("/{id}/approvals")
    @RequiresPermission("task:task:status")
    @OperLog(module = "任务管理", action = "发起审批")
    public R<TaskIdResponse> startApproval(@PathVariable Long id, @RequestBody TaskApprovalRequest request) {
        return R.success(flowService.startApproval(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("task:task:remove")
    @OperLog(module = "任务管理", action = "删除")
    public R<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return R.success(null);
    }
}
