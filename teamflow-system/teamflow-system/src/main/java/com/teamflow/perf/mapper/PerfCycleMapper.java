package com.teamflow.perf.mapper;

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
 * M05 绩效周期表（{@code perf_cycle}）访问层。
 *
 * <p>状态流转的唯一合法性判定在 {@code PerfCycleStatusMachine}，本类只负责持久化。</p>
 */
@Mapper
public interface PerfCycleMapper {

    String COLUMNS = """
            SELECT c.id,
                   c.cycle_code AS cycleCode,
                   c.cycle_name AS cycleName,
                   c.cycle_type AS cycleType,
                   DATE_FORMAT(c.start_date, '%Y-%m-%d') AS startDate,
                   DATE_FORMAT(c.end_date, '%Y-%m-%d') AS endDate,
                   c.template_id AS templateId,
                   t.template_name AS templateName,
                   t.total_weight AS templateTotalWeight,
                   c.status,
                   t.status AS templateStatus,
                   DATE_FORMAT(c.collect_time, '%Y-%m-%d %H:%i:%s') AS collectTime,
                   DATE_FORMAT(c.archive_time, '%Y-%m-%d %H:%i:%s') AS archiveTime,
                   DATE_FORMAT(c.lock_time, '%Y-%m-%d %H:%i:%s') AS lockTime,
                   c.remark,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(c.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM perf_cycle c
            LEFT JOIN perf_template t ON t.id = c.template_id AND t.deleted = 0
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM perf_cycle c
            WHERE c.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (c.cycle_code LIKE CONCAT('%', #{keyword}, '%') OR c.cycle_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND c.status = #{status}
              </if>
            </script>
            """)
    long countCycles(@Param("keyword") String keyword, @Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE c.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (c.cycle_code LIKE CONCAT('%', #{keyword}, '%') OR c.cycle_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND c.status = #{status}
              </if>
            ORDER BY c.start_date DESC, c.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectCycles(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select(COLUMNS + " WHERE c.id = #{id} AND c.deleted = 0 LIMIT 1")
    Map<String, Object> selectCycleById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM perf_cycle WHERE cycle_code = #{cycleCode} AND deleted = 0 AND (#{excludeId} IS NULL OR id <> #{excludeId})")
    long countCycleCode(@Param("cycleCode") String cycleCode, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM perf_cycle WHERE template_id = #{templateId} AND deleted = 0")
    long countCyclesByTemplate(@Param("templateId") Long templateId);

    @Insert("""
            INSERT INTO perf_cycle (cycle_code, cycle_name, cycle_type, start_date, end_date, template_id, status,
                                    remark, create_by, update_by)
            VALUES (#{row.cycleCode}, #{row.cycleName}, #{row.cycleType}, #{row.startDate}, #{row.endDate},
                    #{row.templateId}, #{row.status}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertCycle(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE perf_cycle
            SET cycle_name = #{row.cycleName},
                cycle_type = #{row.cycleType},
                start_date = #{row.startDate},
                end_date = #{row.endDate},
                template_id = #{row.templateId},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateCycle(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE perf_cycle
            SET status = #{status}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateCycleStatus(@Param("id") Long id, @Param("status") String status, @Param("operator") String operator);

    @Update("""
            UPDATE perf_cycle
            SET collect_time = #{collectTime}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int markCollectTime(@Param("id") Long id, @Param("collectTime") LocalDateTime collectTime,
                        @Param("operator") String operator);

    @Update("""
            UPDATE perf_cycle
            SET status = 'archived', archive_time = #{archiveTime}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int markArchived(@Param("id") Long id, @Param("archiveTime") LocalDateTime archiveTime,
                     @Param("operator") String operator);

    @Update("""
            UPDATE perf_cycle
            SET status = 'locked', lock_time = #{lockTime}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int markLocked(@Param("id") Long id, @Param("lockTime") LocalDateTime lockTime, @Param("operator") String operator);

    @Update("UPDATE perf_cycle SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteCycle(@Param("id") Long id, @Param("operator") String operator);
}
