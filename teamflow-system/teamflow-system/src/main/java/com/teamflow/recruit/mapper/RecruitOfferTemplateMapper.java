package com.teamflow.recruit.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M08 Offer 模板表（{@code recruit_offer_template}）访问层。
 */
@Mapper
public interface RecruitOfferTemplateMapper {

    @Select("""
            <script>
            SELECT id, template_code AS templateCode, template_name AS templateName, content, status, remark,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM recruit_offer_template
            WHERE deleted = 0
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY id
            </script>
            """)
    List<Map<String, Object>> selectTemplates(@Param("status") String status);

    @Select("SELECT id, template_code AS templateCode, template_name AS templateName, content, status, remark "
            + "FROM recruit_offer_template WHERE id = #{id} AND deleted = 0 LIMIT 1")
    Map<String, Object> selectTemplateById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM recruit_offer_template WHERE template_code = #{templateCode} AND deleted = 0 "
            + "AND (#{excludeId} IS NULL OR id <> #{excludeId})")
    long countTemplateCode(@Param("templateCode") String templateCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO recruit_offer_template (template_code, template_name, content, status, remark,
                                                create_by, update_by)
            VALUES (#{row.templateCode}, #{row.templateName}, #{row.content}, #{row.status}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTemplate(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_offer_template
            SET template_name = #{row.templateName},
                content = #{row.content},
                status = #{row.status},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTemplate(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);
}
