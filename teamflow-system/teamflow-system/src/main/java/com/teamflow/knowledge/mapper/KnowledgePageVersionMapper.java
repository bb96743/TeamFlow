package com.teamflow.knowledge.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M07 页面版本表（{@code knowledge_page_version}）访问层：只追加、不修改历史版本。
 */
@Mapper
public interface KnowledgePageVersionMapper {

    @Select("""
            SELECT v.id,
                   v.page_id AS pageId,
                   v.version_no AS versionNo,
                   v.page_title AS pageTitle,
                   v.tags,
                   v.change_summary AS changeSummary,
                   v.editor_id AS editorId,
                   v.editor_name AS editorName,
                   DATE_FORMAT(v.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM knowledge_page_version v
            WHERE v.page_id = #{pageId} AND v.deleted = 0
            ORDER BY v.version_no DESC
            """)
    List<Map<String, Object>> selectVersions(@Param("pageId") Long pageId);

    @Select("""
            SELECT v.id,
                   v.page_id AS pageId,
                   v.version_no AS versionNo,
                   v.page_title AS pageTitle,
                   v.content,
                   v.content_format AS contentFormat,
                   v.tags,
                   v.change_summary AS changeSummary,
                   v.editor_id AS editorId,
                   v.editor_name AS editorName,
                   DATE_FORMAT(v.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM knowledge_page_version v
            WHERE v.page_id = #{pageId} AND v.version_no = #{versionNo} AND v.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectVersion(@Param("pageId") Long pageId, @Param("versionNo") Integer versionNo);

    @Insert("""
            INSERT INTO knowledge_page_version (page_id, version_no, page_title, content, content_format, tags,
                                                change_summary, editor_id, editor_name, create_by, update_by)
            VALUES (#{row.pageId}, #{row.versionNo}, #{row.pageTitle}, #{row.content}, #{row.contentFormat},
                    #{row.tags}, #{row.changeSummary}, #{row.editorId}, #{row.editorName},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertVersion(@Param("row") Map<String, Object> row, @Param("operator") String operator);
}
