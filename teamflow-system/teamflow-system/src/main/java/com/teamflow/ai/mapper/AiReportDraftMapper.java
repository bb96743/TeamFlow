package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M11 报告草稿表访问层。
 */
@Mapper
public interface AiReportDraftMapper {

    @Insert("""
            INSERT INTO ai_report_draft (report_type, report_date, period_start, period_end, report_title,
                                         content, status, source_summary, owner_id, owner_name,
                                         create_by, update_by)
            VALUES (#{row.reportType}, #{row.reportDate}, #{row.periodStart}, #{row.periodEnd}, #{row.reportTitle},
                    #{row.content}, 'draft', #{row.sourceSummary}, #{row.ownerId}, #{row.ownerName},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertReport(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   report_type AS reportType,
                   DATE_FORMAT(report_date, '%Y-%m-%d') AS reportDate,
                   DATE_FORMAT(period_start, '%Y-%m-%d') AS periodStart,
                   DATE_FORMAT(period_end, '%Y-%m-%d') AS periodEnd,
                   report_title AS reportTitle,
                   content,
                   status,
                   source_summary AS sourceSummary,
                   owner_id AS ownerId,
                   owner_name AS ownerName,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM ai_report_draft
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectReportById(@Param("id") Long id);

    @Select("""
            SELECT id,
                   report_type AS reportType,
                   DATE_FORMAT(report_date, '%Y-%m-%d') AS reportDate,
                   DATE_FORMAT(period_start, '%Y-%m-%d') AS periodStart,
                   DATE_FORMAT(period_end, '%Y-%m-%d') AS periodEnd,
                   report_title AS reportTitle,
                   status,
                   owner_id AS ownerId,
                   DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS updateTime
            FROM ai_report_draft
            WHERE owner_id = #{ownerId} AND deleted = 0
              AND (#{reportType} IS NULL OR #{reportType} = '' OR report_type = #{reportType})
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Map<String, Object>> selectReports(@Param("ownerId") Long ownerId,
                                            @Param("reportType") String reportType,
                                            @Param("offset") int offset,
                                            @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*)
            FROM ai_report_draft
            WHERE owner_id = #{ownerId} AND deleted = 0
              AND (#{reportType} IS NULL OR #{reportType} = '' OR report_type = #{reportType})
            """)
    long countReports(@Param("ownerId") Long ownerId, @Param("reportType") String reportType);

    @Update("""
            UPDATE ai_report_draft
            SET report_title = #{reportTitle},
                content = #{content},
                update_by = #{operator}
            WHERE id = #{id} AND status = 'draft' AND deleted = 0
            """)
    int updateReport(@Param("id") Long id,
                     @Param("reportTitle") String reportTitle,
                     @Param("content") String content,
                     @Param("operator") String operator);
}
