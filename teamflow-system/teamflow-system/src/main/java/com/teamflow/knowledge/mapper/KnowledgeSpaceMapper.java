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
 * M07 知识空间表（{@code knowledge_space}）访问层。
 */
@Mapper
public interface KnowledgeSpaceMapper {

    @Select("""
            <script>
            SELECT s.id,
                   s.space_name AS spaceName,
                   s.space_code AS spaceCode,
                   s.space_desc AS spaceDesc,
                   s.visibility,
                   s.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   s.status,
                   s.sort,
                   s.remark,
                   DATE_FORMAT(s.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(s.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM knowledge_node n WHERE n.space_id = s.id AND n.deleted = 0) AS nodeCount,
                   (SELECT COUNT(*) FROM knowledge_page p WHERE p.space_id = s.id AND p.deleted = 0) AS pageCount
            FROM knowledge_space s
            LEFT JOIN sys_user u ON u.id = s.owner_id
            WHERE s.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = ''
                   OR s.space_name LIKE CONCAT('%', #{keyword}, '%')
                   OR s.space_code LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR s.status = #{status})
            ORDER BY s.sort, s.id
            </script>
            """)
    List<Map<String, Object>> selectSpaces(@Param("keyword") String keyword, @Param("status") String status);

    @Select("""
            SELECT s.id,
                   s.space_name AS spaceName,
                   s.space_code AS spaceCode,
                   s.space_desc AS spaceDesc,
                   s.visibility,
                   s.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   s.status,
                   s.sort,
                   s.remark,
                   DATE_FORMAT(s.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(s.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM knowledge_node n WHERE n.space_id = s.id AND n.deleted = 0) AS nodeCount,
                   (SELECT COUNT(*) FROM knowledge_page p WHERE p.space_id = s.id AND p.deleted = 0) AS pageCount
            FROM knowledge_space s
            LEFT JOIN sys_user u ON u.id = s.owner_id
            WHERE s.id = #{id} AND s.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectSpaceById(@Param("id") Long id);

    @Select("""
            SELECT id, space_name AS spaceName, space_code AS spaceCode, space_desc AS spaceDesc,
                   visibility, owner_id AS ownerId, status
            FROM knowledge_space
            WHERE space_code = #{spaceCode} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectSpaceByCode(@Param("spaceCode") String spaceCode);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_space
            WHERE space_code = #{spaceCode}
              AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countSpaceCode(@Param("spaceCode") String spaceCode, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM knowledge_node WHERE space_id = #{spaceId} AND deleted = 0")
    long countNodes(@Param("spaceId") Long spaceId);

    @Select("SELECT COUNT(*) FROM knowledge_page WHERE space_id = #{spaceId} AND deleted = 0")
    long countPages(@Param("spaceId") Long spaceId);

    @Insert("""
            INSERT INTO knowledge_space (space_name, space_code, space_desc, visibility, owner_id, status, sort,
                                         remark, create_by, update_by)
            VALUES (#{row.spaceName}, #{row.spaceCode}, #{row.spaceDesc}, #{row.visibility}, #{row.ownerId},
                    #{row.status}, #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertSpace(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_space
            SET space_name = #{row.spaceName},
                space_desc = #{row.spaceDesc},
                visibility = #{row.visibility},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateSpace(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_space
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteSpace(@Param("id") Long id, @Param("operator") String operator);
}
