package com.teamflow.task.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * M03 对 M05 绩效的**只读任务事实取数端口**。
 *
 * <p><b>为什么需要它</b>：M05 归集需要任务完成数与时限达成情况，但 L0 §4.3 / M05 RULE §四
 * 明确「禁止跨模块直连业务表」——M05 不得写 {@code SELECT ... FROM task_task}。任务数据的归属方是 M03，
 * 因此由 M03 提供本服务作为唯一读出口，M05 注入本 Bean 取数（同模块内直接注入 Spring Bean）。
 * 本类**只读、无业务副作用，也不改变既有任务语义**。</p>
 *
 * <p>取数口径（与 M05 RULE §六之一 / 指标字典一致）：顶层任务（子任务不计入）、
 * {@code due_date} 落在周期内；{@code due_date} 为空时按创建时间落在周期内判定。
 * 时限达标/完成率的**计算**属 M05 口径，本服务只返回事实（status/dueDate/doneTime）。</p>
 */
@Service
public class TaskPerfQueryService {

    private final TaskMapper taskMapper;

    public TaskPerfQueryService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 某责任人在给定日期范围内的顶层任务事实清单。
     *
     * @param assigneeId 任务责任人（被评人）用户 ID
     * @param startDate  绩效周期开始日期
     * @param endDate    绩效周期结束日期
     * @return 任务事实（taskId / taskTitle / status / krId / dueDate / doneTime / assigneeId），按 ID 升序
     */
    public List<Map<String, Object>> topLevelTaskFacts(Long assigneeId, LocalDate startDate, LocalDate endDate) {
        if (assigneeId == null) {
            return List.of();
        }
        if (startDate == null || endDate == null) {
            throw new BusinessException("绩效归集取数必须提供周期起止日期");
        }
        return taskMapper.selectPerfTopLevelTasks(assigneeId, startDate.toString(), endDate.toString());
    }

    /**
     * 成员负载取数（M04 团队视图用，L0 §9.1 {@code D-2026-10-26-08} 结论一）。
     *
     * <p>M04 **不得直连 {@code task_*}**：由本只读端口按用户集合批量返回顶层任务的
     * 「任务总数 / 进行中数」，M04 注入本 Service 获取。只读、无业务副作用、不改变任务语义。</p>
     *
     * @param assigneeIds 用户 ID 集合（可含 null/重复，内部去重；空集合返回空列表）
     * @return 每行 {@code assigneeId / taskTotal / taskInProgress}；无任务的用户不出现在结果中（消费方补 0）
     */
    public List<Map<String, Object>> memberTaskLoads(List<Long> assigneeIds) {
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = assigneeIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectMemberTaskLoads(userIds);
    }
}
