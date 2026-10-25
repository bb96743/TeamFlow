package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M03 流程节点实例表（{@code flow_node_instance}）访问层。
 *
 * <p>R2 留痕的落地表：每次流转（含创建、指派、审批结论）写入一行，记录操作人、前后状态、时间、意见。
 * 只追加，不修改历史（删除任务时也不物理删除，保留轨迹）。</p>
 */
@Mapper
public interface FlowNodeInstanceMapper {

    @Insert("""
            INSERT INTO flow_node_instance (instance_id, node_key, node_name, from_status, to_status, node_status,
                                            operator_id, operator_name, opinion, operated_time, remark,
                                            create_by, update_by)
            VALUES (#{row.instanceId}, #{row.nodeKey}, #{row.nodeName}, #{row.fromStatus}, #{row.toStatus},
                    #{row.nodeStatus}, #{row.operatorId}, #{row.operatorName}, #{row.opinion}, #{row.operatedTime},
                    #{row.remark}, #{operator}, #{operator})
            """)
    int insertNode(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT n.id,
                   n.instance_id AS instanceId,
                   n.node_key AS nodeKey,
                   n.node_name AS nodeName,
                   n.from_status AS fromStatus,
                   n.to_status AS toStatus,
                   n.node_status AS nodeStatus,
                   n.operator_id AS operatorId,
                   n.operator_name AS operatorName,
                   n.opinion,
                   DATE_FORMAT(n.operated_time, '%Y-%m-%d %H:%i:%s') AS operatedTime
            FROM flow_node_instance n
            WHERE n.instance_id = #{instanceId} AND n.deleted = 0
            ORDER BY n.id
            """)
    List<Map<String, Object>> selectNodesByInstance(@Param("instanceId") Long instanceId);

    @Select("""
            SELECT n.id,
                   n.instance_id AS instanceId,
                   i.instance_type AS instanceType,
                   n.node_key AS nodeKey,
                   n.node_name AS nodeName,
                   n.from_status AS fromStatus,
                   n.to_status AS toStatus,
                   n.node_status AS nodeStatus,
                   n.operator_id AS operatorId,
                   n.operator_name AS operatorName,
                   n.opinion,
                   DATE_FORMAT(n.operated_time, '%Y-%m-%d %H:%i:%s') AS operatedTime
            FROM flow_node_instance n
            JOIN flow_instance i ON i.id = n.instance_id AND i.deleted = 0
            WHERE i.biz_id = #{bizId} AND n.deleted = 0
            ORDER BY n.id
            """)
    List<Map<String, Object>> selectNodesByTaskId(@Param("bizId") Long bizId);
}
