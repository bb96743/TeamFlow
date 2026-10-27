package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M11 AI 待办建议表（{@code ai_suggestion}）访问层。
 *
 * <p>只保存建议与理由；本 Mapper 没有任何写业务表的语句。同一业务单据按唯一键覆盖为最新建议。</p>
 */
@Mapper
public interface AiSuggestionMapper {

    String COLUMNS = """
            SELECT s.id,
                   s.suggestion_type AS suggestionType,
                   s.biz_type AS bizType,
                   s.biz_id AS bizId,
                   s.current_assignee_id AS currentAssigneeId,
                   cu.nickname AS currentAssigneeName,
                   s.suggested_user_id AS suggestedUserId,
                   su.nickname AS suggestedUserName,
                   s.reason,
                   s.evidence_json AS evidenceJson,
                   s.status,
                   DATE_FORMAT(s.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(s.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM ai_suggestion s
            LEFT JOIN sys_user cu ON cu.id = s.current_assignee_id
            LEFT JOIN sys_user su ON su.id = s.suggested_user_id
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_suggestion s
            WHERE s.deleted = 0
              <if test="status != null and status != ''">AND s.status = #{status}</if>
            </script>
            """)
    long countSuggestions(@Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE s.deleted = 0
              <if test="status != null and status != ''">AND s.status = #{status}</if>
            ORDER BY s.update_time DESC, s.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectSuggestions(@Param("status") String status,
                                                @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(COLUMNS + " WHERE s.id = #{id} AND s.deleted = 0 LIMIT 1")
    Map<String, Object> selectById(@Param("id") Long id);

    /** 幂等写入：同一（类型+业务+单据）覆盖为最新建议，返回建议 ID。 */
    @Insert("""
            INSERT INTO ai_suggestion (suggestion_type, biz_type, biz_id, current_assignee_id,
                                       suggested_user_id, reason, evidence_json, status, create_by, update_by)
            VALUES (#{row.suggestionType}, #{row.bizType}, #{row.bizId}, #{row.currentAssigneeId},
                    #{row.suggestedUserId}, #{row.reason}, #{row.evidenceJson}, 'pending',
                    #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE
                current_assignee_id = VALUES(current_assignee_id),
                suggested_user_id = VALUES(suggested_user_id),
                reason = VALUES(reason),
                evidence_json = VALUES(evidence_json),
                status = 'pending',
                update_by = VALUES(update_by)
            """)
    int upsertSuggestion(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("SELECT id FROM ai_suggestion WHERE suggestion_type = #{suggestionType}"
            + " AND biz_type = #{bizType} AND biz_id = #{bizId} LIMIT 1")
    Long selectIdByBiz(@Param("suggestionType") String suggestionType,
                       @Param("bizType") String bizType, @Param("bizId") Long bizId);

    @Update("UPDATE ai_suggestion SET status = #{status}, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("operator") String operator);
}
