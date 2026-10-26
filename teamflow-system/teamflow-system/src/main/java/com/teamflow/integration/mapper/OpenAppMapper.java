package com.teamflow.integration.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M12 接入应用表（{@code open_app}）访问层。
 *
 * <p>列表/详情查询不返回凭据密文；密文只经 {@link #selectCredentialCipher(Long)} 在联通性检测内部读取。</p>
 */
@Mapper
public interface OpenAppMapper {

    String COLUMNS = """
            SELECT a.id,
                   a.app_code AS appCode,
                   a.app_name AS appName,
                   a.app_type AS appType,
                   a.endpoint_url AS endpointUrl,
                   a.config_json AS configJson,
                   a.credential_hint AS credentialHint,
                   CASE WHEN a.credential_cipher IS NULL OR a.credential_cipher = '' THEN 0 ELSE 1 END
                       AS credentialConfigured,
                   a.status,
                   DATE_FORMAT(a.last_check_time, '%Y-%m-%d %H:%i') AS lastCheckTime,
                   a.last_check_result AS lastCheckResult,
                   a.last_check_message AS lastCheckMessage,
                   a.remark,
                   DATE_FORMAT(a.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(a.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM open_app a
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM open_app a
            WHERE a.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (a.app_code LIKE CONCAT('%', #{keyword}, '%') OR a.app_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="appType != null and appType != ''">AND a.app_type = #{appType}</if>
              <if test="status != null and status != ''">AND a.status = #{status}</if>
            </script>
            """)
    long countApps(@Param("keyword") String keyword, @Param("appType") String appType,
                   @Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE a.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (a.app_code LIKE CONCAT('%', #{keyword}, '%') OR a.app_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="appType != null and appType != ''">AND a.app_type = #{appType}</if>
              <if test="status != null and status != ''">AND a.status = #{status}</if>
            ORDER BY a.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectApps(@Param("keyword") String keyword, @Param("appType") String appType,
                                         @Param("status") String status,
                                         @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(COLUMNS + " WHERE a.id = #{id} AND a.deleted = 0 LIMIT 1")
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("SELECT id FROM open_app WHERE app_code = #{appCode} AND deleted = 0"
            + " AND (#{excludeId} IS NULL OR id <> #{excludeId}) LIMIT 1")
    Long selectIdByCode(@Param("appCode") String appCode, @Param("excludeId") Long excludeId);

    /** 内部使用：读取凭据密文（不对外返回）。 */
    @Select("SELECT credential_cipher FROM open_app WHERE id = #{id} AND deleted = 0 LIMIT 1")
    String selectCredentialCipher(@Param("id") Long id);

    @Insert("""
            INSERT INTO open_app (app_code, app_name, app_type, endpoint_url, config_json, credential_cipher,
                                  credential_hint, status, remark, create_by, update_by)
            VALUES (#{row.appCode}, #{row.appName}, #{row.appType}, #{row.endpointUrl}, #{row.configJson},
                    #{row.credentialCipher}, #{row.credentialHint}, #{row.status}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertApp(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE open_app
            SET app_name = #{row.appName},
                app_type = #{row.appType},
                endpoint_url = #{row.endpointUrl},
                config_json = #{row.configJson},
                credential_cipher = #{row.credentialCipher},
                credential_hint = #{row.credentialHint},
                status = #{row.status},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateApp(@Param("id") Long id, @Param("row") Map<String, Object> row,
                  @Param("operator") String operator);

    @Update("""
            UPDATE open_app
            SET last_check_time = #{time},
                last_check_result = #{result},
                last_check_message = #{message},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateCheckResult(@Param("id") Long id, @Param("result") String result,
                          @Param("message") String message, @Param("time") LocalDateTime time,
                          @Param("operator") String operator);

    @Update("UPDATE open_app SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id, @Param("operator") String operator);
}
