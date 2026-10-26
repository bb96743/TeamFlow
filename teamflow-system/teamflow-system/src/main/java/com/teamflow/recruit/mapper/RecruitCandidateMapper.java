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
 * M08 候选人表（{@code recruit_candidate}）访问层。
 *
 * <p>数据范围按「所属职位的招聘负责人 / 归属部门」过滤；手机号/邮箱在 Service 层脱敏输出。
 * {@code name_snapshot} 是**外部候选人报名姓名快照**，不是 {@code sys_user} 副本（入职只引用 hired_user_id）。</p>
 */
@Mapper
public interface RecruitCandidateMapper {

    String COLUMNS = """
            SELECT c.id,
                   c.candidate_code AS candidateCode,
                   c.job_id AS jobId,
                   j.job_title AS jobTitle,
                   j.job_code AS jobCode,
                   c.name_snapshot AS nameSnapshot,
                   c.phone,
                   c.email,
                   c.phone_norm AS phoneNorm,
                   c.email_norm AS emailNorm,
                   c.source_channel AS sourceChannel,
                   c.tags,
                   c.current_stage AS currentStage,
                   DATE_FORMAT(c.stage_time, '%Y-%m-%d %H:%i') AS stageTime,
                   c.reject_reason AS rejectReason,
                   c.hired_user_id AS hiredUserId,
                   DATE_FORMAT(c.hire_time, '%Y-%m-%d %H:%i') AS hireTime,
                   c.remark,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(c.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
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
            FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (c.name_snapshot LIKE CONCAT('%', #{keyword}, '%')
                   OR c.phone LIKE CONCAT('%', #{keyword}, '%')
                   OR c.email LIKE CONCAT('%', #{keyword}, '%')
                   OR c.candidate_code LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="jobId != null">
              AND c.job_id = #{jobId}
              </if>
              <if test="stage != null and stage != ''">
              AND c.current_stage = #{stage}
              </if>
              <if test="channel != null and channel != ''">
              AND c.source_channel = #{channel}
              </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countCandidates(
            @Param("keyword") String keyword,
            @Param("jobId") Long jobId,
            @Param("stage") String stage,
            @Param("channel") String channel,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE c.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (c.name_snapshot LIKE CONCAT('%', #{keyword}, '%')
                   OR c.phone LIKE CONCAT('%', #{keyword}, '%')
                   OR c.email LIKE CONCAT('%', #{keyword}, '%')
                   OR c.candidate_code LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="jobId != null">
              AND c.job_id = #{jobId}
              </if>
              <if test="stage != null and stage != ''">
              AND c.current_stage = #{stage}
              </if>
              <if test="channel != null and channel != ''">
              AND c.source_channel = #{channel}
              </if>
            """ + SCOPE_FILTER + """
            ORDER BY c.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectCandidates(
            @Param("keyword") String keyword,
            @Param("jobId") Long jobId,
            @Param("stage") String stage,
            @Param("channel") String channel,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE c.deleted = 0 AND c.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectCandidate(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            """ + COLUMNS + """
            WHERE c.deleted = 0 AND c.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectCandidateById(@Param("id") Long id);

    /** R1 查重：按 dedup_key 命中已有候选人（不重复创建）。 */
    @Select("""
            """ + COLUMNS + """
            WHERE c.deleted = 0 AND c.dedup_key = #{dedupKey}
            LIMIT 1
            """)
    Map<String, Object> selectCandidateByDedupKey(@Param("dedupKey") String dedupKey);

    @Select("""
            SELECT COUNT(*) FROM recruit_candidate
            WHERE candidate_code = #{candidateCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countCandidateCode(@Param("candidateCode") String candidateCode, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM recruit_candidate WHERE job_id = #{jobId} AND deleted = 0")
    long countCandidatesByJob(@Param("jobId") Long jobId);

    @Insert("""
            INSERT INTO recruit_candidate (candidate_code, job_id, name_snapshot, phone, email, phone_norm, email_norm,
                                           dedup_key, source_channel, tags, current_stage, stage_time, remark,
                                           create_by, update_by)
            VALUES (#{row.candidateCode}, #{row.jobId}, #{row.nameSnapshot}, #{row.phone}, #{row.email},
                    #{row.phoneNorm}, #{row.emailNorm}, #{row.dedupKey}, #{row.sourceChannel}, #{row.tags},
                    #{row.currentStage}, #{row.stageTime}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertCandidate(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_candidate
            SET name_snapshot = #{row.nameSnapshot},
                phone = #{row.phone},
                email = #{row.email},
                phone_norm = #{row.phoneNorm},
                email_norm = #{row.emailNorm},
                dedup_key = #{row.dedupKey},
                source_channel = #{row.sourceChannel},
                tags = #{row.tags},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateCandidate(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_candidate
            SET current_stage = #{stage},
                stage_time = #{stageTime},
                reject_reason = #{rejectReason},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateStage(
            @Param("id") Long id,
            @Param("stage") String stage,
            @Param("stageTime") LocalDateTime stageTime,
            @Param("rejectReason") String rejectReason,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE recruit_candidate
            SET current_stage = 'hired',
                stage_time = #{hireTime},
                hire_time = #{hireTime},
                hired_user_id = #{hiredUserId},
                reject_reason = NULL,
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int markHired(
            @Param("id") Long id,
            @Param("hiredUserId") Long hiredUserId,
            @Param("hireTime") LocalDateTime hireTime,
            @Param("operator") String operator
    );
}
