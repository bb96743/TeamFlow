package com.teamflow.project.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * M14 里程碑表（{@code proj_milestone}）访问层。
 *
 * <p>本表只存里程碑自身属性与 {@code kr_id} 引用；完成度**不落库**，由服务层经 M03 Service 实时汇总（R3）。</p>
 */
@Mapper
public interface ProjectMilestoneMapper {

    String COLUMNS = """
            SELECT m.id,
                   m.project_id AS projectId,
                   m.milestone_name AS milestoneName,
                   m.milestone_desc AS milestoneDesc,
                   m.kr_id AS krId,
                   DATE_FORMAT(m.plan_start_date, '%Y-%m-%d') AS planStartDate,
                   DATE_FORMAT(m.plan_end_date, '%Y-%m-%d') AS planEndDate,
                   DATE_FORMAT(m.actual_start_date, '%Y-%m-%d') AS actualStartDate,
                   DATE_FORMAT(m.actual_end_date, '%Y-%m-%d') AS actualEndDate,
                   m.status,
                   m.sort,
                   m.remark,
                   DATE_FORMAT(m.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(m.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM proj_milestone m
            """;

    @Select(COLUMNS + " WHERE m.project_id = #{projectId} AND m.deleted = 0 ORDER BY m.sort, m.id")
    List<Map<String, Object>> selectByProject(@Param("projectId") Long projectId);

    @Select(COLUMNS + " WHERE m.id = #{id} AND m.deleted = 0 LIMIT 1")
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM proj_milestone WHERE project_id = #{projectId} AND deleted = 0")
    long countByProject(@Param("projectId") Long projectId);

    @Insert("""
            INSERT INTO proj_milestone (project_id, milestone_name, milestone_desc, kr_id, plan_start_date,
                                        plan_end_date, actual_start_date, actual_end_date, status, sort, remark,
                                        create_by, update_by)
            VALUES (#{row.projectId}, #{row.milestoneName}, #{row.milestoneDesc}, #{row.krId}, #{row.planStartDate},
                    #{row.planEndDate}, #{row.actualStartDate}, #{row.actualEndDate}, #{row.status}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMilestone(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE proj_milestone
            SET milestone_name = #{row.milestoneName},
                milestone_desc = #{row.milestoneDesc},
                kr_id = #{row.krId},
                plan_start_date = #{row.planStartDate},
                plan_end_date = #{row.planEndDate},
                actual_start_date = #{row.actualStartDate},
                actual_end_date = #{row.actualEndDate},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMilestone(@Param("id") Long id, @Param("row") Map<String, Object> row,
                        @Param("operator") String operator);

    @Update("""
            UPDATE proj_milestone
            SET status = #{status},
                actual_start_date = #{actualStartDate},
                actual_end_date = #{actualEndDate},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("actualStartDate") LocalDate actualStartDate,
            @Param("actualEndDate") LocalDate actualEndDate,
            @Param("operator") String operator
    );

    @Update("UPDATE proj_milestone SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteMilestone(@Param("id") Long id, @Param("operator") String operator);

    @Update("UPDATE proj_milestone SET deleted = 1, update_by = #{operator} WHERE project_id = #{projectId} AND deleted = 0")
    int softDeleteByProject(@Param("projectId") Long projectId, @Param("operator") String operator);
}
