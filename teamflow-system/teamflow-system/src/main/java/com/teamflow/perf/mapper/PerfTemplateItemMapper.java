package com.teamflow.perf.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M05 指标项表（{@code perf_template_item}）访问层。
 *
 * <p>可归集项（{@code item_category=collectable}）必须携带已登记指标字典的 {@code metric_code}（R2）。</p>
 */
@Mapper
public interface PerfTemplateItemMapper {

    @Select("""
            SELECT i.id,
                   i.template_id AS templateId,
                   i.item_code AS itemCode,
                   i.item_name AS itemName,
                   i.item_category AS itemCategory,
                   i.metric_code AS metricCode,
                   i.weight,
                   i.max_score AS maxScore,
                   i.score_standard AS scoreStandard,
                   i.require_comment AS requireComment,
                   i.sort,
                   i.remark
            FROM perf_template_item i
            WHERE i.template_id = #{templateId} AND i.deleted = 0
            ORDER BY i.sort, i.id
            """)
    List<Map<String, Object>> selectItems(@Param("templateId") Long templateId);

    @Select("""
            SELECT i.id,
                   i.template_id AS templateId,
                   i.item_code AS itemCode,
                   i.item_name AS itemName,
                   i.item_category AS itemCategory,
                   i.metric_code AS metricCode,
                   i.weight,
                   i.max_score AS maxScore,
                   i.score_standard AS scoreStandard,
                   i.require_comment AS requireComment,
                   i.sort,
                   i.remark
            FROM perf_template_item i
            WHERE i.id = #{id} AND i.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectItemById(@Param("id") Long id);

    @Insert("""
            INSERT INTO perf_template_item (template_id, item_code, item_name, item_category, metric_code, weight,
                                            max_score, score_standard, require_comment, sort, remark,
                                            create_by, update_by)
            VALUES (#{row.templateId}, #{row.itemCode}, #{row.itemName}, #{row.itemCategory}, #{row.metricCode},
                    #{row.weight}, #{row.maxScore}, #{row.scoreStandard}, #{row.requireComment}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertItem(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE perf_template_item
            SET deleted = 1, update_by = #{operator}
            WHERE template_id = #{templateId} AND deleted = 0
            """)
    int softDeleteItemsByTemplate(@Param("templateId") Long templateId, @Param("operator") String operator);
}
