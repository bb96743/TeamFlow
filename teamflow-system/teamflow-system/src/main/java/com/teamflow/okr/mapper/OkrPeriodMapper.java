package com.teamflow.okr.mapper;

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
 * M02 目标周期表（{@code okr_period}）访问层。
 *
 * <p>所有列表/详情查询都显式接收 {@link DataScopeCriteria}：在 G-2（数据权限默认放行）修复前，
 * 漏传 scope 会静默返回全量数据，故禁止新增无 scope 的列表方法。</p>
 */
@Mapper
public interface OkrPeriodMapper {

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM okr_period p
            WHERE p.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR p.period_name LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR p.status = #{status})
              AND (
                  #{scope.allScope} = TRUE
                  OR EXISTS (
                      SELECT 1
                      FROM okr_objective so
                      WHERE so.period_id = p.id
                        AND so.deleted = 0
                        AND (so.owner_id = #{scope.userId}
                        <if test="scope.deptIds != null and scope.deptIds.size > 0">
                             OR so.dept_id IN
                             <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                                 #{scopeDeptId}
                             </foreach>
                        </if>
                        )
                  )
              )
            </script>
            """)
    long countPeriods(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            SELECT p.id,
                   p.period_name AS periodName,
                   p.period_type AS periodType,
                   p.start_date AS startDate,
                   p.end_date AS endDate,
                   p.status,
                   p.sort,
                   p.remark,
                   DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM okr_objective o WHERE o.period_id = p.id AND o.deleted = 0) AS objectiveCount
            FROM okr_period p
            WHERE p.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = '' OR p.period_name LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR p.status = #{status})
              AND (
                  #{scope.allScope} = TRUE
                  OR EXISTS (
                      SELECT 1
                      FROM okr_objective so
                      WHERE so.period_id = p.id
                        AND so.deleted = 0
                        AND (so.owner_id = #{scope.userId}
                        <if test="scope.deptIds != null and scope.deptIds.size > 0">
                             OR so.dept_id IN
                             <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                                 #{scopeDeptId}
                             </foreach>
                        </if>
                        )
                  )
              )
            ORDER BY p.start_date DESC, p.sort, p.id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectPeriods(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT p.id,
                   p.period_name AS periodName,
                   p.period_type AS periodType,
                   p.start_date AS startDate,
                   p.end_date AS endDate,
                   p.status,
                   p.sort,
                   p.remark,
                   DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM okr_objective o WHERE o.period_id = p.id AND o.deleted = 0) AS objectiveCount
            FROM okr_period p
            WHERE p.id = #{id}
              AND p.deleted = 0
              AND (
                  #{scope.allScope} = TRUE
                  OR EXISTS (
                      SELECT 1
                      FROM okr_objective so
                      WHERE so.period_id = p.id
                        AND so.deleted = 0
                        AND (so.owner_id = #{scope.userId}
                        <if test="scope.deptIds != null and scope.deptIds.size > 0">
                             OR so.dept_id IN
                             <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                                 #{scopeDeptId}
                             </foreach>
                        </if>
                        )
                  )
              )
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectPeriod(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制（仅用于写操作前确认记录是否存在与是否归档）。 */
    @Select("""
            SELECT id,
                   period_name AS periodName,
                   period_type AS periodType,
                   start_date AS startDate,
                   end_date AS endDate,
                   status
            FROM okr_period
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectPeriodById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*)
            FROM okr_period
            WHERE period_name = #{periodName}
              AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countPeriodName(@Param("periodName") String periodName, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO okr_period (period_name, period_type, start_date, end_date, status, sort, remark, create_by, update_by)
            VALUES (#{row.periodName}, #{row.periodType}, #{row.startDate}, #{row.endDate}, #{row.status},
                    #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertPeriod(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_period
            SET period_name = #{row.periodName},
                period_type = #{row.periodType},
                start_date = #{row.startDate},
                end_date = #{row.endDate},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updatePeriod(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_period
            SET status = #{status}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updatePeriodStatus(@Param("id") Long id, @Param("status") String status, @Param("operator") String operator);

    @Update("""
            UPDATE okr_period
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeletePeriod(@Param("id") Long id, @Param("operator") String operator);
}
