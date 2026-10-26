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
 * M07 知识页面表（{@code knowledge_page}）访问层。
 *
 * <p>列表查询只做「空间可见集合 + 业务过滤」的 SQL 过滤，页面级/目录级的授权覆盖
 * 由服务层在内存中按授权链判定（避免复杂 SQL 与 N+1 查询）。</p>
 *
 * <p>全文检索：优先 {@code MATCH ... AGAINST}（联合全文索引，ngram 分词），
 * 单字或索引不可用时由服务层回落 LIKE。检索片段由 SQL 的 {@code LOCATE + SUBSTRING}
 * 生成，避免把整篇正文读回应用层。</p>
 */
@Mapper
public interface KnowledgePageMapper {

    @Select("""
            <script>
            SELECT p.id,
                   p.space_id AS spaceId,
                   s.space_name AS spaceName,
                   p.node_id AS nodeId,
                   n.node_name AS nodeName,
                   p.page_title AS pageTitle,
                   p.page_type AS pageType,
                   p.tags,
                   p.summary,
                   p.content_format AS contentFormat,
                   p.visibility,
                   p.status,
                   p.current_version AS currentVersion,
                   p.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   p.source_module AS sourceModule,
                   p.source_biz_type AS sourceBizType,
                   p.source_biz_id AS sourceBizId,
                   p.sort,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM knowledge_page p
            JOIN knowledge_space s ON s.id = p.space_id AND s.deleted = 0
            LEFT JOIN knowledge_node n ON n.id = p.node_id AND n.deleted = 0
            LEFT JOIN sys_user u ON u.id = p.owner_id
            WHERE p.deleted = 0
              AND p.space_id IN
              <foreach collection="spaceIds" item="spaceId" open="(" separator="," close=")">
                  #{spaceId}
              </foreach>
              AND (#{nodeId} IS NULL OR p.node_id = #{nodeId})
              AND (#{pageType} IS NULL OR #{pageType} = '' OR p.page_type = #{pageType})
              AND (#{tag} IS NULL OR #{tag} = '' OR FIND_IN_SET(#{tag}, p.tags))
              AND (#{status} IS NULL OR #{status} = '' OR p.status = #{status})
              AND (#{keyword} IS NULL OR #{keyword} = ''
                   OR p.page_title LIKE CONCAT('%', #{keyword}, '%')
                   OR p.tags LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY p.sort, p.update_time DESC, p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> selectPages(
            @Param("spaceIds") List<Long> spaceIds,
            @Param("nodeId") Long nodeId,
            @Param("pageType") String pageType,
            @Param("tag") String tag,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    @Select("""
            SELECT p.id,
                   p.space_id AS spaceId,
                   s.space_name AS spaceName,
                   p.node_id AS nodeId,
                   n.node_name AS nodeName,
                   p.page_title AS pageTitle,
                   p.page_type AS pageType,
                   p.tags,
                   p.summary,
                   p.content,
                   p.content_format AS contentFormat,
                   p.visibility,
                   p.status,
                   p.current_version AS currentVersion,
                   p.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   p.source_module AS sourceModule,
                   p.source_biz_type AS sourceBizType,
                   p.source_biz_id AS sourceBizId,
                   p.sort,
                   p.remark,
                   DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM knowledge_page p
            JOIN knowledge_space s ON s.id = p.space_id AND s.deleted = 0
            LEFT JOIN knowledge_node n ON n.id = p.node_id AND n.deleted = 0
            LEFT JOIN sys_user u ON u.id = p.owner_id
            WHERE p.id = #{id} AND p.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectPageById(@Param("id") Long id);

    @Select("""
            SELECT id, space_id AS spaceId, node_id AS nodeId, page_title AS pageTitle, page_type AS pageType,
                   tags, summary, content, content_format AS contentFormat, visibility, status,
                   current_version AS currentVersion, owner_id AS ownerId,
                   source_module AS sourceModule, source_biz_type AS sourceBizType, source_biz_id AS sourceBizId,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM knowledge_page
            WHERE source_module = #{sourceModule}
              AND source_biz_type = #{sourceBizType}
              AND source_biz_id = #{sourceBizId}
              AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectPageBySource(
            @Param("sourceModule") String sourceModule,
            @Param("sourceBizType") String sourceBizType,
            @Param("sourceBizId") Long sourceBizId
    );

    @Insert("""
            INSERT INTO knowledge_page (space_id, node_id, page_title, page_type, tags, summary, content,
                                        content_format, visibility, status, current_version, owner_id,
                                        source_module, source_biz_type, source_biz_id, sort, remark,
                                        create_by, update_by)
            VALUES (#{row.spaceId}, #{row.nodeId}, #{row.pageTitle}, #{row.pageType}, #{row.tags}, #{row.summary},
                    #{row.content}, #{row.contentFormat}, #{row.visibility}, #{row.status}, #{row.currentVersion},
                    #{row.ownerId}, #{row.sourceModule}, #{row.sourceBizType}, #{row.sourceBizId}, #{row.sort},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertPage(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_page
            SET node_id = #{row.nodeId},
                page_title = #{row.pageTitle},
                page_type = #{row.pageType},
                tags = #{row.tags},
                summary = #{row.summary},
                content = #{row.content},
                content_format = #{row.contentFormat},
                visibility = #{row.visibility},
                status = #{row.status},
                current_version = #{row.currentVersion},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updatePage(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_page
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeletePage(@Param("id") Long id, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM knowledge_page WHERE space_id = #{spaceId} AND deleted = 0")
    long countPages(@Param("spaceId") Long spaceId);

    @Select("SELECT COUNT(*) FROM knowledge_page WHERE node_id = #{nodeId} AND deleted = 0")
    long countPagesInNode(@Param("nodeId") Long nodeId);

    // ------------------------------------------------------------------
    // 全文检索（FULLTEXT + ngram；片段在 SQL 侧截取）
    // ------------------------------------------------------------------

    @Select("""
            <script>
            SELECT p.id,
                   p.space_id AS spaceId,
                   p.node_id AS nodeId,
                   p.page_type AS pageType,
                   p.page_title AS pageTitle,
                   p.tags,
                   p.visibility,
                   p.status,
                   p.owner_id AS ownerId,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   MATCH(p.page_title, p.content, p.tags) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) AS score,
                   SUBSTRING(p.content, GREATEST(1, LOCATE(#{keyword}, p.content) - 60), 200) AS rawSnippet
            FROM knowledge_page p
            WHERE p.deleted = 0
              AND p.status != 'draft'
              AND MATCH(p.page_title, p.content, p.tags) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE)
              <if test="spaceId != null">AND p.space_id = #{spaceId}</if>
              <if test="pageType != null and pageType != ''">AND p.page_type = #{pageType}</if>
            ORDER BY score DESC, p.update_time DESC, p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchFulltext(
            @Param("keyword") String keyword,
            @Param("spaceId") Long spaceId,
            @Param("pageType") String pageType,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT p.id,
                   p.space_id AS spaceId,
                   p.node_id AS nodeId,
                   p.page_type AS pageType,
                   p.page_title AS pageTitle,
                   p.tags,
                   p.visibility,
                   p.status,
                   p.owner_id AS ownerId,
                   DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   NULL AS score,
                   SUBSTRING(p.content, GREATEST(1, LOCATE(#{keyword}, p.content) - 60), 200) AS rawSnippet
            FROM knowledge_page p
            WHERE p.deleted = 0
              AND p.status != 'draft'
              AND (p.page_title LIKE CONCAT('%', #{keyword}, '%')
                   OR p.content LIKE CONCAT('%', #{keyword}, '%')
                   OR p.tags LIKE CONCAT('%', #{keyword}, '%'))
              <if test="spaceId != null">AND p.space_id = #{spaceId}</if>
              <if test="pageType != null and pageType != ''">AND p.page_type = #{pageType}</if>
            ORDER BY p.update_time DESC, p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchLike(
            @Param("keyword") String keyword,
            @Param("spaceId") Long spaceId,
            @Param("pageType") String pageType,
            @Param("limit") int limit
    );
}
