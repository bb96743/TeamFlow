package com.teamflow.task.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.task.domain.dto.FlowDefinitionWriteRequest;
import com.teamflow.task.domain.dto.TaskApprovalDecisionRequest;
import com.teamflow.task.domain.vo.TaskIdResponse;
import com.teamflow.task.service.TaskFlowService;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M03 流程接口（{@code /api/task/flow}）：流程定义、流程实例、单节点审批与状态机查询。
 *
 * <p>{@code GET /status-machine} 返回的是 {@link TaskStatusMachine} 里**唯一一份**状态枚举与允许迁移表
 * （外置为数据的可查询视图），用于前端渲染与将来换引擎时的迁移对照。</p>
 */
@RestController
@RequestMapping("/api/task/flow")
public class TaskFlowController {

    private final TaskFlowService flowService;

    public TaskFlowController(TaskFlowService flowService) {
        this.flowService = flowService;
    }

    /** 状态机定义（单一来源导出，只读）。 */
    @GetMapping("/status-machine")
    @RequiresPermission("task:task:list")
    public R<Map<String, Object>> statusMachine() {
        return R.success(TaskStatusMachine.definition());
    }

    @GetMapping("/definitions")
    @RequiresPermission("flow:def:list")
    public R<PageResult<Map<String, Object>>> definitions(
            PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String businessType
    ) {
        return R.success(flowService.listDefinitions(query, keyword, businessType));
    }

    @PostMapping("/definitions")
    @RequiresPermission("flow:def:add")
    @OperLog(module = "流程定义", action = "新增")
    public R<TaskIdResponse> createDefinition(@RequestBody FlowDefinitionWriteRequest request) {
        return R.success(flowService.createDefinition(request));
    }

    @PutMapping("/definitions/{id}")
    @RequiresPermission("flow:def:edit")
    @OperLog(module = "流程定义", action = "修改")
    public R<Void> updateDefinition(@PathVariable Long id, @RequestBody FlowDefinitionWriteRequest request) {
        flowService.updateDefinition(id, request);
        return R.success(null);
    }

    @GetMapping("/instances")
    @RequiresPermission("flow:instance:list")
    public R<PageResult<Map<String, Object>>> instances(
            PageQuery query,
            @RequestParam(required = false) String instanceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long bizId
    ) {
        return R.success(flowService.listInstances(query, instanceType, status, bizId));
    }

    @GetMapping("/instances/{id}")
    @RequiresPermission("flow:instance:list")
    public R<Map<String, Object>> instanceDetail(@PathVariable Long id) {
        return R.success(flowService.instanceDetail(id));
    }

    @PutMapping("/instances/{id}/approve")
    @RequiresPermission("flow:instance:approve")
    @OperLog(module = "审批中心", action = "审批处理")
    public R<Map<String, Object>> approve(
            @PathVariable Long id, @RequestBody TaskApprovalDecisionRequest request) {
        return R.success(flowService.decideApproval(id, request));
    }
}
