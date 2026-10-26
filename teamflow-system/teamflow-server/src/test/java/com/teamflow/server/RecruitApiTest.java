package com.teamflow.server;

import com.teamflow.system.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M08 招聘集成测试（真实 HTTP + 真实 M00/M03 能力复用）。
 *
 * <p><b>覆盖</b>：</p>
 * <ol>
 *   <li>招聘漏斗端到端：职位 → 需求审批（复用 M03 flow_instance）→ 候选人入库（查重）→ 简历（只存 file_id）
 *       → 面试轮次 → 结构化评价 → Offer 审批链 → 答复 → 入职转 sys_user（R7 幂等）；</li>
 *   <li>看板口径：4 个招聘指标必须先登记到 M13 指标字典才允许出数（未登记 400），登记后漏斗/渠道/
 *       招聘周期/接受率与手算一致（用独立部门 + DEPT 数据范围账号验证，避免其他测试数据干扰）；</li>
 *   <li>状态机与审批守卫：非法阶段推进被拒、审批中不可编辑、非指定审批人不可审批；</li>
 *   <li>边界硬约束：M08 未新建流程表/文件表/用户表（复用 flow_*、sys_file、sys_user）。</li>
 * </ol>
 *
 * <p><b>测试数据自清理（本次新增硬要求）</b>：所有用例创建的数据都在 {@link #cleanup()} 中按跟踪的 ID
 * 逐表删除（含 M03 流程实例/节点/待办、G-1 文件引用与文件本体、入职产生的 sys_user、临时部门、
 * M13 指标字典里由看板登记的口径），不向开发库留招聘测试残留。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class RecruitApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);
    private static final String RANGE_START = "2026-01-01";
    private static final String RANGE_END = "2026-12-31";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FileService fileService;

    private final List<Long> createdJobIds = new ArrayList<>();
    private final List<Long> createdCandidateIds = new ArrayList<>();
    private final List<Long> createdInterviewIds = new ArrayList<>();
    private final List<Long> createdOfferIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdDeptIds = new ArrayList<>();
    private final List<Long> createdFileIds = new ArrayList<>();

    // ------------------------------------------------------------------
    // ① 招聘漏斗端到端（2.1.1–2.1.4 + R7 幂等 + 复用 M03/G-1/M00）
    // ------------------------------------------------------------------

    @Test
    void fullRecruitmentFunnelEndToEnd() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // ---------- 职位 + 需求审批（复用 M03 flow_instance / flow_task / flow_node_instance） ----------
        Long jobId = createRecruitingJob(admin, suffix, 5L);
        Map<String, Object> job = dataOf(get(admin, "/api/recruit/jobs/" + jobId));
        assertThat(job).containsEntry("status", "recruiting").containsEntry("approvalState", "approved");
        assertThat(intOf(job, "candidateCount")).isZero();
        Long jobInstanceId = longOf(job, "approvalInstanceId");
        assertThat(jobInstanceId).isNotNull();
        assertThat(countBySql("SELECT COUNT(*) FROM flow_instance WHERE id = ? AND instance_type = 'job_approval'",
                jobInstanceId)).as("复用 M03 流程实例（job_approval）").isEqualTo(1);
        assertThat(countBySql("SELECT COUNT(*) FROM flow_instance WHERE id = ? AND status = 'completed'",
                jobInstanceId)).isEqualTo(1);
        assertThat(countBySql("SELECT COUNT(*) FROM flow_task WHERE instance_id = ?", jobInstanceId))
                .as("审批待办落在 M03 flow_task").isEqualTo(1);
        assertThat(countBySql("SELECT COUNT(*) FROM flow_node_instance WHERE instance_id = ?", jobInstanceId))
                .as("审批轨迹落在 M03 flow_node_instance").isGreaterThanOrEqualTo(1);
        assertThat(job.get("approvalHistory")).isNotNull();

        // ---------- 简历：G-1 上传 → M08 只存 file_id（引用计数 +1） ----------
        Long fileId = uploadResume(admin, "resume-" + suffix + ".pdf", "pdf-resume-content-" + suffix);
        assertThat(fileId).isNotNull();

        // ---------- 候选人入库（R1 查重） ----------
        String phone = uniquePhone();
        String email = "zhaoliu" + suffix + "@example.com";
        Long candidateId = createCandidate(admin, jobId, "赵六" + suffix, phone, email, "referral");
        Long resumeId = attachResume(admin, candidateId, fileId, "resume-" + suffix + ".pdf");
        assertThat(resumeId).isNotNull();
        assertThat(countBySql("SELECT ref_count FROM sys_file WHERE id = ?", fileId))
                .as("M08 只存 file_id，并通过 M00 引用计数保护文件").isEqualTo(1);
        List<Map<String, Object>> resumes = listOf(get(admin, "/api/recruit/candidates/" + candidateId + "/resumes"));
        assertThat(resumes).hasSize(1);
        assertThat(((Number) resumes.get(0).get("fileId")).longValue()).isEqualTo(fileId);

        // ---------- 阶段推进 → 面试 → 结构化评价（R4） ----------
        advanceStage(admin, candidateId, "screening", null);
        advanceStage(admin, candidateId, "interview", null);
        Long interviewId = scheduleInterview(admin, candidateId, "技术面", "2026-11-10 14:00");
        completeInterview(admin, interviewId, "pass");
        submitFeedback(admin, interviewId);
        Map<String, Object> interview = dataOf(get(admin, "/api/recruit/interviews/" + interviewId));
        List<Map<String, Object>> feedbacks = listValue(interview.get("feedbacks"));
        assertThat(feedbacks).as("R4：每维度独立评分与评语").hasSize(2);
        assertThat(feedbacks).allSatisfy(feedback -> {
            assertThat(feedback.get("score")).isNotNull();
            assertThat(String.valueOf(feedback.get("comment"))).isNotBlank();
        });

        // ---------- Offer 审批链（复用 M03 流程实例 offer_approval）→ 答复 ----------
        Long offerId = createOffer(admin, candidateId, 20000);
        assertThat(String.valueOf(dataOf(get(admin, "/api/recruit/candidates/" + candidateId)).get("currentStage")))
                .as("创建 Offer 后候选人阶段自动推进到 offer").isEqualTo("offer");
        submitOfferApproval(admin, offerId, 1L);
        Map<String, Object> offerUnderApproval = dataOf(get(admin, "/api/recruit/offers/" + offerId));
        assertThat(offerUnderApproval).containsEntry("status", "pending_approval");
        Long offerInstanceId = longOf(offerUnderApproval, "approvalInstanceId");
        assertThat(countBySql("SELECT COUNT(*) FROM flow_instance WHERE id = ? AND instance_type = 'offer_approval'",
                offerInstanceId)).as("Offer 审批复用 M03 流程实例（offer_approval）").isEqualTo(1);
        decideOfferApproval(admin, offerId, true);
        sendOffer(admin, offerId);
        respondOffer(admin, offerId, true);

        // ---------- 入职转 sys_user（R7 幂等，不建用户表） ----------
        long usersBefore = countBySql("SELECT COUNT(*) FROM sys_user WHERE deleted = 0");
        Map<String, Object> onboard = dataOf(post(admin, "/api/recruit/offers/" + offerId + "/onboard", Map.of()));
        Long hiredUserId = ((Number) onboard.get("userId")).longValue();
        createdUserIds.add(hiredUserId);
        assertThat(onboard).containsEntry("duplicated", false);
        assertThat(countBySql("SELECT COUNT(*) FROM sys_user WHERE deleted = 0")).as("入职后 sys_user +1")
                .isEqualTo(usersBefore + 1);
        Map<String, Object> candidate = dataOf(get(admin, "/api/recruit/candidates/" + candidateId));
        assertThat(candidate).containsEntry("currentStage", "hired");
        assertThat(((Number) candidate.get("hiredUserId")).longValue()).isEqualTo(hiredUserId);
        assertThat(candidate.get("hireTime")).isNotNull();
        assertThat(String.valueOf(dataOf(get(admin, "/api/recruit/offers/" + offerId)).get("status")))
                .isEqualTo("onboarded");

        // R7 幂等：重复入职返回同一用户，不再新增 sys_user
        Map<String, Object> second = dataOf(post(admin, "/api/recruit/offers/" + offerId + "/onboard", Map.of()));
        assertThat(second).containsEntry("duplicated", true);
        assertThat(((Number) second.get("userId")).longValue()).isEqualTo(hiredUserId);
        assertThat(countBySql("SELECT COUNT(*) FROM sys_user WHERE deleted = 0")).isEqualTo(usersBefore + 1);

        // 职位漏斗计数
        Map<String, Object> jobAfter = dataOf(get(admin, "/api/recruit/jobs/" + jobId));
        assertThat(intOf(jobAfter, "candidateCount")).isEqualTo(1);
        assertThat(intOf(jobAfter, "hiredCount")).isEqualTo(1);
        assertThat(fileId).isNotNull();
    }

    // ------------------------------------------------------------------
    // ② R1 查重 / R6 列表脱敏
    // ------------------------------------------------------------------

    @Test
    void candidateDedupAndListMasking() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long jobId = createRecruitingJob(admin, suffix, 5L);
        String phone = uniquePhone();
        String email = "alice" + suffix + "@example.com";

        Map<String, Object> first = dataOf(post(admin, "/api/recruit/candidates",
                candidateBody(jobId, "Alice" + suffix, phone, email, "referral")));
        Long candidateId = ((Number) first.get("id")).longValue();
        createdCandidateIds.add(candidateId);
        assertThat(first).containsEntry("duplicated", false);

        // 同手机号 + 同邮箱：识别为同一候选人，返回已有记录（R1）
        Map<String, Object> duplicate = dataOf(post(admin, "/api/recruit/candidates",
                candidateBody(jobId, "Alice 重复投递", phone, email, "website")));
        assertThat(duplicate).containsEntry("duplicated", true);
        assertThat(((Number) duplicate.get("id")).longValue()).isEqualTo(candidateId);

        // 同手机号不同邮箱：查重键不同 → 允许新建
        Map<String, Object> another = dataOf(post(admin, "/api/recruit/candidates",
                candidateBody(jobId, "Alice 另一邮箱", phone, "another" + suffix + "@example.com", "referral")));
        assertThat(another).containsEntry("duplicated", false);
        createdCandidateIds.add(((Number) another.get("id")).longValue());

        // 列表默认脱敏（R6）
        Map<String, Object> page = dataOf(get(admin, "/api/recruit/candidates?jobId=" + jobId));
        List<Map<String, Object>> rows = listValue(page.get("rows"));
        Map<String, Object> row = rows.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == candidateId).findFirst().orElseThrow();
        assertThat(String.valueOf(row.get("phone"))).isEqualTo(maskPhone(phone));
        assertThat(String.valueOf(row.get("email"))).isEqualTo(maskEmail(email));
        // 详情返回完整联系方式（详情受 recruit:candidate:list 权限保护）
        Map<String, Object> detail = dataOf(get(admin, "/api/recruit/candidates/" + candidateId));
        assertThat(String.valueOf(detail.get("phone"))).isEqualTo(phone);
        assertThat(String.valueOf(detail.get("email"))).isEqualTo(email);
    }

    // ------------------------------------------------------------------
    // ③ 看板：未登记口径拒绝出数 + 登记后漏斗手算一致（2.1.5）
    // ------------------------------------------------------------------

    @Test
    void dashboardRequiresMetricRegistrationAndMatchesHandCounts() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // 独立部门 + DEPT 数据范围账号：保证看板只统计本用例数据（手算可核对）
        Long deptId = createDept(admin, suffix);
        TestUser scopeUser = createUser(deptId, 3L, suffix + "-scope");
        HttpHeaders scoped = bearerHeaders(login(scopeUser.username(), "admin123"));

        Long jobId = createRecruitingJobForDept(admin, suffix, deptId, 1L);
        String phone = uniquePhone();
        Long candidateId = createCandidate(admin, jobId, "看板候选人" + suffix, phone,
                "board" + suffix + "@example.com", "referral");
        advanceStage(admin, candidateId, "screening", null);
        advanceStage(admin, candidateId, "interview", null);
        Long interviewId = scheduleInterview(admin, candidateId, "HR 面", "2026-11-12 10:00");
        completeInterview(admin, interviewId, "pass");
        submitFeedback(admin, interviewId);
        Long offerId = createOffer(admin, candidateId, 18000);
        submitOfferApproval(admin, offerId, 1L);
        decideOfferApproval(admin, offerId, true);
        sendOffer(admin, offerId);
        respondOffer(admin, offerId, true);
        Map<String, Object> onboard = dataOf(post(admin, "/api/recruit/offers/" + offerId + "/onboard", Map.of()));
        createdUserIds.add(((Number) onboard.get("userId")).longValue());

        // 先清空看板指标登记 → 看板拒绝出数（未登记口径不得上线）
        jdbcTemplate.update("DELETE FROM insight_metric WHERE metric_code LIKE 'recruit\\_%'");
        ResponseEntity<Map<String, Object>> blocked = get(scoped,
                "/api/recruit/dashboard/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(blocked.getBody().get("message")))
                .contains("未在 M13 指标字典登记").contains("register-metrics");

        // 登记口径（经 M13 Service 写入 M13 指标字典，category=cross，draft）
        Map<String, Object> registration = dataOf(post(admin, "/api/recruit/dashboard/register-metrics", Map.of()));
        assertThat(intOf(registration, "registeredCount")).isEqualTo(4);
        List<Map<String, Object>> metricRows = listValue(registration.get("metrics"));
        assertThat(metricRows).extracting(row -> row.get("metricCode")).contains(
                "recruit_funnel", "recruit_channel_effect", "recruit_time_to_hire", "recruit_offer_acceptance_rate");
        assertThat(metricRows).allSatisfy(row -> {
            assertThat(row).containsEntry("registered", true);
            assertThat(row.get("formula")).isNotNull();
            assertThat(row.get("missingPolicy")).isNotNull();
        });
        // M13 字典侧可查（口径登记到 M13，而不是 M08 自说自话）
        assertThat(countBySql("SELECT COUNT(*) FROM insight_metric WHERE metric_code LIKE 'recruit\\_%' "
                + "AND metric_category = 'cross' AND deleted = 0")).isEqualTo(4);

        // 登记后出数：漏斗 / 渠道 / 周期 / 接受率与手算一致
        Map<String, Object> overview = dataOf(get(scoped,
                "/api/recruit/dashboard/overview?startDate=" + RANGE_START + "&endDate=" + RANGE_END));
        Map<String, Object> summary = mapValue(overview.get("summary"));
        assertThat(longOf(summary, "jobCount")).isEqualTo(1);
        assertThat(longOf(summary, "candidateCount")).isEqualTo(1);
        assertThat(longOf(summary, "interviewCandidateCount")).isEqualTo(1);
        assertThat(longOf(summary, "offerCandidateCount")).isEqualTo(1);
        assertThat(longOf(summary, "hiredCount")).isEqualTo(1);

        List<Map<String, Object>> funnel = listValue(overview.get("funnel"));
        assertThat(funnel).extracting(row -> row.get("stage"))
                .containsExactly("candidates", "interview", "offer", "hired");
        assertThat(funnel).extracting(row -> longOf(row, "value")).containsExactly(1L, 1L, 1L, 1L);
        assertThat(decimalValue(funnel.get(1), "conversionRate")).as("面试/投递 = 100%").isEqualTo(100.0);
        assertThat(decimalValue(funnel.get(3), "conversionRate")).as("入职/Offer = 100%").isEqualTo(100.0);

        List<Map<String, Object>> channels = listValue(overview.get("channels"));
        Map<String, Object> referral = channels.stream()
                .filter(row -> "referral".equals(row.get("channel"))).findFirst().orElseThrow();
        assertThat(longOf(referral, "candidateCount")).isEqualTo(1);
        assertThat(longOf(referral, "offerCount")).isEqualTo(1);
        assertThat(longOf(referral, "hiredCount")).isEqualTo(1);
        assertThat(decimalValue(referral, "hiredRate")).isEqualTo(100.0);

        Map<String, Object> hireCycle = mapValue(overview.get("hireCycle"));
        assertThat(longOf(hireCycle, "hiredCount")).isEqualTo(1);
        assertThat(decimalValue(hireCycle, "avgDays")).as("当日入库当日入职 = 0 天").isZero();

        Map<String, Object> offerStats = mapValue(overview.get("offerStats"));
        assertThat(longOf(offerStats, "offerCount")).isEqualTo(1);
        assertThat(longOf(offerStats, "acceptedCount")).isEqualTo(1);
        assertThat(decimalValue(offerStats, "acceptanceRate")).isEqualTo(100.0);

        Map<String, Object> metrics = mapValue(overview.get("metrics"));
        assertThat(metrics).containsKeys("recruit_funnel", "recruit_channel_effect",
                "recruit_time_to_hire", "recruit_offer_acceptance_rate");
        assertThat(String.valueOf(mapValue(metrics.get("recruit_time_to_hire")).get("dictionaryStatus")))
                .as("跨域指标登记为 draft（完整上线依赖 M13 7.4）").isEqualTo("draft");
    }

    // ------------------------------------------------------------------
    // ④ 状态机与审批守卫（R5 / R3）
    // ------------------------------------------------------------------

    @Test
    void stateMachineAndApprovalGuards() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long jobId = createJobDraft(admin, suffix, 5L);

        // 审批中不能编辑
        submitJobApproval(admin, jobId, 2L);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("jobTitle", "审批中改名");
        ResponseEntity<Map<String, Object>> editResponse = put(admin, "/api/recruit/jobs/" + jobId, edit);
        assertThat(editResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(editResponse.getBody().get("message"))).contains("审批中");

        // 非指定审批人不能审批（审批人 = chensy/2）
        ResponseEntity<Map<String, Object>> wrongApprover = decideJobApproval(admin, jobId, true);
        assertThat(wrongApprover.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(wrongApprover.getBody().get("message"))).contains("无权审批");
        // 指定审批人登录后可审批
        HttpHeaders approver = bearerHeaders(login("chensy", "admin123"));
        assertThat(decideJobApproval(approver, jobId, true).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 候选人非法阶段跳转被拒（投递 → Offer）
        Long candidateId = createCandidate(admin, jobId, "状态机候选人" + suffix, uniquePhone(),
                "sm" + suffix + "@example.com", "website");
        ResponseEntity<Map<String, Object>> illegalStage = put(admin,
                "/api/recruit/candidates/" + candidateId + "/stage", Map.of("stage", "offer"));
        assertThat(illegalStage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(illegalStage.getBody().get("message"))).contains("不允许");

        // 淘汰必须写原因（R5 留痕）
        ResponseEntity<Map<String, Object>> rejectWithoutReason = put(admin,
                "/api/recruit/candidates/" + candidateId + "/stage", Map.of("stage", "rejected"));
        assertThat(rejectWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejectWithoutReason.getBody().get("message"))).contains("原因");

        // Offer 未发送不能登记候选人答复
        Long jobId2 = createRecruitingJob(admin, suffix + "-b", 5L);
        Long candidate2 = createCandidate(admin, jobId2, "Offer候选人" + suffix, uniquePhone(),
                "offer" + suffix + "@example.com", "agency");
        advanceStage(admin, candidate2, "screening", null);
        advanceStage(admin, candidate2, "interview", null);
        Long offerId = createOffer(admin, candidate2, 15000);
        submitOfferApproval(admin, offerId, 1L);
        decideOfferApproval(admin, offerId, true);
        ResponseEntity<Map<String, Object>> respondBeforeSend = put(admin,
                "/api/recruit/offers/" + offerId + "/respond", Map.of("accepted", true));
        assertThat(respondBeforeSend.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(respondBeforeSend.getBody().get("message"))).contains("不允许");
    }

    // ------------------------------------------------------------------
    // ⑤ 边界硬约束：不新建流程表/文件表/用户表
    // ------------------------------------------------------------------

    @Test
    void schemaBoundaryNoFlowFileUserTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        // 复用 M03 的 4 张流程表，M08 不新增流程表
        assertThat(tables).contains("flow_definition", "flow_instance", "flow_node_instance", "flow_task");
        assertThat(tables.stream().filter(name -> name.startsWith("flow_")).count()).isEqualTo(4);
        // M08 自有 7 张表
        assertThat(tables).contains("recruit_job", "recruit_candidate", "recruit_resume", "recruit_interview",
                "recruit_interview_feedback", "recruit_offer_template", "recruit_offer");
        // 不得出现 recruit_ 前缀的流程/文件/用户表（复用 M03 flow_* / G-1 sys_file / M00 sys_user）
        assertThat(tables).noneMatch(name -> name.startsWith("recruit_")
                && (name.contains("flow") || name.contains("approval") || name.contains("file")
                || name.contains("user") || name.contains("resume_file")));
        // 文件存储表归 M00：sys_file 存在且被 M08 只以 file_id 引用
        assertThat(tables).contains("sys_file");

        // 候选人不复制 sys_user 的账号/昵称字段（name_snapshot 是外部候选人报名姓名快照）
        List<String> candidateColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'recruit_candidate'", String.class);
        assertThat(candidateColumns).contains("name_snapshot", "hired_user_id");
        assertThat(candidateColumns).doesNotContain("username", "nickname", "password");
    }

    // ------------------------------------------------------------------
    // 造数辅助（全部走真实 HTTP）
    // ------------------------------------------------------------------

    private Long createJobDraft(HttpHeaders headers, String suffix, Long deptId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobCode", "IT-M08-J-" + suffix);
        body.put("jobTitle", "后端工程师" + suffix);
        body.put("jobDesc", "负责招聘模块后端开发（JD 测试数据）");
        body.put("deptId", deptId);
        body.put("headcount", 1);
        body.put("jobLevel", "P6");
        body.put("salaryRange", "25k-35k");
        body.put("city", "上海");
        body.put("ownerId", 1L);
        Long jobId = idOf(post(headers, "/api/recruit/jobs", body));
        createdJobIds.add(jobId);
        return jobId;
    }

    /** 建职位 → 提交需求审批 → 审批通过（招聘中），返回职位 ID。 */
    private Long createRecruitingJob(HttpHeaders headers, String suffix, Long deptId) {
        return createRecruitingJobForDept(headers, suffix, deptId, 1L);
    }

    private Long createRecruitingJobForDept(HttpHeaders headers, String suffix, Long deptId, Long approverId) {
        Long jobId = createJobDraft(headers, suffix, deptId);
        submitJobApproval(headers, jobId, approverId);
        ResponseEntity<Map<String, Object>> approval = decideJobApproval(headers, jobId, true);
        assertThat(approval.getStatusCode()).isEqualTo(HttpStatus.OK);
        return jobId;
    }

    private void submitJobApproval(HttpHeaders headers, Long jobId, Long approverId) {
        ResponseEntity<Map<String, Object>> response = post(headers,
                "/api/recruit/jobs/" + jobId + "/submit-approval", Map.of("approverId", approverId));
        assertThat(response.getStatusCode()).as("提交需求审批失败：%s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> decideJobApproval(HttpHeaders headers, Long jobId, boolean approved) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approved", approved);
        body.put("opinion", approved ? "同意招聘" : "暂缓招聘");
        return put(headers, "/api/recruit/jobs/" + jobId + "/approval", body);
    }

    private Map<String, Object> candidateBody(Long jobId, String name, String phone, String email, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("nameSnapshot", name);
        body.put("phone", phone);
        body.put("email", email);
        body.put("sourceChannel", channel);
        body.put("tags", "后端,Java");
        return body;
    }

    private Long createCandidate(HttpHeaders headers, Long jobId, String name, String phone, String email,
                                 String channel) {
        Map<String, Object> data = dataOf(post(headers, "/api/recruit/candidates",
                candidateBody(jobId, name, phone, email, channel)));
        Long candidateId = ((Number) data.get("id")).longValue();
        createdCandidateIds.add(candidateId);
        return candidateId;
    }

    private void advanceStage(HttpHeaders headers, Long candidateId, String stage, String rejectReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stage", stage);
        if (rejectReason != null) {
            body.put("rejectReason", rejectReason);
        }
        assertThat(put(headers, "/api/recruit/candidates/" + candidateId + "/stage", body).getStatusCode())
                .as("候选人应可推进到 " + stage).isEqualTo(HttpStatus.OK);
    }

    private Long uploadResume(HttpHeaders headers, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> data = dataOf(restTemplate.exchange("/api/system/files", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), MAP_TYPE));
        Long fileId = ((Number) data.get("id")).longValue();
        createdFileIds.add(fileId);
        return fileId;
    }

    private Long attachResume(HttpHeaders headers, Long candidateId, Long fileId, String fileName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        body.put("fileName", fileName);
        return idOf(post(headers, "/api/recruit/candidates/" + candidateId + "/resumes", body));
    }

    private Long scheduleInterview(HttpHeaders headers, Long candidateId, String roundName, String time) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateId", candidateId);
        body.put("roundName", roundName);
        body.put("interviewerId", 1L);
        body.put("scheduledTime", time);
        body.put("durationMinutes", 60);
        body.put("location", "线上会议");
        Long interviewId = idOf(post(headers, "/api/recruit/interviews", body));
        createdInterviewIds.add(interviewId);
        return interviewId;
    }

    private void completeInterview(HttpHeaders headers, Long interviewId, String result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "completed");
        body.put("result", result);
        body.put("remark", "面试完成，给出结论");
        assertThat(put(headers, "/api/recruit/interviews/" + interviewId, body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void submitFeedback(HttpHeaders headers, Long interviewId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dimensions", List.of(
                Map.of("dimensionCode", "skill", "dimensionName", "技术能力", "score", 85, "comment", "基础扎实，项目经验匹配"),
                Map.of("dimensionCode", "communication", "dimensionName", "沟通协作", "score", 80, "comment", "表达清晰，能主动对齐")
        ));
        assertThat(post(headers, "/api/recruit/interviews/" + interviewId + "/feedbacks", body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private Long defaultTemplateId(HttpHeaders headers) {
        List<Map<String, Object>> templates = listOf(get(headers, "/api/recruit/offer-templates?status=enabled"));
        Map<String, Object> seeded = templates.stream()
                .filter(row -> "std_offer_v1".equals(row.get("templateCode"))).findFirst()
                .orElseGet(() -> templates.get(0));
        return ((Number) seeded.get("id")).longValue();
    }

    private Long createOffer(HttpHeaders headers, Long candidateId, int salary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateId", candidateId);
        body.put("templateId", defaultTemplateId(headers));
        body.put("salary", salary);
        body.put("expectedOnboardDate", "2026-12-01");
        Long offerId = idOf(post(headers, "/api/recruit/offers", body));
        createdOfferIds.add(offerId);
        return offerId;
    }

    private void submitOfferApproval(HttpHeaders headers, Long offerId, Long approverId) {
        assertThat(post(headers, "/api/recruit/offers/" + offerId + "/submit-approval",
                Map.of("approverId", approverId)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void decideOfferApproval(HttpHeaders headers, Long offerId, boolean approved) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approved", approved);
        body.put("opinion", approved ? "薪酬符合预算，同意发放" : "薪酬超预算，驳回");
        assertThat(put(headers, "/api/recruit/offers/" + offerId + "/approval", body).getStatusCode())
                .as("Offer 审批应可处理").isEqualTo(HttpStatus.OK);
    }

    private void sendOffer(HttpHeaders headers, Long offerId) {
        assertThat(post(headers, "/api/recruit/offers/" + offerId + "/send", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void respondOffer(HttpHeaders headers, Long offerId, boolean accepted) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        if (!accepted) {
            body.put("reason", "候选人选择其他机会");
        }
        assertThat(put(headers, "/api/recruit/offers/" + offerId + "/respond", body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** 建独立测试部门（system API），供看板数据范围用例使用。 */
    private Long createDept(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", 0L);
        body.put("name", "M08招聘测试部" + suffix);
        body.put("sort", 99);
        body.put("status", 1);
        Long deptId = idOf(post(headers, "/api/system/depts", body));
        createdDeptIds.add(deptId);
        return deptId;
    }

    // ------------------------------------------------------------------
    // 测试用户（JDBC 直插，密码哈希复用 admin；数据范围用例用 role 3=DEPT）
    // ------------------------------------------------------------------

    private record TestUser(Long id, String username) {
    }

    private TestUser createUser(Long deptId, Long roleId, String suffix) {
        String username = "m08_it_" + suffix + "_" + SEQ.incrementAndGet();
        String passwordHash = jdbcTemplate.queryForObject("SELECT password FROM sys_user WHERE id = 1", String.class);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by) "
                            + "VALUES (?, ?, ?, ?, 1, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, "M08测试用户" + suffix);
            statement.setLong(4, deptId);
            return statement;
        }, keyHolder);
        Long userId = keyHolder.getKey().longValue();
        createdUserIds.add(userId);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return new TestUser(userId, username);
    }

    // ------------------------------------------------------------------
    // 自清理（本次新增硬要求：测试不向开发库留残留）
    // ------------------------------------------------------------------

    @AfterEach
    void cleanup() {
        // 1) G-1 文件：先释放 M08 的引用计数，再删除元数据与磁盘文件
        for (Long fileId : createdFileIds) {
            try {
                fileService.decrementRef(fileId);
            } catch (RuntimeException ignored) {
                // 文件可能已删除
            }
            try {
                fileService.delete(fileId);
            } catch (RuntimeException ignored) {
                // 引用未归零/已删除时忽略，避免掩盖断言错误
            }
        }
        // 2) 招聘自有表（按依赖顺序）
        deleteByIds("DELETE FROM recruit_interview_feedback WHERE interview_id IN (%s)", createdInterviewIds);
        deleteByIds("DELETE FROM recruit_interview WHERE id IN (%s)", createdInterviewIds);
        deleteByIds("DELETE FROM recruit_resume WHERE candidate_id IN (%s)", createdCandidateIds);
        deleteByIds("DELETE FROM recruit_offer WHERE id IN (%s)", createdOfferIds);
        deleteByIds("DELETE FROM recruit_candidate WHERE id IN (%s)", createdCandidateIds);
        deleteByIds("DELETE FROM recruit_job WHERE id IN (%s)", createdJobIds);
        deleteByIds("DELETE FROM recruit_offer_template WHERE id IN (%s)", createdTemplateIds);
        // 3) 复用的 M03 流程实例（只删本用例创建的业务单据上的实例/待办/轨迹）
        cleanupFlowInstances();
        // 4) M13 指标字典里由招聘看板登记的口径（测试数据不留库）
        try {
            jdbcTemplate.update("DELETE FROM insight_metric WHERE metric_code LIKE 'recruit\\_%'");
        } catch (RuntimeException ignored) {
            // 表不存在时忽略
        }
        // 5) 入职产生的用户 / 测试用户 / 临建部门
        deleteByIds("DELETE FROM sys_user_role WHERE user_id IN (%s)", createdUserIds);
        deleteByIds("DELETE FROM sys_user WHERE id IN (%s)", createdUserIds);
        deleteByIds("DELETE FROM sys_dept WHERE id IN (%s)", createdDeptIds);

        createdJobIds.clear();
        createdCandidateIds.clear();
        createdInterviewIds.clear();
        createdOfferIds.clear();
        createdTemplateIds.clear();
        createdUserIds.clear();
        createdDeptIds.clear();
        createdFileIds.clear();
    }

    private void cleanupFlowInstances() {
        List<Long> jobIds = new ArrayList<>(createdJobIds);
        List<Long> offerIds = new ArrayList<>(createdOfferIds);
        if (jobIds.isEmpty() && offerIds.isEmpty()) {
            return;
        }
        String instanceFilter = "SELECT id FROM flow_instance WHERE instance_type IN ('job_approval', 'offer_approval')"
                + " AND (biz_id IN (%s))";
        try {
            if (!jobIds.isEmpty()) {
                Object[] args = jobIds.toArray();
                String marks = placeholders(jobIds.size());
                jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN ("
                        + String.format(instanceFilter, marks) + ")", args);
                jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN ("
                        + String.format(instanceFilter, marks) + ")", args);
                jdbcTemplate.update("DELETE FROM flow_instance WHERE instance_type = 'job_approval' AND biz_id IN ("
                        + marks + ")", args);
            }
            if (!offerIds.isEmpty()) {
                Object[] args = offerIds.toArray();
                String marks = placeholders(offerIds.size());
                jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN ("
                        + String.format(instanceFilter, marks) + ")", args);
                jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN ("
                        + String.format(instanceFilter, marks) + ")", args);
                jdbcTemplate.update("DELETE FROM flow_instance WHERE instance_type = 'offer_approval' AND biz_id IN ("
                        + marks + ")", args);
            }
        } catch (RuntimeException ignored) {
            // 清理失败不掩盖真实断言错误
        }
    }

    private void deleteByIds(String sqlTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.update(String.format(sqlTemplate, placeholders(ids.size())), ids.toArray());
        } catch (RuntimeException ignored) {
            // 清理失败不掩盖真实断言错误
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    // ------------------------------------------------------------------
    // HTTP / JSON 辅助
    // ------------------------------------------------------------------

    private HttpHeaders adminHeaders() {
        return bearerHeaders(login("admin", "admin123"));
    }

    private String login(String username, String password) {
        return TestAuthClient.login(restTemplate, username, password);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<Map<String, Object>> get(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> post(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> put(HttpHeaders headers, String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        assertThat(id).as("响应应包含 id").isInstanceOf(Number.class);
        return ((Number) id).longValue();
    }

    private long countBySql(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private int intOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).intValue();
    }

    private long longOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private double decimalValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    private String uniquePhone() {
        long seq = SEQ.incrementAndGet();
        return "139" + String.format("%08d", seq % 100_000_000L);
    }

    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
