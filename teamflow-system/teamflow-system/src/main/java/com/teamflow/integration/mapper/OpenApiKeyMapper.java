package com.teamflow.integration.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M12 开放 API 密钥表（{@code open_api_key}）访问层。
 *
 * <p><b>安全约定</b>：列表与详情查询不返回 {@code key_hash}；只有 {@link #selectByHash(String)}
 * 在认证时内部使用。密钥明文只在创建响应中出现一次。</p>
 */
@Mapper
public interface OpenApiKeyMapper {

    String COLUMNS = """
            SELECT k.id,
                   k.app_id AS appId,
                   a.app_name AS appName,
                   k.key_name AS keyName,
                   k.key_prefix AS keyPrefix,
                   k.user_id AS userId,
                   u.username,
                   k.scope,
                   DATE_FORMAT(k.expires_at, '%Y-%m-%d %H:%i') AS expiresAt,
                   k.status,
                   DATE_FORMAT(k.last_used_at, '%Y-%m-%d %H:%i') AS lastUsedAt,
                   k.call_count AS callCount,
                   k.remark,
                   DATE_FORMAT(k.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM open_api_key k
            LEFT JOIN open_app a ON a.id = k.app_id AND a.deleted = 0
            LEFT JOIN sys_user u ON u.id = k.user_id
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM open_api_key k
            WHERE k.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (k.key_name LIKE CONCAT('%', #{keyword}, '%') OR k.key_prefix LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="appId != null">AND k.app_id = #{appId}</if>
              <if test="status != null and status != ''">AND k.status = #{status}</if>
            </script>
            """)
    long countKeys(@Param("keyword") String keyword, @Param("appId") Long appId, @Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE k.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (k.key_name LIKE CONCAT('%', #{keyword}, '%') OR k.key_prefix LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="appId != null">AND k.app_id = #{appId}</if>
              <if test="status != null and status != ''">AND k.status = #{status}</if>
            ORDER BY k.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectKeys(@Param("keyword") String keyword, @Param("appId") Long appId,
                                         @Param("status") String status,
                                         @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(COLUMNS + " WHERE k.id = #{id} AND k.deleted = 0 LIMIT 1")
    Map<String, Object> selectById(@Param("id") Long id);

    /**
     * 内部认证查询：按哈希匹配密钥，返回认证所需字段（含过期判定与应用状态）。
     */
    @Select("""
            SELECT k.id,
                   k.app_id AS appId,
                   k.key_prefix AS keyPrefix,
                   k.user_id AS userId,
                   k.scope,
                   k.status,
                   (k.expires_at IS NULL OR k.expires_at > NOW()) AS notExpired,
                   a.status AS appStatus
            FROM open_api_key k
            LEFT JOIN open_app a ON a.id = k.app_id AND a.deleted = 0
            WHERE k.key_hash = #{keyHash} AND k.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectByHash(@Param("keyHash") String keyHash);

    @Insert("""
            INSERT INTO open_api_key (app_id, key_name, key_prefix, key_hash, user_id, scope, expires_at,
                                      status, remark, create_by, update_by)
            VALUES (#{row.appId}, #{row.keyName}, #{row.keyPrefix}, #{row.keyHash}, #{row.userId},
                    #{row.scope}, #{row.expiresAt}, 'active', #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertKey(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("UPDATE open_api_key SET status = 'revoked', update_by = #{operator}"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'active'")
    int revoke(@Param("id") Long id, @Param("operator") String operator);

    @Update("UPDATE open_api_key SET last_used_at = NOW(), call_count = call_count + 1"
            + " WHERE id = #{id} AND deleted = 0")
    int touchUsage(@Param("id") Long id);

    @Update("UPDATE open_api_key SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id, @Param("operator") String operator);
}
