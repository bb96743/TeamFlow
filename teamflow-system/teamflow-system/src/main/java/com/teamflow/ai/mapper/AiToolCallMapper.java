package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M11 工具调用轨迹访问层（审计：谁、以什么权限、调用了什么）。
 */
@Mapper
public interface AiToolCallMapper {

    @Insert("""
            INSERT INTO ai_tool_call (session_id, message_id, tool_name, tool_label, arguments, permission_code,
                                      allowed, deny_reason, result_summary, elapsed_ms, operator_id, operator_name,
                                      create_by, update_by)
            VALUES (#{row.sessionId}, #{row.messageId}, #{row.toolName}, #{row.toolLabel}, #{row.arguments},
                    #{row.permissionCode}, #{row.allowed}, #{row.denyReason}, #{row.resultSummary}, #{row.elapsedMs},
                    #{row.operatorId}, #{row.operatorName}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertToolCall(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   session_id AS sessionId,
                   message_id AS messageId,
                   tool_name AS toolName,
                   tool_label AS toolLabel,
                   arguments,
                   permission_code AS permissionCode,
                   allowed,
                   deny_reason AS denyReason,
                   result_summary AS resultSummary,
                   elapsed_ms AS elapsedMs,
                   operator_id AS operatorId,
                   operator_name AS operatorName,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM ai_tool_call
            WHERE session_id = #{sessionId} AND deleted = 0
            ORDER BY id ASC
            """)
    List<Map<String, Object>> selectToolCalls(@Param("sessionId") Long sessionId);
}
