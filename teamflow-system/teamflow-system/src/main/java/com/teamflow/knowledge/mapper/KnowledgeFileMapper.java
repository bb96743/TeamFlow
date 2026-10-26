package com.teamflow.knowledge.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M07 附件引用表（{@code knowledge_file}）访问层。
 *
 * <p>只保存平台文件 ID 与业务标签；文件本体与引用计数由平台统一上传服务维护。</p>
 */
@Mapper
public interface KnowledgeFileMapper {

    @Select("""
            <script>
            SELECT f.id,
                   f.space_id AS spaceId,
                   f.page_id AS pageId,
                   f.file_id AS fileId,
                   f.file_title AS fileTitle,
                   f.category,
                   f.sort,
                   f.remark,
                   f.create_by AS createBy,
                   DATE_FORMAT(f.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM knowledge_file f
            WHERE f.deleted = 0
              AND (#{spaceId} IS NULL OR f.space_id = #{spaceId})
              AND (#{pageId} IS NULL OR f.page_id = #{pageId})
            ORDER BY f.sort, f.id
            </script>
            """)
    List<Map<String, Object>> selectFiles(@Param("spaceId") Long spaceId, @Param("pageId") Long pageId);

    @Select("""
            SELECT id, space_id AS spaceId, page_id AS pageId, file_id AS fileId, file_title AS fileTitle,
                   category, sort, remark, create_by AS createBy
            FROM knowledge_file
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectFileById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM knowledge_file WHERE page_id = #{pageId} AND deleted = 0")
    long countByPage(@Param("pageId") Long pageId);

    @Insert("""
            INSERT INTO knowledge_file (space_id, page_id, file_id, file_title, category, sort, remark,
                                        create_by, update_by)
            VALUES (#{row.spaceId}, #{row.pageId}, #{row.fileId}, #{row.fileTitle}, #{row.category},
                    #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertFile(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_file
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteFile(@Param("id") Long id, @Param("operator") String operator);
}
