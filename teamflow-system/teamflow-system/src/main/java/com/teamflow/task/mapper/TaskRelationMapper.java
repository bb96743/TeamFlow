package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M03 任务关系表（{@code task_relation}）访问层。
 *
 * <p><b>父子关系是 isRoot / rootTaskId 的唯一判定依据</b>（不额外冗余 {@code parent_id} 字段，
 * 避免出现两份真相）。约定 {@code relation_type='subtask'} 时 {@code task_id} 是
 * {@code related_task_id} 的子任务。</p>
 */
@Mapper
public interface TaskRelationMapper {

    /** 取直接父任务 ID；无父任务返回 null（即顶层任务 isRoot=true）。 */
    @Select("""
            SELECT related_task_id FROM task_relation
            WHERE task_id = #{taskId} AND relation_type = 'subtask' AND deleted = 0
            LIMIT 1
            """)
    Long selectParentTaskId(@Param("taskId") Long taskId);

    @Insert("""
            INSERT INTO task_relation (task_id, related_task_id, relation_type, remark, create_by, update_by)
            VALUES (#{taskId}, #{relatedTaskId}, #{relationType}, #{remark}, #{operator}, #{operator})
            """)
    int insertRelation(
            @Param("taskId") Long taskId,
            @Param("relatedTaskId") Long relatedTaskId,
            @Param("relationType") String relationType,
            @Param("remark") String remark,
            @Param("operator") String operator
    );

    @Select("""
            SELECT COUNT(*) FROM task_relation
            WHERE related_task_id = #{parentTaskId} AND relation_type = 'subtask' AND deleted = 0
            """)
    int countChildren(@Param("parentTaskId") Long parentTaskId);

    /** 子任务列表（按父任务查直接子任务），用于详情页展示。 */
    @Select("""
            SELECT r.task_id AS taskId,
                   t.task_title AS taskTitle,
                   t.status,
                   t.assignee_id AS assigneeId
            FROM task_relation r
            JOIN task_task t ON t.id = r.task_id AND t.deleted = 0
            WHERE r.related_task_id = #{parentTaskId} AND r.relation_type = 'subtask' AND r.deleted = 0
            ORDER BY r.id
            """)
    List<Map<String, Object>> selectChildren(@Param("parentTaskId") Long parentTaskId);

    /** 任务删除时清理其作为子任务的关系行（存在子任务的父任务不允许删除，故无需清理反向关系）。 */
    @Update("""
            UPDATE task_relation
            SET deleted = 1, update_by = #{operator}
            WHERE task_id = #{taskId} AND deleted = 0
            """)
    int softDeleteRelationsOfTask(@Param("taskId") Long taskId, @Param("operator") String operator);
}
