package com.teamflow.perf.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M05 绩效申诉表（{@code perf_appeal}）访问层。
 *
 * <p>R4/R5：周期锁定后任何修改都必须走申诉流程，申诉记录只追加、处理结果留痕
 * （处理人/意见/时间、调整前后分值）。</p>
 */
@Mapper
public interface PerfAppealMapper {

    String COLUMNS = """
            SELECT a.id,
                   a.cycle_id AS cycleId,
                   c.cycle_code AS cycleCode,
                   c.cycle_name AS cycleName,
                   a.result_id AS resultId,
                   a.employee_id AS employeeId,
                   u.nickname AS employeeName,
                   a.appeal_reason AS appealReason,
                   a.appeal_status AS appealStatus,
                   a.before_score AS beforeScore,
                   a.after_score AS afterScore,
                   a.handler_id AS handlerId,
                   a.handler_name AS handlerName,
                   a.handle_comment AS handleComment,
                   DATE_FORMAT(a.handle_time, '%Y-%m-%d %H:%i:%s') AS handleTime,
                   a.applied,
                   DATE_FORMAT(a.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM perf_appeal a
            JOIN perf_cycle c ON c.id = a.cycle_id AND c.deleted = 0
            LEFT JOIN sys_user u ON u.id = a.employee_id
            """;

    @Insert("""
            INSERT INTO perf_appeal (cycle_id, result_id, employee_id, appeal_reason, appeal_status, before_score,
                                     create_by, update_by)
            VALUES (#{row.cycleId}, #{row.resultId}, #{row.employeeId}, #{row.appealReason}, 'pending',
                    #{row.beforeScore}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertAppeal(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select(COLUMNS + " WHERE a.id = #{id} AND a.deleted = 0 LIMIT 1")
    Map<String, Object> selectAppealById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM perf_appeal
            WHERE result_id = #{resultId} AND appeal_status = 'pending' AND deleted = 0
            """)
    long countPendingByResult(@Param("resultId") Long resultId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM perf_appeal a
            WHERE a.deleted = 0
              AND (#{cycleId} IS NULL OR a.cycle_id = #{cycleId})
              AND (#{status} IS NULL OR #{status} = '' OR a.appeal_status = #{status})
              AND (
                  #{scope.allScope} = TRUE
                  OR a.employee_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR a.employee_id IN (
                      SELECT su.id FROM sys_user su WHERE su.deleted = 0 AND su.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            </script>
            """)
    long countAppeals(
            @Param("cycleId") Long cycleId,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE a.deleted = 0
              AND (#{cycleId} IS NULL OR a.cycle_id = #{cycleId})
              AND (#{status} IS NULL OR #{status} = '' OR a.appeal_status = #{status})
              AND (
                  #{scope.allScope} = TRUE
                  OR a.employee_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR a.employee_id IN (
                      SELECT su.id FROM sys_user su WHERE su.deleted = 0 AND su.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            ORDER BY a.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectAppeals(
            @Param("cycleId") Long cycleId,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Update("""
            UPDATE perf_appeal
            SET appeal_status = #{appealStatus},
                after_score = #{afterScore},
                handler_id = #{handlerId},
                handler_name = #{handlerName},
                handle_comment = #{handleComment},
                handle_time = #{handleTime},
                applied = #{applied},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0 AND appeal_status = 'pending'
            """)
    int updateHandle(
            @Param("id") Long id,
            @Param("appealStatus") String appealStatus,
            @Param("afterScore") BigDecimal afterScore,
            @Param("handlerId") Long handlerId,
            @Param("handlerName") String handlerName,
            @Param("handleComment") String handleComment,
            @Param("handleTime") LocalDateTime handleTime,
            @Param("applied") int applied,
            @Param("operator") String operator
    );
}
