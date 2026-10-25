package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M03 任务指派关系表（{@code task_assignee}）访问层。
 *
 * <p>主责人（{@code owner}）与任务表的 {@code assignee_id} 保持一致：转派时先把旧 owner 行置删除，
 * 再 upsert 新 owner；参与人（{@code participant}）本期不做接口，只保留表能力。</p>
 */
@Mapper
public interface TaskAssigneeMapper {

    /** upsert：同一 (task, user) 只保留一行，重复指派时复活并更新类型。 */
    @Insert("""
            INSERT INTO task_assignee (task_id, user_id, assign_type, assigned_by, remark, create_by, update_by)
            VALUES (#{taskId}, #{userId}, #{assignType}, #{assignedBy}, #{remark}, #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE
                assign_type = VALUES(assign_type),
                assigned_by = VALUES(assigned_by),
                remark = VALUES(remark),
                deleted = 0,
                update_by = VALUES(update_by)
            """)
    int upsertAssignee(
            @Param("taskId") Long taskId,
            @Param("userId") Long userId,
            @Param("assignType") String assignType,
            @Param("assignedBy") Long assignedBy,
            @Param("remark") String remark,
            @Param("operator") String operator
    );

    /** 把任务当前所有主责人行置为删除（转派/收回指派时调用）。 */
    @Update("""
            UPDATE task_assignee
            SET deleted = 1, update_by = #{operator}
            WHERE task_id = #{taskId} AND assign_type = 'owner' AND deleted = 0
            """)
    int deactivateOwners(@Param("taskId") Long taskId, @Param("operator") String operator);

    @Select("""
            SELECT a.id,
                   a.task_id AS taskId,
                   a.user_id AS userId,
                   u.nickname AS userName,
                   a.assign_type AS assignType,
                   a.assigned_by AS assignedBy,
                   DATE_FORMAT(a.assign_time, '%Y-%m-%d %H:%i') AS assignTime,
                   a.remark
            FROM task_assignee a
            LEFT JOIN sys_user u ON u.id = a.user_id
            WHERE a.task_id = #{taskId} AND a.deleted = 0
            ORDER BY a.assign_type, a.id
            """)
    List<Map<String, Object>> selectAssignees(@Param("taskId") Long taskId);
}
