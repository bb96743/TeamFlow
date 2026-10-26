package com.teamflow.meeting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M06 会议模板表（{@code meeting_template}）访问层。
 */
@Mapper
public interface MeetingTemplateMapper {

    @Select("""
            <script>
            SELECT t.id,
                   t.template_code AS templateCode,
                   t.template_name AS templateName,
                   t.template_type AS templateType,
                   t.description,
                   t.agenda_template AS agendaTemplate,
                   t.default_duration_minutes AS defaultDurationMinutes,
                   t.status,
                   t.builtin,
                   t.sort,
                   t.remark,
                   DATE_FORMAT(t.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM meeting_template t
            WHERE t.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = ''
                   OR t.template_name LIKE CONCAT('%', #{keyword}, '%')
                   OR t.template_code LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{templateType} IS NULL OR #{templateType} = '' OR t.template_type = #{templateType})
              AND (#{status} IS NULL OR #{status} = '' OR t.status = #{status})
            ORDER BY t.sort, t.id
            </script>
            """)
    List<Map<String, Object>> selectTemplates(
            @Param("keyword") String keyword,
            @Param("templateType") String templateType,
            @Param("status") String status
    );

    @Select("""
            SELECT id, template_code AS templateCode, template_name AS templateName,
                   template_type AS templateType, description, agenda_template AS agendaTemplate,
                   default_duration_minutes AS defaultDurationMinutes, status, builtin, sort, remark
            FROM meeting_template
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectTemplateById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM meeting_template
            WHERE template_code = #{templateCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countTemplateCode(@Param("templateCode") String templateCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO meeting_template (template_code, template_name, template_type, description,
                                          agenda_template, default_duration_minutes, status, sort, remark,
                                          create_by, update_by)
            VALUES (#{row.templateCode}, #{row.templateName}, #{row.templateType}, #{row.description},
                    #{row.agendaTemplate}, #{row.defaultDurationMinutes}, #{row.status}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTemplate(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_template
            SET template_name = #{row.templateName},
                template_type = #{row.templateType},
                description = #{row.description},
                agenda_template = #{row.agendaTemplate},
                default_duration_minutes = #{row.defaultDurationMinutes},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTemplate(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_template
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0 AND builtin = 0
            """)
    int softDeleteTemplate(@Param("id") Long id, @Param("operator") String operator);
}
