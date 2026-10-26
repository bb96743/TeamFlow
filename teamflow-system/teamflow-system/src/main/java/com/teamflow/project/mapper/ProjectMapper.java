package com.teamflow.project.mapper;

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
 * M14 项目主表（{@code proj_project}）访问层。
 *
 * <p>列表/详情按数据范围过滤：命中条件为「本人是项目负责人、或项目归属部门在数据范围内」。
 * 内部写操作前用 {@link #selectProjectById(Long)} 校验存在性（不受数据范围限制）。</p>
 */
@Mapper
public interface ProjectMapper {

    String COLUMNS = """
            SELECT p.id,
                   p.project_code AS projectCode,
                   p.project_name AS projectName,
                   p.project_desc AS projectDesc,
                   p.objective_id AS objectiveId,
                   p.no_objective_reason AS noObjectiveReason,
                   p.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   p.dept_id AS deptId,
                   d.dept_name AS deptName,
                   DATE_FORMAT(p.start_date, '%Y-%m-%d') AS startDate,
                   DATE_FORMAT(p.end_date, '%Y-%m-%d') AS endDate,
                   p.status,
                   p.priority,
                   p.remark,
                   DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM proj_milestone m WHERE m.project_id = p.id AND m.deleted = 0) AS milestoneCount,
                   (SELECT COUNT(*) FROM proj_milestone m WHERE m.project_id = p.id AND m.deleted = 0
                     AND m.status = 'completed') AS completedMilestoneCount,
                   (SELECT COUNT(*) FROM proj_member mm WHERE mm.project_id = p.id AND mm.deleted = 0
                     AND mm.leave_time IS NULL) AS memberCount
            FROM proj_project p
            LEFT JOIN sys_user u ON u.id = p.owner_id
            LEFT JOIN sys_dept d ON d.id = p.dept_id
            """;

    String SCOPE_FILTER = """
              AND (
                  #{scope.allScope} = TRUE
                  OR p.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR p.dept_id IN
                  <foreach collection="scope.deptIds" item="deptId" open="(" separator="," close=")">
                      #{deptId}
                  </foreach>
                  </if>
              )
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM proj_project p
            WHERE p.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (p.project_code LIKE CONCAT('%', #{keyword}, '%') OR p.project_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND p.status = #{status}
              </if>
              <if test="deptId != null">
              AND p.dept_id = #{deptId}
              </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countProjects(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("deptId") Long deptId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE p.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (p.project_code LIKE CONCAT('%', #{keyword}, '%') OR p.project_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND p.status = #{status}
              </if>
              <if test="deptId != null">
              AND p.dept_id = #{deptId}
              </if>
            """ + SCOPE_FILTER + """
            ORDER BY p.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectProjects(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("deptId") Long deptId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE p.deleted = 0 AND p.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectProject(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            """ + COLUMNS + """
            WHERE p.deleted = 0 AND p.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectProjectById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM proj_project
            WHERE project_code = #{projectCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countProjectCode(@Param("projectCode") String projectCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO proj_project (project_code, project_name, project_desc, objective_id, no_objective_reason,
                                      owner_id, dept_id, start_date, end_date, status, priority, remark,
                                      create_by, update_by)
            VALUES (#{row.projectCode}, #{row.projectName}, #{row.projectDesc}, #{row.objectiveId},
                    #{row.noObjectiveReason}, #{row.ownerId}, #{row.deptId}, #{row.startDate}, #{row.endDate},
                    #{row.status}, #{row.priority}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertProject(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE proj_project
            SET project_name = #{row.projectName},
                project_desc = #{row.projectDesc},
                objective_id = #{row.objectiveId},
                no_objective_reason = #{row.noObjectiveReason},
                owner_id = #{row.ownerId},
                dept_id = #{row.deptId},
                start_date = #{row.startDate},
                end_date = #{row.endDate},
                priority = #{row.priority},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateProject(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE proj_project
            SET status = #{status}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateProjectStatus(@Param("id") Long id, @Param("status") String status, @Param("operator") String operator);

    @Update("UPDATE proj_project SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteProject(@Param("id") Long id, @Param("operator") String operator);
}
