package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M11 消息表访问层。
 */
@Mapper
public interface AiMessageMapper {

    @Insert("""
            INSERT INTO ai_message (session_id, role, content, intent_code, intent_label, tool_name,
                                    tool_params, tool_result, citations, model_name, status, elapsed_ms,
                                    token_estimate, create_by, update_by)
            VALUES (#{row.sessionId}, #{row.role}, #{row.content}, #{row.intentCode}, #{row.intentLabel},
                    #{row.toolName}, #{row.toolParams}, #{row.toolResult}, #{row.citations}, #{row.modelName},
                    #{row.status}, #{row.elapsedMs}, #{row.tokenEstimate}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMessage(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   session_id AS sessionId,
                   role,
                   content,
                   intent_code AS intentCode,
                   intent_label AS intentLabel,
                   tool_name AS toolName,
                   tool_params AS toolParams,
                   tool_result AS toolResult,
                   citations,
                   model_name AS modelName,
                   status,
                   elapsed_ms AS elapsedMs,
                   token_estimate AS tokenEstimate,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM ai_message
            WHERE session_id = #{sessionId} AND deleted = 0
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectMessages(@Param("sessionId") Long sessionId, @Param("limit") int limit);
}
