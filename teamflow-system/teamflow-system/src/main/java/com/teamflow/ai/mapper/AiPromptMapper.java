package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M11 提示词版本表访问层（读取同一编码下生效的最大版本）。
 */
@Mapper
public interface AiPromptMapper {

    @Select("""
            SELECT id,
                   prompt_code AS promptCode,
                   prompt_name AS promptName,
                   version_no AS versionNo,
                   content,
                   scope,
                   status,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM ai_prompt_template
            WHERE prompt_code = #{promptCode} AND status = 'active' AND deleted = 0
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Map<String, Object> selectActivePrompt(@Param("promptCode") String promptCode);

    @Select("""
            SELECT id,
                   prompt_code AS promptCode,
                   prompt_name AS promptName,
                   version_no AS versionNo,
                   status,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM ai_prompt_template
            WHERE prompt_code = #{promptCode} AND deleted = 0
            ORDER BY version_no DESC
            """)
    List<Map<String, Object>> selectPromptVersions(@Param("promptCode") String promptCode);
}
