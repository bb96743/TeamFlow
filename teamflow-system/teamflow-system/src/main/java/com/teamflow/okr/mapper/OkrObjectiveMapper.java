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
 * M02 目标表（{@code okr_objective}）访问层。
 *
 * <p>列表/详情/对齐度检查的 SQL 一律显式带入 {@link DataScopeCriteria}；
 * 无 scope 的 {@code selectObjectiveById} 仅用于写操作前的存在性与归属校验。</p>
 */
@Mapper
public interface OkrObjectiveMapper {

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM okr_objective o
            WHERE o.deleted = 0
              AND (#{periodId} IS NULL OR o.period_id = #{periodId})
              AND (#{level} IS NULL OR #{level} = '' OR o.level = #{level})
              AND (#{ownerId} IS NULL OR o.owner_id = #{ownerId})
              AND (#{status} IS NULL OR #{status} = '' OR o.status = #{status})
              AND (#{keyword} IS NULL OR #{keyword} = '' OR o.objective_title LIKE CONCAT('%', #{keyword}, '%'))
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
            </script>
            """)
    long countObjectives(
            @Param("periodId") Long periodId,
            @Param("level") String level,
            @Param("ownerId") Long ownerId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            SELECT o.id,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   o.parent_id AS parentId,
                   o.objective_title AS objectiveTitle,
                   o.objective_desc AS objectiveDesc,
                   o.level,
                   o.dept_id AS deptId,
                   d.dept_name AS deptName,
                   o.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   o.weight,
                   o.progress,
                   o.status,
                   o.sort,
                   o.remark,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(o.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM okr_key_result k WHERE k.objective_id = o.id AND k.deleted = 0) AS krCount,
                   (SELECT COUNT(*) FROM okr_objective c WHERE c.parent_id = o.id AND c.deleted = 0) AS childCount
            FROM okr_objective o
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            LEFT JOIN sys_dept d ON d.id = o.dept_id
            LEFT JOIN sys_user u ON u.id = o.owner_id
            WHERE o.deleted = 0
              AND (#{periodId} IS NULL OR o.period_id = #{periodId})
              AND (#{level} IS NULL OR #{level} = '' OR o.level = #{level})
              AND (#{ownerId} IS NULL OR o.owner_id = #{ownerId})
              AND (#{status} IS NULL OR #{status} = '' OR o.status = #{status})
              AND (#{keyword} IS NULL OR #{keyword} = '' OR o.objective_title LIKE CONCAT('%', #{keyword}, '%'))
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
            ORDER BY p.start_date DESC, o.parent_id, o.sort, o.id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectObjectives(
            @Param("periodId") Long periodId,
            @Param("level") String level,
            @Param("ownerId") Long ownerId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT o.id,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   o.parent_id AS parentId,
                   o.objective_title AS objectiveTitle,
                   o.objective_desc AS objectiveDesc,
                   o.level,
                   o.dept_id AS deptId,
                   d.dept_name AS deptName,
                   o.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   o.weight,
                   o.progress,
                   o.status,
                   o.sort,
                   o.remark,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(o.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM okr_key_result k WHERE k.objective_id = o.id AND k.deleted = 0) AS krCount,
                   (SELECT COUNT(*) FROM okr_objective c WHERE c.parent_id = o.id AND c.deleted = 0) AS childCount
            FROM okr_objective o
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            LEFT JOIN sys_dept d ON d.id = o.dept_id
            LEFT JOIN sys_user u ON u.id = o.owner_id
            WHERE o.deleted = 0
              AND (#{periodId} IS NULL OR o.period_id = #{periodId})
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
            ORDER BY o.parent_id, o.sort, o.id
            </script>
            """)
    List<Map<String, Object>> selectObjectiveTreeRows(
            @Param("periodId") Long periodId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            SELECT o.id,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   o.parent_id AS parentId,
                   o.objective_title AS objectiveTitle,
                   o.objective_desc AS objectiveDesc,
                   o.level,
                   o.dept_id AS deptId,
                   d.dept_name AS deptName,
                   o.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   o.weight,
                   o.progress,
                   o.status,
                   o.sort,
                   o.remark,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(o.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM okr_key_result k WHERE k.objective_id = o.id AND k.deleted = 0) AS krCount,
                   (SELECT COUNT(*) FROM okr_objective c WHERE c.parent_id = o.id AND c.deleted = 0) AS childCount
            FROM okr_objective o
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            LEFT JOIN sys_dept d ON d.id = o.dept_id
            LEFT JOIN sys_user u ON u.id = o.owner_id
            WHERE o.id = #{id}
              AND o.deleted = 0
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
    Map<String, Object> selectObjective(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制（写操作前确认归属、状态与归档）。 */
    @Select("""
            SELECT id,
                   period_id AS periodId,
                   parent_id AS parentId,
                   objective_title AS objectiveTitle,
                   objective_desc AS objectiveDesc,
                   level,
                   dept_id AS deptId,
                   owner_id AS ownerId,
                   weight,
                   progress,
                   status,
                   sort,
                   remark
            FROM okr_objective
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectObjectiveById(@Param("id") Long id);

    @Select("""
            SELECT COALESCE(SUM(o.weight), 0)
            FROM okr_objective o
            WHERE o.deleted = 0
              AND o.period_id = #{periodId}
              AND o.parent_id = #{parentId}
              AND (#{excludeId} IS NULL OR o.id <> #{excludeId})
            """)
    BigDecimal selectSiblingWeight(
            @Param("periodId") Long periodId,
            @Param("parentId") Long parentId,
            @Param("excludeId") Long excludeId
    );

    @Select("""
            SELECT COUNT(*)
            FROM okr_objective o
            WHERE o.deleted = 0
              AND o.period_id = #{periodId}
              AND o.parent_id = #{parentId}
              AND o.status IN ('active', 'done')
              AND (#{excludeId} IS NULL OR o.id <> #{excludeId})
            """)
    long countActiveSiblings(
            @Param("periodId") Long periodId,
            @Param("parentId") Long parentId,
            @Param("excludeId") Long excludeId
    );

    @Select("SELECT COUNT(*) FROM okr_objective WHERE period_id = #{periodId} AND deleted = 0")
    long countObjectivesByPeriod(@Param("periodId") Long periodId);

    @Select("SELECT COUNT(*) FROM okr_objective WHERE parent_id = #{parentId} AND deleted = 0")
    long countChildObjectives(@Param("parentId") Long parentId);

    @Select("SELECT AVG(o.progress) FROM okr_objective o WHERE o.parent_id = #{parentId} AND o.deleted = 0")
    BigDecimal selectChildProgressAvg(@Param("parentId") Long parentId);

    @Select("""
            <script>
            SELECT o.id,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   o.objective_title AS objectiveTitle,
                   o.level,
                   o.status,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM okr_objective o
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            WHERE o.deleted = 0
              AND o.owner_id IS NULL
              AND (#{periodId} IS NULL OR o.period_id = #{periodId})
              AND (
                  #{scope.allScope} = TRUE
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR o.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            ORDER BY o.period_id, o.id
            </script>
            """)
    List<Map<String, Object>> selectUnownedObjectives(
            @Param("periodId") Long periodId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            SELECT o.id,
                   o.period_id AS periodId,
                   p.period_name AS periodName,
                   o.objective_title AS objectiveTitle,
                   o.level,
                   o.owner_id AS ownerId,
                   o.status,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM okr_objective o
            JOIN okr_period p ON p.id = o.period_id AND p.deleted = 0
            WHERE o.deleted = 0
              AND NOT EXISTS (SELECT 1 FROM okr_key_result k WHERE k.objective_id = o.id AND k.deleted = 0)
              AND (#{periodId} IS NULL OR o.period_id = #{periodId})
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
            ORDER BY o.period_id, o.id
            </script>
            """)
    List<Map<String, Object>> selectObjectivesWithoutKr(
            @Param("periodId") Long periodId,
            @Param("scope") DataScopeCriteria scope
    );

    @Insert("""
            INSERT INTO okr_objective (period_id, parent_id, objective_title, objective_desc, level, dept_id, owner_id,
                                       weight, progress, status, sort, remark, create_by, update_by)
            VALUES (#{row.periodId}, #{row.parentId}, #{row.objectiveTitle}, #{row.objectiveDesc}, #{row.level},
                    #{row.deptId}, #{row.ownerId}, #{row.weight}, #{row.progress}, #{row.status}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertObjective(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_objective
            SET period_id = #{row.periodId},
                parent_id = #{row.parentId},
                objective_title = #{row.objectiveTitle},
                objective_desc = #{row.objectiveDesc},
                level = #{row.level},
                dept_id = #{row.deptId},
                owner_id = #{row.ownerId},
                weight = #{row.weight},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateObjective(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_objective
            SET progress = #{progress}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateObjectiveProgress(@Param("id") Long id, @Param("progress") BigDecimal progress, @Param("operator") String operator);

    @Update("""
            UPDATE okr_objective
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteObjective(@Param("id") Long id, @Param("operator") String operator);
}
