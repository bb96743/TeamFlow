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
 * M08 职位表（{@code recruit_job}）访问层。
 *
 * <p>列表/详情按数据范围过滤（G-2 默认拒绝）：命中条件为「本人是招聘负责人、或职位归属部门在数据范围内」。
 * 内部写操作前用 {@link #selectJobById(Long)} 校验存在性（不受数据范围限制，与 M02/M03 的写法一致）。</p>
 */
@Mapper
public interface RecruitJobMapper {

    String COLUMNS = """
            SELECT j.id,
                   j.job_code AS jobCode,
                   j.job_title AS jobTitle,
                   j.job_desc AS jobDesc,
                   j.dept_id AS deptId,
                   d.dept_name AS deptName,
                   j.headcount,
                   j.job_level AS jobLevel,
                   j.salary_range AS salaryRange,
                   j.city,
                   j.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   j.status,
                   j.approval_state AS approvalState,
                   j.approval_instance_id AS approvalInstanceId,
                   DATE_FORMAT(j.publish_time, '%Y-%m-%d %H:%i') AS publishTime,
                   DATE_FORMAT(j.close_time, '%Y-%m-%d %H:%i') AS closeTime,
                   j.remark,
                   DATE_FORMAT(j.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(j.update_time, '%Y-%m-%d %H:%i') AS updateTime,
                   (SELECT COUNT(*) FROM recruit_candidate c WHERE c.job_id = j.id AND c.deleted = 0) AS candidateCount,
                   (SELECT COUNT(*) FROM recruit_candidate c WHERE c.job_id = j.id AND c.deleted = 0
                     AND c.current_stage = 'hired') AS hiredCount
            FROM recruit_job j
            LEFT JOIN sys_dept d ON d.id = j.dept_id
            LEFT JOIN sys_user u ON u.id = j.owner_id
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
            FROM recruit_job j
            WHERE j.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (j.job_code LIKE CONCAT('%', #{keyword}, '%') OR j.job_title LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND j.status = #{status}
              </if>
              <if test="deptId != null">
              AND j.dept_id = #{deptId}
              </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countJobs(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("deptId") Long deptId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE j.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (j.job_code LIKE CONCAT('%', #{keyword}, '%') OR j.job_title LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
              AND j.status = #{status}
              </if>
              <if test="deptId != null">
              AND j.dept_id = #{deptId}
              </if>
            """ + SCOPE_FILTER + """
            ORDER BY j.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectJobs(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("deptId") Long deptId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE j.deleted = 0 AND j.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectJob(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            """ + COLUMNS + """
            WHERE j.deleted = 0 AND j.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectJobById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM recruit_job
            WHERE job_code = #{jobCode} AND deleted = 0 AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countJobCode(@Param("jobCode") String jobCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO recruit_job (job_code, job_title, job_desc, dept_id, headcount, job_level, salary_range, city,
                                     owner_id, status, approval_state, remark, create_by, update_by)
            VALUES (#{row.jobCode}, #{row.jobTitle}, #{row.jobDesc}, #{row.deptId}, #{row.headcount},
                    #{row.jobLevel}, #{row.salaryRange}, #{row.city}, #{row.ownerId}, #{row.status},
                    #{row.approvalState}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertJob(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_job
            SET job_title = #{row.jobTitle},
                job_desc = #{row.jobDesc},
                dept_id = #{row.deptId},
                headcount = #{row.headcount},
                job_level = #{row.jobLevel},
                salary_range = #{row.salaryRange},
                city = #{row.city},
                owner_id = #{row.ownerId},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateJob(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE recruit_job
            SET status = #{status},
                approval_state = #{approvalState},
                approval_instance_id = #{approvalInstanceId},
                publish_time = #{publishTime},
                close_time = #{closeTime},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateJobStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("approvalState") String approvalState,
            @Param("approvalInstanceId") Long approvalInstanceId,
            @Param("publishTime") LocalDateTime publishTime,
            @Param("closeTime") LocalDateTime closeTime,
            @Param("operator") String operator
    );

    @Update("UPDATE recruit_job SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteJob(@Param("id") Long id, @Param("operator") String operator);
}
