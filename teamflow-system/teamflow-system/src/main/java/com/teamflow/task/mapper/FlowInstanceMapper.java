package com.teamflow.task.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
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
 * M03 流程实例表（{@code flow_instance}）访问层。
 *
 * <p>一个任务有一条持续进行的状态流转实例（{@code instance_type='status'}）；审批实例
 * （{@code instance_type='approval'}）按次创建。列表按任务数据范围过滤（关联 {@code task_task}）。</p>
 */
@Mapper
public interface FlowInstanceMapper {

    String COLUMNS = """
            SELECT i.id,
                   i.definition_id AS definitionId,
                   i.def_code AS defCode,
                   i.instance_type AS instanceType,
                   i.biz_id AS bizId,
                   i.biz_title AS bizTitle,
                   i.target_status AS targetStatus,
                   i.status,
                   i.current_node AS currentNode,
                   i.start_by AS startBy,
                   su.nickname AS startByName,
                   DATE_FORMAT(i.start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(i.end_time, '%Y-%m-%d %H:%i') AS endTime,
                   i.remark
            FROM flow_instance i
            LEFT JOIN sys_user su ON su.id = i.start_by
            LEFT JOIN task_task t ON t.id = i.biz_id
            """;

    String SCOPE_FILTER = """
              AND (
                  #{scope.allScope} = TRUE
                  OR t.assignee_id = #{scope.userId}
                  OR t.creator_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR t.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            """;

    @Insert("""
            INSERT INTO flow_instance (definition_id, def_code, instance_type, biz_id, biz_title, target_status,
                                       status, current_node, start_by, start_time, remark, create_by, update_by)
            VALUES (#{row.definitionId}, #{row.defCode}, #{row.instanceType}, #{row.bizId}, #{row.bizTitle},
                    #{row.targetStatus}, #{row.status}, #{row.currentNode}, #{row.startBy}, #{row.startTime},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertInstance(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, definition_id AS definitionId, def_code AS defCode, instance_type AS instanceType,
                   biz_id AS bizId, biz_title AS bizTitle, target_status AS targetStatus, status,
                   current_node AS currentNode, start_by AS startBy, start_time AS startTime, end_time AS endTime
            FROM flow_instance
            WHERE biz_id = #{bizId} AND instance_type = 'status' AND status = 'running' AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Map<String, Object> selectRunningStatusInstance(@Param("bizId") Long bizId);

    @Select("""
            """ + COLUMNS + """
            WHERE i.id = #{id} AND i.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectInstanceById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT COUNT(*) FROM flow_instance i
            LEFT JOIN task_task t ON t.id = i.biz_id
            WHERE i.deleted = 0
              <if test="instanceType != null and instanceType != ''">
              AND i.instance_type = #{instanceType}
              </if>
              <if test="status != null and status != ''">
              AND i.status = #{status}
              </if>
              <if test="bizId != null">
              AND i.biz_id = #{bizId}
              </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countInstances(
            @Param("instanceType") String instanceType,
            @Param("status") String status,
            @Param("bizId") Long bizId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE i.deleted = 0
              <if test="instanceType != null and instanceType != ''">
              AND i.instance_type = #{instanceType}
              </if>
              <if test="status != null and status != ''">
              AND i.status = #{status}
              </if>
              <if test="bizId != null">
              AND i.biz_id = #{bizId}
              </if>
            """ + SCOPE_FILTER + """
            ORDER BY i.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectInstances(
            @Param("instanceType") String instanceType,
            @Param("status") String status,
            @Param("bizId") Long bizId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Update("""
            UPDATE flow_instance
            SET status = #{status},
                current_node = #{currentNode},
                end_time = #{endTime},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateInstance(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("currentNode") String currentNode,
            @Param("endTime") LocalDateTime endTime,
            @Param("operator") String operator
    );
}
