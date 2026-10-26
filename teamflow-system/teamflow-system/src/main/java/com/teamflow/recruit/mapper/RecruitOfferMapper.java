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
 * M08 Offer 表（{@code recruit_offer}）访问层。
 *
 * <p>审批链复用 M03 流程实例（{@code approval_instance_id} 只存引用）；入职转用户只写
 * {@code recruit_candidate.hired_user_id}，本表不存用户副本。</p>
 */
@Mapper
public interface RecruitOfferMapper {

    String COLUMNS = """
            SELECT o.id,
                   o.candidate_id AS candidateId,
                   c.name_snapshot AS candidateName,
                   o.job_id AS jobId,
                   j.job_title AS jobTitle,
                   o.template_id AS templateId,
                   t.template_name AS templateName,
                   o.salary,
                   DATE_FORMAT(o.expected_onboard_date, '%Y-%m-%d') AS expectedOnboardDate,
                   o.status,
                   o.approval_state AS approvalState,
                   o.approval_instance_id AS approvalInstanceId,
                   DATE_FORMAT(o.sent_time, '%Y-%m-%d %H:%i') AS sentTime,
                   DATE_FORMAT(o.accepted_time, '%Y-%m-%d %H:%i') AS acceptedTime,
                   o.declined_reason AS declinedReason,
                   o.remark,
                   DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(o.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM recruit_offer o
            JOIN recruit_candidate c ON c.id = o.candidate_id AND c.deleted = 0
            JOIN recruit_job j ON j.id = o.job_id AND j.deleted = 0
            LEFT JOIN recruit_offer_template t ON t.id = o.template_id AND t.deleted = 0
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
            FROM recruit_offer o
            JOIN recruit_candidate c ON c.id = o.candidate_id AND c.deleted = 0
            JOIN recruit_job j ON j.id = o.job_id AND j.deleted = 0
            WHERE o.deleted = 0
              <if test="candidateId != null">AND o.candidate_id = #{candidateId}</if>
              <if test="jobId != null">AND o.job_id = #{jobId}</if>
              <if test="status != null and status != ''">AND o.status = #{status}</if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countOffers(
            @Param("candidateId") Long candidateId,
            @Param("jobId") Long jobId,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE o.deleted = 0
              <if test="candidateId != null">AND o.candidate_id = #{candidateId}</if>
              <if test="jobId != null">AND o.job_id = #{jobId}</if>
              <if test="status != null and status != ''">AND o.status = #{status}</if>
            """ + SCOPE_FILTER + """
            ORDER BY o.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectOffers(
            @Param("candidateId") Long candidateId,
            @Param("jobId") Long jobId,
            @Param("status") String status,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE o.deleted = 0 AND o.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectOffer(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            SELECT id, candidate_id AS candidateId, job_id AS jobId, template_id AS templateId, salary,
                   expected_onboard_date AS expectedOnboardDate, status, approval_state AS approvalState,
                   approval_instance_id AS approvalInstanceId, sent_time AS sentTime, accepted_time AS acceptedTime,
                   declined_reason AS declinedReason, remark
            FROM recruit_offer
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectOfferById(@Param("id") Long id);

    @Select("""
            SELECT id, candidate_id AS candidateId, job_id AS jobId, status, approval_state AS approvalState,
                   approval_instance_id AS approvalInstanceId
            FROM recruit_offer
            WHERE candidate_id = #{candidateId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectByCandidate(@Param("candidateId") Long candidateId);

    @Insert("""
            INSERT INTO recruit_offer (candidate_id, job_id, template_id, salary, expected_onboard_date, status,
                                       approval_state, remark, create_by, update_by)
            VALUES (#{row.candidateId}, #{row.jobId}, #{row.templateId}, #{row.salary}, #{row.expectedOnboardDate},
                    #{row.status}, #{row.approvalState}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertOffer(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_offer
            SET template_id = #{row.templateId},
                salary = #{row.salary},
                expected_onboard_date = #{row.expectedOnboardDate},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateOffer(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_offer
            SET status = #{status},
                approval_state = #{approvalState},
                approval_instance_id = #{approvalInstanceId},
                sent_time = #{sentTime},
                accepted_time = #{acceptedTime},
                declined_reason = #{declinedReason},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateOfferStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("approvalState") String approvalState,
            @Param("approvalInstanceId") Long approvalInstanceId,
            @Param("sentTime") LocalDateTime sentTime,
            @Param("acceptedTime") LocalDateTime acceptedTime,
            @Param("declinedReason") String declinedReason,
            @Param("operator") String operator
    );

    /** 入职完成：只改状态（保留发送/答复时间），用户创建结果体现在 candidate.hired_user_id。 */
    @Update("""
            UPDATE recruit_offer
            SET status = 'onboarded', update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int markOnboarded(@Param("id") Long id, @Param("operator") String operator);
}
