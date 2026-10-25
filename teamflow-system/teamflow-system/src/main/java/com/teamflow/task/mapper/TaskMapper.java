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
 * M03 任务表（{@code task_task}）访问层。
 *
 * <p>列表/详情按数据范围过滤（G-2 默认拒绝）：命中条件为「本人是责任人、本人是创建人、或任务归属部门在数据范围内」。
 * 内部写操作前的归属校验走 {@link #selectTaskById(Long)}（不受数据范围限制，与 M02 的写法一致）。</p>
 */
@Mapper
public interface TaskMapper {

    String COLUMNS = """
            SELECT t.id,
                   t.task_title AS taskTitle,
                   t.task_desc AS taskDesc,
                   t.task_type AS taskType,
                   t.kr_id AS krId,
                   t.priority,
                   t.status,
                   t.assignee_id AS assigneeId,
                   au.nickname AS assigneeName,
                   t.creator_id AS creatorId,
                   cu.nickname AS creatorName,
                   t.dept_id AS deptId,
                   DATE_FORMAT(t.start_date, '%Y-%m-%d') AS startDate,
                   DATE_FORMAT(t.due_date, '%Y-%m-%d') AS dueDate,
                   DATE_FORMAT(t.done_time, '%Y-%m-%d %H:%i') AS doneTime,
                   t.sub_task_count AS subTaskCount,
                   t.done_sub_task_count AS doneSubTaskCount,
                   (SELECT r.related_task_id FROM task_relation r
                     WHERE r.task_id = t.id AND r.relation_type = 'subtask' AND r.deleted = 0
                     LIMIT 1) AS parentTaskId,
                   t.sort,
                   t.remark,
                   DATE_FORMAT(t.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(t.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM task_task t
            LEFT JOIN sys_user au ON au.id = t.assignee_id
            LEFT JOIN sys_user cu ON cu.id = t.creator_id
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

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM task_task t
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND t.task_title LIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">
              AND t.status = #{status}
              </if>
              <if test="assigneeId != null">
              AND t.assignee_id = #{assigneeId}
              </if>
              <if test="krId != null">
              AND t.kr_id = #{krId}
              </if>
              <if test="priority != null and priority != ''">
              AND t.priority = #{priority}
              </if>
              <if test="onlyPool">
              AND t.status = 'pool' AND t.assignee_id IS NULL
              </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countTasks(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("assigneeId") Long assigneeId,
            @Param("krId") Long krId,
            @Param("priority") String priority,
            @Param("onlyPool") boolean onlyPool,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND t.task_title LIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">
              AND t.status = #{status}
              </if>
              <if test="assigneeId != null">
              AND t.assignee_id = #{assigneeId}
              </if>
              <if test="krId != null">
              AND t.kr_id = #{krId}
              </if>
              <if test="priority != null and priority != ''">
              AND t.priority = #{priority}
              </if>
              <if test="onlyPool">
              AND t.status = 'pool' AND t.assignee_id IS NULL
              </if>
            """ + SCOPE_FILTER + """
            ORDER BY t.sort, t.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectTasks(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("assigneeId") Long assigneeId,
            @Param("krId") Long krId,
            @Param("priority") String priority,
            @Param("onlyPool") boolean onlyPool,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE t.deleted = 0
              AND t.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectTask(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制（与 M02 的 {@code selectKeyResultById} 同构）。 */
    @Select("""
            """ + COLUMNS + """
            WHERE t.deleted = 0 AND t.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectTaskById(@Param("id") Long id);

    @Insert("""
            INSERT INTO task_task (task_title, task_desc, task_type, kr_id, priority, status, assignee_id,
                                   creator_id, dept_id, start_date, due_date, sub_task_count, done_sub_task_count,
                                   sort, remark, create_by, update_by)
            VALUES (#{row.taskTitle}, #{row.taskDesc}, #{row.taskType}, #{row.krId}, #{row.priority}, #{row.status},
                    #{row.assigneeId}, #{row.creatorId}, #{row.deptId}, #{row.startDate}, #{row.dueDate},
                    #{row.subTaskCount}, #{row.doneSubTaskCount}, #{row.sort}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTask(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE task_task
            SET task_title = #{row.taskTitle},
                task_desc = #{row.taskDesc},
                kr_id = #{row.krId},
                priority = #{row.priority},
                start_date = #{row.startDate},
                due_date = #{row.dueDate},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTask(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    /** 状态流转（唯一入口在 TaskStatusService，合法性由 TaskStatusMachine 判定）。 */
    @Update("""
            UPDATE task_task
            SET status = #{status},
                done_time = CASE
                                WHEN #{status} = 'done' THEN #{doneTime}
                                WHEN #{status} = 'closed' THEN done_time
                                ELSE NULL
                            END,
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTaskStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("doneTime") LocalDateTime doneTime,
            @Param("operator") String operator
    );

    /** 指派/转派：assigneeId 为 null 表示收回指派（任务退回待认领池）。 */
    @Update("""
            UPDATE task_task
            SET assignee_id = #{assigneeId},
                dept_id = #{deptId},
                status = #{status},
                done_time = CASE
                                WHEN #{status} = 'done' THEN done_time
                                WHEN #{status} = 'closed' THEN done_time
                                ELSE NULL
                            END,
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTaskAssignee(
            @Param("id") Long id,
            @Param("assigneeId") Long assigneeId,
            @Param("deptId") Long deptId,
            @Param("status") String status,
            @Param("operator") String operator
    );

    /** 子任务汇总：父任务维护直接子任务总数与已完成数（增量式，GREATEST 防止为负）。 */
    @Update("""
            UPDATE task_task
            SET sub_task_count = GREATEST(sub_task_count + #{totalDelta}, 0),
                done_sub_task_count = GREATEST(done_sub_task_count + #{doneDelta}, 0),
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int addSubTaskCount(
            @Param("id") Long parentId,
            @Param("totalDelta") int totalDelta,
            @Param("doneDelta") int doneDelta,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE task_task
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteTask(@Param("id") Long id, @Param("operator") String operator);

    // ------------------------------------------------------------------
    // M05 绩效归集取数（跨模块只走 Service：TaskPerfQueryService 是本模块唯一的对外读出口）
    // ------------------------------------------------------------------

    /**
     * 归集取数：某责任人在给定日期范围内的**顶层任务事实**（M05 绩效归集用，只读）。
     *
     * <p>顶层任务 = 不存在 {@code task_relation.relation_type='subtask'} 关系的任务（子任务不计入
     * 分子/分母，与 M02 的 KR 进度口径一致）。时间范围：{@code due_date} 落在周期内；
     * {@code due_date} 为空时按 {@code create_time} 落在周期内判定。</p>
     */
    @Select("""
            SELECT t.id AS taskId,
                   t.task_title AS taskTitle,
                   t.status,
                   t.kr_id AS krId,
                   DATE_FORMAT(t.due_date, '%Y-%m-%d') AS dueDate,
                   DATE_FORMAT(t.done_time, '%Y-%m-%d %H:%i') AS doneTime,
                   t.assignee_id AS assigneeId
            FROM task_task t
            WHERE t.deleted = 0
              AND t.assignee_id = #{assigneeId}
              AND NOT EXISTS (
                  SELECT 1 FROM task_relation r
                  WHERE r.task_id = t.id AND r.relation_type = 'subtask' AND r.deleted = 0
              )
              AND (
                  (t.due_date IS NOT NULL AND t.due_date BETWEEN #{startDate} AND #{endDate})
                  OR (t.due_date IS NULL AND DATE(t.create_time) BETWEEN #{startDate} AND #{endDate})
              )
            ORDER BY t.id
            """)
    List<Map<String, Object>> selectPerfTopLevelTasks(
            @Param("assigneeId") Long assigneeId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );
}
