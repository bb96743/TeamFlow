package com.teamflow.perf.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M05 评分与归集明细表（{@code perf_score}）访问层。
 *
 * <p><b>两种写入语义（R3/R4）</b>：</p>
 * <ul>
 *   <li>{@code rater_type='system'}：可归集自动值，**同一 (周期, 员工, 指标项) 只保留 version=1 一行**，
 *       重算时覆盖更新 → 天然幂等（覆盖而非累加），并保留 not_applicable 标记；</li>
 *   <li>{@code rater_type='self'/'manager'}：人工评分，**只追加**（version+1、previous_score 记录前值），
 *       满足 R4「每次保存留痕：评分人/时间/前后值」。</li>
 * </ul>
 */
@Mapper
public interface PerfScoreMapper {

    /** 当前版本视图：每个 (cycle, employee, item, raterType) 取 MAX(version) 一行。 */
    String LATEST_VIEW = """
            SELECT s.id,
                   s.cycle_id AS cycleId,
                   s.employee_id AS employeeId,
                   s.template_item_id AS templateItemId,
                   s.item_code AS itemCode,
                   s.item_name AS itemName,
                   s.item_category AS itemCategory,
                   s.weight,
                   s.rater_type AS raterType,
                   s.rater_id AS raterId,
                   s.rater_name AS raterName,
                   s.auto_score AS autoScore,
                   s.score,
                   s.previous_score AS previousScore,
                   s.version,
                   s.comment,
                   s.not_applicable AS notApplicable,
                   s.na_reason AS naReason,
                   s.na_marked_by AS naMarkedBy,
                   DATE_FORMAT(s.na_marked_time, '%Y-%m-%d %H:%i:%s') AS naMarkedTime,
                   DATE_FORMAT(s.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM perf_score s
            JOIN (
                SELECT cycle_id, employee_id, template_item_id, rater_type, MAX(version) AS max_version
                FROM perf_score
                WHERE cycle_id = #{cycleId} AND deleted = 0
                GROUP BY cycle_id, employee_id, template_item_id, rater_type
            ) latest ON latest.cycle_id = s.cycle_id
                    AND latest.employee_id = s.employee_id
                    AND latest.template_item_id = s.template_item_id
                    AND latest.rater_type = s.rater_type
                    AND latest.max_version = s.version
            WHERE s.cycle_id = #{cycleId}
              AND s.deleted = 0
            """;

    /** 写一条评分历史行（self/manager 只追加；system 行由 updateSystemRow 维护）。 */
    @Insert("""
            INSERT INTO perf_score (cycle_id, employee_id, template_item_id, item_code, item_name, item_category, weight,
                                    rater_type, rater_id, rater_name, auto_score, score, previous_score, version, comment,
                                    not_applicable, na_reason, na_marked_by, na_marked_time, create_by, update_by)
            VALUES (#{row.cycleId}, #{row.employeeId}, #{row.templateItemId}, #{row.itemCode}, #{row.itemName},
                    #{row.itemCategory}, #{row.weight}, #{row.raterType}, #{row.raterId}, #{row.raterName},
                    #{row.autoScore}, #{row.score}, #{row.previousScore}, #{row.version}, #{row.comment},
                    #{row.notApplicable}, #{row.naReason}, #{row.naMarkedBy}, #{row.naMarkedTime},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertScore(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    /** 重算覆盖 system 行（保持 version=1、值覆盖；not_applicable 标记不在此 SQL 内改动）。 */
    @Update("""
            UPDATE perf_score
            SET item_code = #{row.itemCode},
                item_name = #{row.itemName},
                item_category = #{row.itemCategory},
                weight = #{row.weight},
                auto_score = #{row.autoScore},
                score = #{row.score},
                previous_score = #{row.previousScore},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateSystemRow(@Param("id") Long id, @Param("row") Map<String, Object> row,
                        @Param("operator") String operator);

    /** 标记/取消 not_applicable（D-06.5）：只改 system 行的标记字段，不动分值。 */
    @Update("""
            UPDATE perf_score
            SET not_applicable = #{notApplicable},
                na_reason = #{naReason},
                na_marked_by = #{naMarkedBy},
                na_marked_time = #{naMarkedTime},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateSystemNotApplicable(
            @Param("id") Long id,
            @Param("notApplicable") boolean notApplicable,
            @Param("naReason") String naReason,
            @Param("naMarkedBy") String naMarkedBy,
            @Param("naMarkedTime") java.time.LocalDateTime naMarkedTime,
            @Param("operator") String operator
    );

    /** 某 (周期, 员工, 指标项) 的 system 行（不存在返回 null）。 */
    @Select("""
            SELECT id,
                   cycle_id AS cycleId,
                   employee_id AS employeeId,
                   template_item_id AS templateItemId,
                   item_code AS itemCode,
                   item_name AS itemName,
                   item_category AS itemCategory,
                   weight,
                   auto_score AS autoScore,
                   score,
                   not_applicable AS notApplicable,
                   na_reason AS naReason,
                   na_marked_by AS naMarkedBy
            FROM perf_score
            WHERE cycle_id = #{cycleId} AND employee_id = #{employeeId}
              AND template_item_id = #{templateItemId} AND rater_type = 'system' AND deleted = 0
            ORDER BY version DESC
            LIMIT 1
            """)
    Map<String, Object> selectSystemRow(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId,
            @Param("templateItemId") Long templateItemId
    );

    @Select("""
            SELECT COALESCE(MAX(version), 0)
            FROM perf_score
            WHERE cycle_id = #{cycleId} AND employee_id = #{employeeId}
              AND template_item_id = #{templateItemId} AND rater_type = #{raterType} AND deleted = 0
            """)
    int selectMaxVersion(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId,
            @Param("templateItemId") Long templateItemId,
            @Param("raterType") String raterType
    );

    /** 某 (周期, 员工, 指标项, 评分人类型) 的当前版本行（不存在返回 null）。 */
    @Select(LATEST_VIEW + """
             AND s.employee_id = #{employeeId}
               AND s.template_item_id = #{templateItemId}
               AND s.rater_type = #{raterType}
            LIMIT 1
            """)
    Map<String, Object> selectLatestRow(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId,
            @Param("templateItemId") Long templateItemId,
            @Param("raterType") String raterType
    );

    /** 某员工在某周期的全部"当前版本"评分行（system + self + manager）。 */
    @Select(LATEST_VIEW + " AND s.employee_id = #{employeeId} ORDER BY s.template_item_id, s.rater_type")
    List<Map<String, Object>> selectLatestRows(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId
    );

    /** 某周期全部员工的"当前版本"评分行（归档时批量计算用）。 */
    @Select(LATEST_VIEW + " ORDER BY s.employee_id, s.template_item_id, s.rater_type")
    List<Map<String, Object>> selectLatestRowsByCycle(@Param("cycleId") Long cycleId);

    /** 已产生自评/上级评的员工（归档范围：有评分记录的员工）。 */
    @Select("""
            SELECT DISTINCT employee_id
            FROM perf_score
            WHERE cycle_id = #{cycleId} AND deleted = 0 AND rater_type IN ('self', 'manager')
            ORDER BY employee_id
            """)
    List<Long> selectEmployeesWithManualScores(@Param("cycleId") Long cycleId);

    /** 评分明细分页计数（数据范围：本人、或本人部门下的员工）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM perf_score s
            JOIN (
                SELECT cycle_id, employee_id, template_item_id, rater_type, MAX(version) AS max_version
                FROM perf_score
                WHERE cycle_id = #{cycleId} AND deleted = 0
                GROUP BY cycle_id, employee_id, template_item_id, rater_type
            ) latest ON latest.cycle_id = s.cycle_id AND latest.employee_id = s.employee_id
                    AND latest.template_item_id = s.template_item_id
                    AND latest.rater_type = s.rater_type AND latest.max_version = s.version
            WHERE s.cycle_id = #{cycleId} AND s.deleted = 0
              AND (#{employeeId} IS NULL OR s.employee_id = #{employeeId})
              AND (
                  #{scope.allScope} = TRUE
                  OR s.employee_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR s.employee_id IN (
                      SELECT u.id FROM sys_user u WHERE u.deleted = 0 AND u.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            </script>
            """)
    long countScores(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId,
            @Param("scope") DataScopeCriteria scope
    );

    /** 评分明细分页（当前版本行，含被评人姓名）。 */
    @Select("""
            <script>
            SELECT v.id, v.cycleId, v.employeeId, v.templateItemId, v.itemCode, v.itemName, v.itemCategory, v.weight,
                   v.raterType, v.raterId, v.raterName, v.autoScore, v.score, v.previousScore, v.version, v.comment,
                   v.notApplicable, v.naReason, v.naMarkedBy, v.naMarkedTime, v.createTime,
                   u.nickname AS employeeName
            FROM (
            """ + LATEST_VIEW + """
            ) v
            LEFT JOIN sys_user u ON u.id = v.employeeId
            WHERE (#{employeeId} IS NULL OR v.employeeId = #{employeeId})
              AND (
                  #{scope.allScope} = TRUE
                  OR v.employeeId = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR v.employeeId IN (
                      SELECT su.id FROM sys_user su WHERE su.deleted = 0 AND su.dept_id IN
                      <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                          #{scopeDeptId}
                      </foreach>
                  )
                  </if>
              )
            ORDER BY v.employeeId, v.templateItemId, v.raterType
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectScores(
            @Param("cycleId") Long cycleId,
            @Param("employeeId") Long employeeId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    /** 模板是否已被任何评分/归集数据引用（模板结构变更的护栏）。 */
    @Select("""
            SELECT COUNT(*)
            FROM perf_score s
            JOIN perf_template_item i ON i.id = s.template_item_id
            WHERE i.template_id = #{templateId} AND s.deleted = 0
            """)
    long countScoresByTemplate(@Param("templateId") Long templateId);

    /** 某员工某周期 system 行数（幂等验证：应等于可归集指标项数，重算不新增）。 */
    @Select("""
            SELECT COUNT(*) FROM perf_score
            WHERE cycle_id = #{cycleId} AND employee_id = #{employeeId} AND rater_type = 'system' AND deleted = 0
            """)
    int countSystemRows(@Param("cycleId") Long cycleId, @Param("employeeId") Long employeeId);
}
