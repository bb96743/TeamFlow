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
 * M07 知识目录表（{@code knowledge_node}）访问层。
 */
@Mapper
public interface KnowledgeNodeMapper {

    @Select("""
            SELECT n.id,
                   n.space_id AS spaceId,
                   n.parent_id AS parentId,
                   n.node_name AS nodeName,
                   n.node_desc AS nodeDesc,
                   n.visibility,
                   n.sort,
                   n.remark,
                   DATE_FORMAT(n.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(n.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM knowledge_node n
            WHERE n.space_id = #{spaceId} AND n.deleted = 0
            ORDER BY n.parent_id, n.sort, n.id
            """)
    List<Map<String, Object>> selectNodesBySpace(@Param("spaceId") Long spaceId);

    @Select("""
            SELECT id, space_id AS spaceId, parent_id AS parentId, node_name AS nodeName, node_desc AS nodeDesc,
                   visibility, sort, remark
            FROM knowledge_node
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectNodeById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM knowledge_node WHERE parent_id = #{parentId} AND deleted = 0")
    long countChildren(@Param("parentId") Long parentId);

    @Select("SELECT COUNT(*) FROM knowledge_page WHERE node_id = #{nodeId} AND deleted = 0")
    long countPages(@Param("nodeId") Long nodeId);

    /** 按 ID 集合批量取目录（授权链计算用，避免逐级查询）。 */
    @Select("""
            <script>
            SELECT id, space_id AS spaceId, parent_id AS parentId, node_name AS nodeName,
                   visibility, sort
            FROM knowledge_node
            WHERE deleted = 0 AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectNodesByIds(@Param("ids") List<Long> ids);

    @Insert("""
            INSERT INTO knowledge_node (space_id, parent_id, node_name, node_desc, visibility, sort, remark,
                                        create_by, update_by)
            VALUES (#{row.spaceId}, #{row.parentId}, #{row.nodeName}, #{row.nodeDesc}, #{row.visibility},
                    #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertNode(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_node
            SET node_name = #{row.nodeName},
                node_desc = #{row.nodeDesc},
                visibility = #{row.visibility},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateNode(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_node
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteNode(@Param("id") Long id, @Param("operator") String operator);
}
