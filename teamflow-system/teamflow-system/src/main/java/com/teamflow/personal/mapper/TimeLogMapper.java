package com.teamflow.personal.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M10 工时记录表（{@code time_log}）访问层。
 *
 * <p>R6：工时以任务为粒度，{@code task_id} 非空且在服务层强制校验；按任务汇总由
 * {@link #selectSummaryByTask} 实时聚合，不落汇总表。</p>
 */
@Mapper
public interface TimeLogMapper {

    @Insert("""
            INSERT INTO time_log (task_id, user_id, work_date, start_time, end_time, duration_minutes,
                                  description, create_by, update_by)
            VALUES (#{row.taskId}, #{row.userId}, #{row.workDate}, #{row.startTime}, #{row.endTime},
                    #{row.durationMinutes}, #{row.description}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertLog(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, task_id AS taskId, user_id AS userId,
                   DATE_FORMAT(work_date, '%Y-%m-%d') AS workDate,
                   DATE_FORMAT(start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(end_time, '%Y-%m-%d %H:%i') AS endTime,
                   duration_minutes AS durationMinutes, description,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM time_log
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT COUNT(*) FROM time_log
            WHERE user_id = #{userId} AND deleted = 0
              <if test="taskId != null"> AND task_id = #{taskId} </if>
              <if test="fromDate != null and fromDate != ''"> AND work_date &gt;= #{fromDate} </if>
              <if test="toDate != null and toDate != ''"> AND work_date &lt;= #{toDate} </if>
            </script>
            """)
    long countLogs(
            @Param("userId") Long userId,
            @Param("taskId") Long taskId,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate
    );

    @Select("""
            <script>
            SELECT id, task_id AS taskId,
                   DATE_FORMAT(work_date, '%Y-%m-%d') AS workDate,
                   DATE_FORMAT(start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(end_time, '%Y-%m-%d %H:%i') AS endTime,
                   duration_minutes AS durationMinutes, description,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM time_log
            WHERE user_id = #{userId} AND deleted = 0
              <if test="taskId != null"> AND task_id = #{taskId} </if>
              <if test="fromDate != null and fromDate != ''"> AND work_date &gt;= #{fromDate} </if>
              <if test="toDate != null and toDate != ''"> AND work_date &lt;= #{toDate} </if>
            ORDER BY work_date DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectLogs(
            @Param("userId") Long userId,
            @Param("taskId") Long taskId,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Update("""
            UPDATE time_log
            SET task_id = #{row.taskId},
                work_date = #{row.workDate},
                start_time = #{row.startTime},
                end_time = #{row.endTime},
                duration_minutes = #{row.durationMinutes},
                description = #{row.description},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateLog(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE time_log
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDelete(@Param("id") Long id, @Param("operator") String operator);

    /** 按任务汇总（R6/DoD）：返回 taskId / totalMinutes / logCount，按总时长降序。 */
    @Select("""
            <script>
            SELECT task_id AS taskId,
                   SUM(duration_minutes) AS totalMinutes,
                   COUNT(*) AS logCount
            FROM time_log
            WHERE user_id = #{userId} AND deleted = 0
              <if test="fromDate != null and fromDate != ''"> AND work_date &gt;= #{fromDate} </if>
              <if test="toDate != null and toDate != ''"> AND work_date &lt;= #{toDate} </if>
            GROUP BY task_id
            ORDER BY totalMinutes DESC, task_id
            </script>
            """)
    List<Map<String, Object>> selectSummaryByTask(
            @Param("userId") Long userId,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate
    );

    /** 效能画像：本人总工时、涉及任务数、记录数（实时聚合）。 */
    @Select("""
            SELECT COALESCE(SUM(duration_minutes), 0) AS totalMinutes,
                   COUNT(DISTINCT task_id) AS taskCount,
                   COUNT(*) AS logCount
            FROM time_log
            WHERE user_id = #{userId} AND deleted = 0
            """)
    Map<String, Object> selectStatByUser(@Param("userId") Long userId);

    /** 效能画像：工时投入 Top N 任务。 */
    @Select("""
            SELECT task_id AS taskId, SUM(duration_minutes) AS totalMinutes
            FROM time_log
            WHERE user_id = #{userId} AND deleted = 0
            GROUP BY task_id
            ORDER BY totalMinutes DESC, task_id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectTopTasks(@Param("userId") Long userId, @Param("limit") int limit);
}
