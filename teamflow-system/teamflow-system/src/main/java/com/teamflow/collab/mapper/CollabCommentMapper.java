package com.teamflow.collab.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M09 统一评论表访问层（**全站唯一评论存储**，按业务类型 + 业务单据多态挂载）。
 *
 * <p>本 Mapper 只操作本模块评论表；不读取任何业务模块的数据表（L0 §4.3、D-2026-10-25-02）。</p>
 */
@Mapper
public interface CollabCommentMapper {

    @Insert("""
            INSERT INTO collab_comment (biz_type, biz_id, parent_id, content, author_id, mentioned_user_ids,
                                        create_by, update_by)
            VALUES (#{row.bizType}, #{row.bizId}, #{row.parentId}, #{row.content}, #{row.authorId},
                    #{row.mentionedUserIds}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertComment(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT c.id,
                   c.biz_type AS bizType,
                   c.biz_id AS bizId,
                   c.parent_id AS parentId,
                   c.content,
                   c.author_id AS authorId,
                   u.nickname AS authorName,
                   u.username AS authorUsername,
                   c.mentioned_user_ids AS mentionedUserIds,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM collab_comment c
            LEFT JOIN sys_user u ON u.id = c.author_id
            WHERE c.biz_type = #{bizType} AND c.biz_id = #{bizId} AND c.deleted = 0
            ORDER BY c.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Map<String, Object>> selectComments(
            @Param("bizType") String bizType,
            @Param("bizId") Long bizId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*) FROM collab_comment
            WHERE biz_type = #{bizType} AND biz_id = #{bizId} AND deleted = 0
            """)
    long countComments(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    @Select("""
            SELECT id, biz_type AS bizType, biz_id AS bizId, parent_id AS parentId, content,
                   author_id AS authorId, mentioned_user_ids AS mentionedUserIds
            FROM collab_comment
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectCommentById(@Param("id") Long id);

    @Update("""
            UPDATE collab_comment
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteComment(@Param("id") Long id, @Param("operator") String operator);

    /** 统计同名评论表的数量（供自检/排障：全站只允许一张评论表）。 */
    @Select("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'
            """)
    int countCommentTables();
}
