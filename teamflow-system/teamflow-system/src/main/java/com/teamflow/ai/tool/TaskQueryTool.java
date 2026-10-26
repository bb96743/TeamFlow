package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.task.service.TaskService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查询任务（task.query）。
 *
 * <p>以调用者身份调用 M03 的任务查询 Service：既有权限码闸门，也有 {@code @DataScope} 数据范围，
 * 助手不会因为「AI 调用」而看到比调用者本人更多的任务。</p>
 */
@Component
public class TaskQueryTool implements AiTool {

    private final TaskService taskService;
    private final AiToolSupport support;

    public TaskQueryTool(TaskService taskService, AiToolSupport support) {
        this.taskService = taskService;
        this.support = support;
    }

    @Override
    public String name() {
        return "task.query";
    }

    @Override
    public String label() {
        return "查询任务";
    }

    @Override
    public String permissionCode() {
        return "task:task:list";
    }

    @Override
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        String employeeName = support.text(params, "employeeName", "");
        LocalDate[] range = support.range(support.text(params, "range", ""), LocalDate.now());
        String targetLabel;
        Long assigneeId;
        Map<String, Object> user = employeeName.isBlank() ? null : support.findUser(employeeName);
        if (employeeName.isBlank()) {
            assigneeId = caller.userId();
            targetLabel = "我（" + caller.displayName() + "）";
        } else if (user == null) {
            return ToolOutcome.ok(name(), params, permissionCode(),
                    "未找到员工「" + employeeName + "」，请确认姓名或账号；已按当前用户可见范围未读取数据。",
                    Map.of("employee", employeeName, "tasks", List.of()));
        } else {
            assigneeId = support.userId(user);
            targetLabel = support.displayName(user);
        }

        PageResult<Map<String, Object>> page = taskService.list(
                new PageQuery(1, 50, null, null), null, null, assigneeId, null, null);
        List<Map<String, Object>> rows = page.rows() == null ? List.of() : page.rows();
        List<Map<String, Object>> inRange = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String startDate = support.text(row, "startDate");
            String dueDate = support.text(row, "dueDate");
            if (startDate.isBlank() && dueDate.isBlank()) {
                continue;
            }
            if (com.teamflow.ai.support.AiDates.inRange(startDate, range[0], range[1])
                    || com.teamflow.ai.support.AiDates.inRange(dueDate, range[0], range[1])) {
                inRange.add(row);
            }
        }
        List<Map<String, Object>> display = inRange.isEmpty() ? rows.stream().limit(10).toList() : inRange;

        StringBuilder summary = new StringBuilder();
        summary.append("员工：").append(targetLabel)
                .append("｜区间：").append(support.rangeLabel(range))
                .append("｜该员工可见任务共 ").append(page.total()).append(" 条");
        if (inRange.isEmpty() && !rows.isEmpty()) {
            summary.append("（区间内无任务，以下为最近 10 条）");
        }
        summary.append("：");
        int index = 1;
        for (Map<String, Object> row : display) {
            summary.append("\n").append(index++).append(". [")
                    .append(support.taskStatusLabel(support.text(row, "status"))).append("] ")
                    .append(support.text(row, "taskTitle"));
            String dueDate = support.text(row, "dueDate");
            if (!dueDate.isBlank()) {
                summary.append("（截止 ").append(dueDate).append("）");
            }
        }
        if (display.isEmpty()) {
            summary.append("\n（暂无任务）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employee", targetLabel);
        data.put("employeeId", assigneeId);
        data.put("range", support.rangeLabel(range));
        data.put("total", page.total());
        data.put("matched", inRange.size());
        data.put("tasks", display);
        return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data);
    }
}
