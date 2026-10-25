package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M03 流程待办表（{@code flow_task}）访问层。
 *
 * <p>本期只承载「线性单节点审批」的待办：发起审批时写一条 {@code pending}，审批后置为
 * {@code approved}/{@code rejected}。会签/并行/加签不在本期范围（P2 的 3.1 审批中心）。</p>
 */
@Mapper
public interface FlowTaskMapper {

    @Insert("""
            INSERT INTO flow_task (instance_id, biz_id, node_key, task_title, assignee_id, status, remark,
                                   create_by, update_by)
            VALUES (#{row.instanceId}, #{row.bizId}, #{row.nodeKey}, #{row.taskTitle}, #{row.assigneeId},
                    #{row.status}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertFlowTask(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, instance_id AS instanceId, biz_id AS bizId, node_key AS nodeKey, task_title AS taskTitle,
                   assignee_id AS assigneeId, status, opinion, operate_time AS operateTime
            FROM flow_task
            WHERE instance_id = #{instanceId} AND status = 'pending' AND deleted = 0
            ORDER BY id
            LIMIT 1
            """)
    Map<String, Object> selectPendingByInstance(@Param("instanceId") Long instanceId);

    @Select("""
            SELECT t.id,
                   t.instance_id AS instanceId,
                   t.biz_id AS bizId,
                   t.node_key AS nodeKey,
                   t.task_title AS taskTitle,
                   t.assignee_id AS assigneeId,
                   u.nickname AS assigneeName,
                   t.status,
                   t.opinion,
                   DATE_FORMAT(t.operate_time, '%Y-%m-%d %H:%i:%s') AS operateTime
            FROM flow_task t
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            WHERE t.instance_id = #{instanceId} AND t.deleted = 0
            ORDER BY t.id
            """)
    List<Map<String, Object>> selectTasksByInstance(@Param("instanceId") Long instanceId);

    @Update("""
            UPDATE flow_task
            SET status = #{status},
                opinion = #{opinion},
                operate_time = #{operateTime},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateDecision(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("opinion") String opinion,
            @Param("operateTime") LocalDateTime operateTime,
            @Param("operator") String operator
    );
}
