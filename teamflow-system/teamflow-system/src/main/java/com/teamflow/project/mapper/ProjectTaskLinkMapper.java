package com.teamflow.project.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M14 项目-任务直连关联访问层（M2-1 遗留）。
 *
 * <p>只维护 {@code project_id + task_id + 关联类型}；任务的标题/状态/时间/完成度**不落本表**，
 * 一律经任务模块 Service 实时读取（R2）。</p>
 */
@Mapper
public interface ProjectTaskLinkMapper {

    @Insert("""
            INSERT INTO proj_task_link (project_id, task_id, link_type, remark, create_by, update_by)
            VALUES (#{row.projectId}, #{row.taskId}, #{row.linkType}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertLink(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT l.id,
                   l.project_id AS projectId,
                   l.task_id AS taskId,
                   l.link_type AS linkType,
                   l.remark,
                   DATE_FORMAT(l.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM proj_task_link l
            WHERE l.project_id = #{projectId} AND l.deleted = 0
            ORDER BY l.task_id
            """)
    List<Map<String, Object>> selectByProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT id, project_id AS projectId, task_id AS taskId, link_type AS linkType, remark
            FROM proj_task_link
            WHERE id = #{id} AND project_id = #{projectId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectLink(
            @Param("projectId") Long projectId, @Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM proj_task_link
            WHERE project_id = #{projectId} AND task_id = #{taskId} AND deleted = 0
            """)
    long countLink(@Param("projectId") Long projectId, @Param("taskId") Long taskId);

    @Update("""
            UPDATE proj_task_link
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND project_id = #{projectId} AND deleted = 0
            """)
    int softDeleteLink(
            @Param("projectId") Long projectId, @Param("id") Long id, @Param("operator") String operator);

    @Update("""
            UPDATE proj_task_link
            SET deleted = 1, update_by = #{operator}
            WHERE project_id = #{projectId} AND deleted = 0
            """)
    int softDeleteByProject(@Param("projectId") Long projectId, @Param("operator") String operator);
}
