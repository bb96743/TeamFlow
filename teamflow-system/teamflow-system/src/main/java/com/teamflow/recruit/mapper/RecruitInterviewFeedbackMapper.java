package com.teamflow.recruit.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M08 面试结构化评价表（{@code recruit_interview_feedback}）访问层。
 *
 * <p>R4：每维度独立评分 + 评语；同一面试同一维度唯一（{@code uk_recruit_feedback_dimension}）。</p>
 */
@Mapper
public interface RecruitInterviewFeedbackMapper {

    @Select("""
            SELECT f.id,
                   f.interview_id AS interviewId,
                   f.dimension_code AS dimensionCode,
                   f.dimension_name AS dimensionName,
                   f.score,
                   f.comment,
                   f.interviewer_id AS interviewerId,
                   DATE_FORMAT(f.submit_time, '%Y-%m-%d %H:%i') AS submitTime
            FROM recruit_interview_feedback f
            WHERE f.interview_id = #{interviewId} AND f.deleted = 0
            ORDER BY f.id
            """)
    List<Map<String, Object>> selectByInterview(@Param("interviewId") Long interviewId);

    @Select("SELECT COUNT(*) FROM recruit_interview_feedback WHERE interview_id = #{interviewId} AND deleted = 0")
    long countByInterview(@Param("interviewId") Long interviewId);

    @Insert("""
            INSERT INTO recruit_interview_feedback (interview_id, dimension_code, dimension_name, score, comment,
                                                    interviewer_id, submit_time, create_by, update_by)
            VALUES (#{row.interviewId}, #{row.dimensionCode}, #{row.dimensionName}, #{row.score}, #{row.comment},
                    #{row.interviewerId}, #{row.submitTime}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertFeedback(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("UPDATE recruit_interview_feedback SET deleted = 1, update_by = #{operator} WHERE interview_id = #{interviewId} AND deleted = 0")
    int softDeleteByInterview(@Param("interviewId") Long interviewId, @Param("operator") String operator);
}
