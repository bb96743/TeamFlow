package com.teamflow.okr.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * M02 关键结果表（{@code okr_key_result}）访问层。
 *
 * <p>列表/详情通过所属目标应用数据范围；{@code selectKeyResultById} 仅用于写操作前的归属校验。</p>
 */
@Mapper
public interface OkrKeyResultMapper {

    @Select("""
            <script>
            SELECT k.id,
                   k.objective_id AS objectiveId,
                   o.objective_title AS objectiveTitle,
                   k.kr_title AS krTitle,
                   k.kr_type AS krType,
                   k.metric_unit AS metricUnit,
                   k.target_value AS targetValue,
                   k.current_value AS currentValue,
                   k.direction,
                   k.progress,
                   k.progress_source AS progressSource,
                   k.task_link_status AS taskLinkStatus,
                   k.task_count AS taskCount,
                   k.done_task_count AS doneTaskCount,
                   k.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   k.sort,
                   k.remark,
                   DATE_FORMAT(k.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(k.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM okr_key_result k
            JOIN okr_objective o ON o.id = k.objective_id AND o.deleted = 0
            LEFT JOIN sys_user u ON u.id = k.owner_id
            WHERE k.deleted = 0
              AND k.objective_id = #{objectiveId}
              AND (
                  #{scope.allScope} = TRUE
                  OR o.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR o.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            ORDER BY k.sort, k.id
            </script>
            """)
    List<Map<String, Object>> selectKeyResults(
            @Param("objectiveId") Long objectiveId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            SELECT k.id,
                   k.objective_id AS objectiveId,
                   o.objective_title AS objectiveTitle,
                   k.kr_title AS krTitle,
                   k.kr_type AS krType,
                   k.metric_unit AS metricUnit,
                   k.target_value AS targetValue,
                   k.current_value AS currentValue,
                   k.direction,
                   k.progress,
                   k.progress_source AS progressSource,
                   k.task_link_status AS taskLinkStatus,
                   k.task_count AS taskCount,
                   k.done_task_count AS doneTaskCount,
                   k.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   k.sort,
                   k.remark,
                   DATE_FORMAT(k.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(k.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM okr_key_result k
            JOIN okr_objective o ON o.id = k.objective_id AND o.deleted = 0
            LEFT JOIN sys_user u ON u.id = k.owner_id
            WHERE k.id = #{id}
              AND k.objective_id = #{objectiveId}
              AND k.deleted = 0
              AND (
                  #{scope.allScope} = TRUE
                  OR o.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR o.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectKeyResult(
            @Param("objectiveId") Long objectiveId,
            @Param("id") Long id,
            @Param("scope") DataScopeCriteria scope
    );

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            SELECT id,
                   objective_id AS objectiveId,
                   kr_title AS krTitle,
                   kr_type AS krType,
                   metric_unit AS metricUnit,
                   target_value AS targetValue,
                   current_value AS currentValue,
                   direction,
                   progress,
                   progress_source AS progressSource,
                   task_link_status AS taskLinkStatus,
                   task_count AS taskCount,
                   done_task_count AS doneTaskCount,
                   owner_id AS ownerId
            FROM okr_key_result
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectKeyResultById(@Param("id") Long id);

    @Select("SELECT AVG(k.progress) FROM okr_key_result k WHERE k.objective_id = #{objectiveId} AND k.deleted = 0")
    BigDecimal selectProgressAvg(@Param("objectiveId") Long objectiveId);

    @Select("SELECT COUNT(*) FROM okr_key_result WHERE objective_id = #{objectiveId} AND deleted = 0")
    long countKeyResults(@Param("objectiveId") Long objectiveId);

    @Insert("""
            INSERT INTO okr_key_result (objective_id, kr_title, kr_type, metric_unit, target_value, current_value,
                                        direction, progress, progress_source, task_link_status, task_count,
                                        done_task_count, owner_id, sort, remark, create_by, update_by)
            VALUES (#{row.objectiveId}, #{row.krTitle}, #{row.krType}, #{row.metricUnit}, #{row.targetValue},
                    #{row.currentValue}, #{row.direction}, #{row.progress}, #{row.progressSource},
                    #{row.taskLinkStatus}, #{row.taskCount}, #{row.doneTaskCount}, #{row.ownerId}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertKeyResult(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_key_result
            SET kr_title = #{row.krTitle},
                kr_type = #{row.krType},
                metric_unit = #{row.metricUnit},
                target_value = #{row.targetValue},
                current_value = #{row.currentValue},
                direction = #{row.direction},
                progress = #{row.progress},
                progress_source = #{row.progressSource},
                task_link_status = #{row.taskLinkStatus},
                owner_id = #{row.ownerId},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateKeyResult(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_key_result
            SET current_value = #{currentValue},
                progress = #{progress},
                progress_source = #{progressSource},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateKeyResultProgress(
            @Param("id") Long id,
            @Param("currentValue") BigDecimal currentValue,
            @Param("progress") BigDecimal progress,
            @Param("progressSource") String progressSource,
            @Param("operator") String operator
    );

    /** 任务事件回写入口（本期不开放 HTTP，待 M03 的 G-3 领域事件接入后调用）。 */
    @Update("""
            UPDATE okr_key_result
            SET task_count = #{taskCount},
                done_task_count = #{doneTaskCount},
                task_link_status = #{taskLinkStatus},
                progress = #{progress},
                progress_source = 'task_writeback',
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTaskWriteback(
            @Param("id") Long id,
            @Param("taskCount") int taskCount,
            @Param("doneTaskCount") int doneTaskCount,
            @Param("taskLinkStatus") String taskLinkStatus,
            @Param("progress") BigDecimal progress,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE okr_key_result
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteKeyResult(@Param("id") Long id, @Param("operator") String operator);

    // ------------------------------------------------------------------
    // M05 归集取数（跨模块只走 Service：OkrKeyResultService#perfFacts 是本表唯一的对外读出口）
    // ------------------------------------------------------------------

    /**
     * 归集取数：某员工在给定日期范围内承接的 KR 及其进度（M05 绩效归集用）。
     *
     * <p>取数范围：目标所属 {@code okr_period} 与绩效周期 {@code [startDate, endDate]} 日期区间重叠；
     * 同时带出**最近一次人工校准**的量与原因（D-06.3：下钻必须展示校准量与原因）。</p>
     */
    @Select("""
            SELECT k.id AS krId,
                   k.objective_id AS objectiveId,
                   o.objective_title AS objectiveTitle,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   k.kr_title AS krTitle,
                   k.kr_type AS krType,
                   k.progress,
                   k.progress_source AS progressSource,
                   k.task_count AS taskCount,
                   k.done_task_count AS doneTaskCount,
                   (SELECT l.from_progress FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationFrom,
                   (SELECT l.to_progress FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationTo,
                   (SELECT l.reason FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationReason,
                   (SELECT l.operator_name FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationOperator,
                   (SELECT DATE_FORMAT(l.create_time, '%Y-%m-%d %H:%i:%s') FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationTime
            FROM okr_key_result k
            JOIN okr_objective o ON o.id = k.objective_id AND o.deleted = 0
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            WHERE k.deleted = 0
              AND k.owner_id = #{ownerId}
              AND p.start_date <= #{endDate}
              AND p.end_date >= #{startDate}
            ORDER BY k.objective_id, k.sort, k.id
            """)
    List<Map<String, Object>> selectPerfKrs(
            @Param("ownerId") Long ownerId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

    /**
     * KR 归集批量取数（M13 洞察聚合用，只读）：与 {@link #selectPerfKrs} 同口径，
     * 一次取回一批负责人的 KR 事实；每行额外带 {@code ownerId} 供调用方按用户分组。
     */
    @Select("""
            <script>
            SELECT k.id AS krId,
                   k.objective_id AS objectiveId,
                   o.objective_title AS objectiveTitle,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   k.kr_title AS krTitle,
                   k.kr_type AS krType,
                   k.progress,
                   k.progress_source AS progressSource,
                   k.task_count AS taskCount,
                   k.done_task_count AS doneTaskCount,
                   k.owner_id AS ownerId,
                   (SELECT l.from_progress FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationFrom,
                   (SELECT l.to_progress FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationTo,
                   (SELECT l.reason FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationReason,
                   (SELECT l.operator_name FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationOperator,
                   (SELECT DATE_FORMAT(l.create_time, '%Y-%m-%d %H:%i:%s') FROM okr_progress_log l
                     WHERE l.kr_id = k.id AND l.progress_source = 'manual' AND l.deleted = 0
                     ORDER BY l.id DESC LIMIT 1) AS calibrationTime
            FROM okr_key_result k
            JOIN okr_objective o ON o.id = k.objective_id AND o.deleted = 0
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            WHERE k.deleted = 0
              AND k.owner_id IN
              <foreach collection="ownerIds" item="ownerId" open="(" separator="," close=")">
                  #{ownerId}
              </foreach>
              AND p.start_date &lt;= #{endDate}
              AND p.end_date &gt;= #{startDate}
            ORDER BY k.objective_id, k.sort, k.id
            </script>
            """)
    List<Map<String, Object>> selectPerfKrsBatch(
            @Param("ownerIds") List<Long> ownerIds,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );
    /**
     * 行级悲观锁（L0 §9.1 D-2026-10-25-06 D-06.4）：M02 事件订阅端在重算前锁住 KR 行，
     * 使同一 KR 的并发事件消费串行化，避免 A(1/2=50%) 覆盖 B(2/2=100%) 的交错。
     */
    @Select("SELECT id FROM okr_key_result WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Long lockKeyResultById(@Param("id") Long id);
}
