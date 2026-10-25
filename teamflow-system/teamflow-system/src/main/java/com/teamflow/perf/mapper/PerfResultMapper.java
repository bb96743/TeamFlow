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
 * M05 绩效结果表（{@code perf_result}）访问层。
 *
 * <p>归档时按 (cycle_id, employee_id) 唯一键覆盖写入（重算/重复归档均幂等）；锁定后只读，
 * 修改只能经 {@code perf_appeal} 申诉通道（R4/R5）。</p>
 */
@Mapper
public interface PerfResultMapper {

    String COLUMNS = """
            SELECT r.id,
                   r.cycle_id AS cycleId,
                   c.cycle_code AS cycleCode,
                   c.cycle_name AS cycleName,
                   r.employee_id AS employeeId,
                   u.nickname AS employeeName,
                   u.username AS employeeUsername,
                   r.template_id AS templateId,
                   r.total_score AS totalScore,
                   r.self_score AS selfScore,
                   r.collect_score AS collectScore,
                   r.subjective_score AS subjectiveScore,
                   r.effective_weight AS effectiveWeight,
                   r.na_weight AS naWeight,
                   r.status,
                   DATE_FORMAT(r.archive_time, '%Y-%m-%d %H:%i:%s') AS archiveTime,
                   DATE_FORMAT(r.lock_time, '%Y-%m-%d %H:%i:%s') AS lockTime,
                   r.remark,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM perf_result r
            JOIN perf_cycle c ON c.id = r.cycle_id AND c.deleted = 0
            LEFT JOIN sys_user u ON u.id = r.employee_id
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM perf_result r
            WHERE r.deleted = 0
              AND (#{cycleId} IS NULL OR r.cycle_id = #{cycleId})
              AND (
                  #{scope.allScope} = TRUE
                  OR r.employee_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR r.employee_id IN (
                      SELECT su.id FROM sys_user su WHERE su.deleted = 0 AND su.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            </script>
            """)
    long countResults(@Param("cycleId") Long cycleId, @Param("scope") DataScopeCriteria scope);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE r.deleted = 0
              AND (#{cycleId} IS NULL OR r.cycle_id = #{cycleId})
              AND (
                  #{scope.allScope} = TRUE
                  OR r.employee_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR r.employee_id IN (
                      SELECT su.id FROM sys_user su WHERE su.deleted = 0 AND su.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            ORDER BY r.cycle_id DESC, r.total_score DESC, r.employee_id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectResults(
            @Param("cycleId") Long cycleId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select(COLUMNS + " WHERE r.id = #{id} AND r.deleted = 0 LIMIT 1")
    Map<String, Object> selectResultById(@Param("id") Long id);

    @Select(COLUMNS + " WHERE r.cycle_id = #{cycleId} AND r.employee_id = #{employeeId} AND r.deleted = 0 LIMIT 1")
    Map<String, Object> selectResultByEmployee(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId
    );

    @Select("""
            SELECT r.id,
                   r.cycle_id AS cycleId,
                   r.employee_id AS employeeId,
                   r.total_score AS totalScore,
                   r.status
            FROM perf_result r
            WHERE r.cycle_id = #{cycleId} AND r.deleted = 0
            ORDER BY r.employee_id
            """)
    List<Map<String, Object>> selectResultBriefs(@Param("cycleId") Long cycleId);

    @Insert("""
            INSERT INTO perf_result (cycle_id, employee_id, template_id, total_score, self_score, collect_score,
                                     subjective_score, effective_weight, na_weight, status, archive_time, remark,
                                     create_by, update_by)
            VALUES (#{row.cycleId}, #{row.employeeId}, #{row.templateId}, #{row.totalScore}, #{row.selfScore},
                    #{row.collectScore}, #{row.subjectiveScore}, #{row.effectiveWeight}, #{row.naWeight},
                    #{row.status}, #{row.archiveTime}, #{row.remark}, #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE
                template_id = VALUES(template_id),
                total_score = VALUES(total_score),
                self_score = VALUES(self_score),
                collect_score = VALUES(collect_score),
                subjective_score = VALUES(subjective_score),
                effective_weight = VALUES(effective_weight),
                na_weight = VALUES(na_weight),
                status = VALUES(status),
                archive_time = VALUES(archive_time),
                remark = VALUES(remark),
                update_by = VALUES(update_by)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int upsertResult(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE perf_result
            SET status = 'locked', lock_time = #{lockTime}, update_by = #{operator}
            WHERE cycle_id = #{cycleId} AND deleted = 0
            """)
    int lockResults(@Param("cycleId") Long cycleId, @Param("lockTime") LocalDateTime lockTime,
                    @Param("operator") String operator);

    /** 申诉通过后的唯一定向改分入口（R4/R5：锁定后修改只能走申诉并留痕）。 */
    @Update("""
            UPDATE perf_result
            SET total_score = #{totalScore}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateResultTotal(@Param("id") Long id, @Param("totalScore") BigDecimal totalScore,
                          @Param("operator") String operator);
}
