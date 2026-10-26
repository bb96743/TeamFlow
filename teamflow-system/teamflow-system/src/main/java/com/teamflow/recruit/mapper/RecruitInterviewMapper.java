package com.teamflow.recruit.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
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
 * M08 面试轮次表（{@code recruit_interview}）访问层。
 *
 * <p>数据范围按候选人所属职位的负责人/部门过滤。日程能力归 M06，本表只存安排时间与地点。</p>
 */
@Mapper
public interface RecruitInterviewMapper {

    String COLUMNS = """
            SELECT i.id,
                   i.candidate_id AS candidateId,
                   c.name_snapshot AS candidateName,
                   i.job_id AS jobId,
                   j.job_title AS jobTitle,
                   i.round_no AS roundNo,
                   i.round_name AS roundName,
                   i.interviewer_id AS interviewerId,
                   u.nickname AS interviewerName,
                   DATE_FORMAT(i.scheduled_time, '%Y-%m-%d %H:%i') AS scheduledTime,
                   i.duration_minutes AS durationMinutes,
                   i.location,
                   i.status,
                   i.result,
                   i.remark,
                   DATE_FORMAT(i.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM recruit_interview i
            JOIN recruit_candidate c ON c.id = i.candidate_id AND c.deleted = 0
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            LEFT JOIN sys_user u ON u.id = i.interviewer_id
            """;

    String SCOPE_FILTER = """
              AND (
                  #{scope.allScope} = TRUE
                  OR j.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR j.dept_id IN
                  <foreach collection="scope.deptIds" item="deptId" open="(" separator="," close=")">
                      #{deptId}
                  </foreach>
                  </if>
              )
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM recruit_interview i
            JOIN recruit_candidate c ON c.id = i.candidate_id AND c.deleted = 0
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE i.deleted = 0
              <if test="candidateId != null">AND i.candidate_id = #{candidateId}</if>
              <if test="jobId != null">AND i.job_id = #{jobId}</if>
              <if test="status != null and status != ''">AND i.status = #{status}</if>
              <if test="interviewerId != null">AND i.interviewer_id = #{interviewerId}</if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countInterviews(
            @Param("candidateId") Long candidateId,
            @Param("jobId") Long jobId,
            @Param("status") String status,
            @Param("interviewerId") Long interviewerId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE i.deleted = 0
              <if test="candidateId != null">AND i.candidate_id = #{candidateId}</if>
              <if test="jobId != null">AND i.job_id = #{jobId}</if>
              <if test="status != null and status != ''">AND i.status = #{status}</if>
              <if test="interviewerId != null">AND i.interviewer_id = #{interviewerId}</if>
            """ + SCOPE_FILTER + """
            ORDER BY i.scheduled_time DESC, i.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectInterviews(
            @Param("candidateId") Long candidateId,
            @Param("jobId") Long jobId,
            @Param("status") String status,
            @Param("interviewerId") Long interviewerId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE i.deleted = 0 AND i.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectInterview(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            SELECT id, candidate_id AS candidateId, job_id AS jobId, round_no AS roundNo, round_name AS roundName,
                   interviewer_id AS interviewerId, scheduled_time AS scheduledTime, duration_minutes AS durationMinutes,
                   location, status, result, remark
            FROM recruit_interview
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectInterviewById(@Param("id") Long id);

    @Select("""
            SELECT id, candidate_id AS candidateId, job_id AS jobId, round_no AS roundNo, round_name AS roundName,
                   interviewer_id AS interviewerId, scheduled_time AS scheduledTime, duration_minutes AS durationMinutes,
                   location, status, result, remark
            FROM recruit_interview
            WHERE candidate_id = #{candidateId} AND deleted = 0
            ORDER BY round_no
            """)
    List<Map<String, Object>> selectByCandidate(@Param("candidateId") Long candidateId);

    @Select("SELECT COALESCE(MAX(round_no), 0) FROM recruit_interview WHERE candidate_id = #{candidateId} AND deleted = 0")
    int selectMaxRound(@Param("candidateId") Long candidateId);

    @Insert("""
            INSERT INTO recruit_interview (candidate_id, job_id, round_no, round_name, interviewer_id, scheduled_time,
                                           duration_minutes, location, status, result, remark, create_by, update_by)
            VALUES (#{row.candidateId}, #{row.jobId}, #{row.roundNo}, #{row.roundName}, #{row.interviewerId},
                    #{row.scheduledTime}, #{row.durationMinutes}, #{row.location}, #{row.status}, 'pending',
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertInterview(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_interview
            SET round_name = #{row.roundName},
                interviewer_id = #{row.interviewerId},
                scheduled_time = #{row.scheduledTime},
                duration_minutes = #{row.durationMinutes},
                location = #{row.location},
                status = #{row.status},
                result = #{row.result},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateInterview(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM recruit_interview WHERE candidate_id = #{candidateId} AND deleted = 0")
    long countByCandidate(@Param("candidateId") Long candidateId);
}
