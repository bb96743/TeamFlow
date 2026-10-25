package com.teamflow.task.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.mapper.TaskMapper;
import com.teamflow.task.mapper.TaskRelationMapper;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.stereotype.Service;

/**
 * M03 任务层级（父子）判定与子任务汇总服务。
 *
 * <p><b>单一来源</b>：父子关系只存在于 {@code task_relation}，不额外冗余 {@code parent_id}；
 * {@code isRoot}/{@code rootTaskId} 全部由本服务推导，避免多处各算一遍。</p>
 *
 * <p><b>汇总口径</b>（M03 RULE §八 DoD）：父任务维护「直接子任务总数 / 已完成数」两个计数，
 * 子任务完成或返工、子任务删除时增量维护；父任务详情同时返回由计数算出的子任务完成度。
 * 注意：子任务完成<b>不会</b>把父任务自动置为完成（避免隐式级联事件），只做汇总展示。</p>
 */
@Service
public class TaskHierarchyService {

    /** 防御父子关系成环时的死循环上限。 */
    private static final int MAX_DEPTH = 32;

    private final TaskMapper taskMapper;
    private final TaskRelationMapper relationMapper;

    public TaskHierarchyService(TaskMapper taskMapper, TaskRelationMapper relationMapper) {
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
    }

    /** 是否顶层任务（没有父任务即为顶层；M02 只把 isRoot=true 计入 KR 进度分母）。 */
    public boolean isRoot(Long taskId) {
        return relationMapper.selectParentTaskId(taskId) == null;
    }

    /** 顶层任务 ID：顶层任务返回自身，子任务回溯到最顶层祖先。 */
    public Long rootTaskId(Long taskId) {
        Long current = taskId;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Long parentId = relationMapper.selectParentTaskId(current);
            if (parentId == null) {
                return current;
            }
            current = parentId;
        }
        throw new BusinessException("任务父子层级超过 " + MAX_DEPTH + " 层或存在环，无法确定顶层任务");
    }

    /**
     * 任务状态变化后维护父任务的子任务完成计数。
     *
     * @param fromStatus 变更前状态（创建时为 null）
     * @param toStatus   变更后状态
     */
    public void adjustParentAggregation(Long taskId, String fromStatus, String toStatus, String operator) {
        Long parentId = relationMapper.selectParentTaskId(taskId);
        if (parentId == null) {
            return;
        }
        int doneDelta = (TaskStatusMachine.DONE.equals(toStatus) ? 1 : 0)
                - (TaskStatusMachine.DONE.equals(fromStatus) ? 1 : 0);
        if (doneDelta == 0) {
            return;
        }
        taskMapper.addSubTaskCount(parentId, 0, doneDelta, operator);
    }

    /** 删除子任务时回收父任务的计数（总数 -1，若原本已完成则已完成数 -1）。 */
    public void detachFromParent(Long taskId, String status, String operator) {
        Long parentId = relationMapper.selectParentTaskId(taskId);
        if (parentId == null) {
            return;
        }
        int doneDelta = TaskStatusMachine.DONE.equals(status) ? -1 : 0;
        taskMapper.addSubTaskCount(parentId, -1, doneDelta, operator);
        relationMapper.softDeleteRelationsOfTask(taskId, operator);
    }
}
