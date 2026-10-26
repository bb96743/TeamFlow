package com.teamflow.integration.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M12 开放平台调用审计表（{@code open_event_log}）访问层。
 *
 * <p>审计写入不得阻断主流程；调用方以 try/catch 包裹。日志内不记录任何密钥/凭据内容。</p>
 */
@Mapper
public interface OpenEventLogMapper {

    @Insert("""
            INSERT INTO open_event_log (channel, key_id, app_id, user_id, method, path, status, cost_ms,
                                        client_ip, request_id, detail, create_by, update_by)
            VALUES (#{row.channel}, #{row.keyId}, #{row.appId}, #{row.userId}, #{row.method}, #{row.path},
                    #{row.status}, #{row.costMs}, #{row.clientIp}, #{row.requestId}, #{row.detail},
                    #{operator}, #{operator})
            """)
    int insertLog(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            <script>
            SELECT id, channel, key_id AS keyId, app_id AS appId, user_id AS userId, method, path, status,
                   cost_ms AS costMs, client_ip AS clientIp, request_id AS requestId, detail,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM open_event_log
            WHERE deleted = 0
            <if test="channel != null and channel != ''">AND channel = #{channel}</if>
            <if test="keyId != null">AND key_id = #{keyId}</if>
            ORDER BY id DESC
            LIMIT 100
            </script>
            """)
    List<Map<String, Object>> selectRecent(@Param("channel") String channel, @Param("keyId") Long keyId);
}
