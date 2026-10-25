package com.teamflow.perf.mapper;

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
 * M05 指标模板表（{@code perf_template}）访问层。R6：权重和必须 100%。
 */
@Mapper
public interface PerfTemplateMapper {

    String COLUMNS = """
            SELECT t.id,
                   t.template_code AS templateCode,
                   t.template_name AS templateName,
                   t.description,
                   t.status,
                   t.total_weight AS totalWeight,
                   t.remark,
                   DATE_FORMAT(t.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(t.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM perf_template_item i WHERE i.template_id = t.id AND i.deleted = 0) AS itemCount
            FROM perf_template t
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM perf_template t
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (t.template_code LIKE CONCAT('%', #{keyword}, '%') OR t.template_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND t.status = #{status}
              </if>
            </script>
            """)
    long countTemplates(@Param("keyword") String keyword, @Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (t.template_code LIKE CONCAT('%', #{keyword}, '%') OR t.template_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND t.status = #{status}
              </if>
            ORDER BY t.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectTemplates(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select(COLUMNS + " WHERE t.id = #{id} AND t.deleted = 0 LIMIT 1")
    Map<String, Object> selectTemplateById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM perf_template
            WHERE template_code = #{templateCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countTemplateCode(@Param("templateCode") String templateCode, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM perf_cycle WHERE template_id = #{templateId} AND deleted = 0 AND status <> 'draft'")
    long countActiveCyclesUsingTemplate(@Param("templateId") Long templateId);

    @Insert("""
            INSERT INTO perf_template (template_code, template_name, description, status, total_weight, remark,
                                       create_by, update_by)
            VALUES (#{row.templateCode}, #{row.templateName}, #{row.description}, #{row.status}, #{row.totalWeight},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTemplate(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE perf_template
            SET template_name = #{row.templateName},
                description = #{row.description},
                status = #{row.status},
                total_weight = #{row.totalWeight},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTemplate(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("UPDATE perf_template SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteTemplate(@Param("id") Long id, @Param("operator") String operator);

    @Select("SELECT COALESCE(SUM(weight), 0) FROM perf_template_item WHERE template_id = #{templateId} AND deleted = 0")
    BigDecimal selectItemWeightSum(@Param("templateId") Long templateId);

    @Select("SELECT COUNT(*) FROM perf_template_item WHERE template_id = #{templateId} AND deleted = 0")
    long countItems(@Param("templateId") Long templateId);
}
