package com.teamflow.recruit.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M08 招聘看板（2.1.5）只读聚合（**只查本模块自有 {@code recruit_*} 表**，不触碰其他模块业务表）。
 *
 * <p>统计口径与指标定义登记在 M13 指标字典（由 {@code RecruitDashboardService} 经 M13 Service 登记），
 * 本 Mapper 只负责把漏斗/渠道/周期口径落成 SQL；数据范围统一按「职位负责人/归属部门」过滤。</p>
 */
@Mapper
public interface RecruitDashboardMapper {

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

    /** 需求：职位数（统计区间内创建的职位）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM recruit_job j
            WHERE j.deleted = 0
              AND j.create_time &gt;= #{startTime} AND j.create_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countJobs(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 漏斗第 1 层：候选人数（区间内入库）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countCandidates(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 漏斗第 3 层：进入面试的候选人数（区间内入库的候选人中，存在面试记录的人数）。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT c.id) FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
              AND EXISTS (SELECT 1 FROM recruit_interview i WHERE i.candidate_id = c.id AND i.deleted = 0)
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countCandidatesWithInterview(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 漏斗第 4 层：拿到 Offer 的候选人数（区间内入库的候选人中，存在 Offer 记录的人数）。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT c.id) FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
              AND EXISTS (SELECT 1 FROM recruit_offer o WHERE o.candidate_id = c.id AND o.deleted = 0)
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countCandidatesWithOffer(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 漏斗第 5 层：已入职人数（区间内入库的候选人中，当前阶段=已入职）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
              AND c.current_stage = 'hired'
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countHired(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 渠道效果：按来源渠道统计候选人数 / 拿 Offer 数 / 入职数。 */
    @Select("""
            <script>
            SELECT c.source_channel AS channel,
                   COUNT(*) AS candidateCount,
                   SUM(CASE WHEN EXISTS (
                       SELECT 1 FROM recruit_offer o WHERE o.candidate_id = c.id AND o.deleted = 0
                   ) THEN 1 ELSE 0 END) AS offerCount,
                   SUM(CASE WHEN c.current_stage = 'hired' THEN 1 ELSE 0 END) AS hiredCount
            FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0
              AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            GROUP BY c.source_channel
            ORDER BY candidateCount DESC
            </script>
            """)
    List<Map<String, Object>> selectChannelStats(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 周期分析：按入职月份统计入职人数。 */
    @Select("""
            <script>
            SELECT DATE_FORMAT(c.hire_time, '%Y-%m') AS month, COUNT(*) AS hiredCount
            FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0 AND c.current_stage = 'hired' AND c.hire_time IS NOT NULL
              AND c.hire_time &gt;= #{startTime} AND c.hire_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            GROUP BY DATE_FORMAT(c.hire_time, '%Y-%m')
            ORDER BY month
            </script>
            """)
    List<Map<String, Object>> selectHiredByMonth(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 平均招聘周期：已入职候选人的「入库 → 入职」自然日天数均值。 */
    @Select("""
            <script>
            SELECT COUNT(*) AS hiredCount,
                   AVG(DATEDIFF(c.hire_time, c.create_time)) AS avgDays,
                   MIN(DATEDIFF(c.hire_time, c.create_time)) AS minDays,
                   MAX(DATEDIFF(c.hire_time, c.create_time)) AS maxDays
            FROM recruit_candidate c
            JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
            WHERE c.deleted = 0 AND c.current_stage = 'hired' AND c.hire_time IS NOT NULL
              AND c.hire_time &gt;= #{startTime} AND c.hire_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            </script>
            """)
    Map<String, Object> selectHireCycle(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** Offer 接受率：区间内创建的 Offer 中 已接受 / 已拒绝 / 有答复 的数量。 */
    @Select("""
            <script>
            SELECT COUNT(*) AS offerCount,
                   SUM(CASE WHEN o.status IN ('accepted', 'onboarded') THEN 1 ELSE 0 END) AS acceptedCount,
                   SUM(CASE WHEN o.status = 'declined' THEN 1 ELSE 0 END) AS declinedCount,
                   SUM(CASE WHEN o.status IN ('sent', 'accepted', 'declined', 'onboarded') THEN 1 ELSE 0 END) AS respondedCount,
                   SUM(CASE WHEN o.status IN ('pending_approval', 'approved', 'sent', 'accepted', 'declined', 'onboarded') THEN 1 ELSE 0 END) AS sentOrLaterCount
            FROM recruit_offer o
            JOIN recruit_candidate c ON c.id = o.candidate_id AND c.deleted = 0
            JOIN recruit_job j ON j.id = o.job_id AND j.deleted = 0
            WHERE o.deleted = 0
              AND o.create_time &gt;= #{startTime} AND o.create_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
            </script>
            """)
    Map<String, Object> selectOfferStats(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /** 面试轮次均值：区间内入库候选人的平均面试轮数（仅统计已有面试记录的候选人）。 */
    @Select("""
            <script>
            SELECT AVG(roundCount) AS avgRounds, COUNT(*) AS interviewedCandidates
            FROM (
                SELECT c.id, COUNT(i.id) AS roundCount
                FROM recruit_candidate c
                JOIN recruit_job j ON j.id = c.job_id AND j.deleted = 0
                JOIN recruit_interview i ON i.candidate_id = c.id AND i.deleted = 0
                WHERE c.deleted = 0
                  AND c.create_time &gt;= #{startTime} AND c.create_time &lt;= #{endTime}
            """ + SCOPE_FILTER + """
                GROUP BY c.id
            ) rounds
            </script>
            """)
    Map<String, Object> selectInterviewStats(
            @Param("scope") DataScopeCriteria scope,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );
}
