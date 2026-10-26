package com.teamflow.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.teamflow.task.service.TaskStatsQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

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
 * M14 项目管理集成测试（需求 2.5.1「项目立项」，真实 HTTP + 真实 M02/M03 Service 链路）。
 *
 * <p><b>覆盖</b>：</p>
 * <ol>
 *   <li>立项 → 关联目标 → 加里程碑 → 状态流转全链路；详情返回目标标题/进度（**经 M02 服务实时读取**，
 *       M14 表内不存目标副本）；里程碑完成度按 M03 任务实时汇总并与手算一致；</li>
 *   <li>状态机非法迁移被拒（项目 draft→closed、archived 回退；里程碑 planned→completed 跳级）；</li>
 *   <li>R1 项目不得悬空：objectiveId 与 noObjectiveReason 至少一项；</li>
 *   <li>成员生命周期（添加/重复添加/移出/重新加入；负责人不可移出、不可经成员接口设置）。</li>
 * </ol>
 *
 * <p><b>测试自清理</b>：本用例创建的 M02 周期/目标/KR、M03 任务与状态流转留下的流程留痕、M14 项目/成员/里程碑
 * 均在 {@link #cleanup()} 中按跟踪 ID 删除，不向开发库留残留（延续 M08 的做法）。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class ProjectApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskStatsQueryService taskStatsQueryService;

    private final List<Long> createdPeriodIds = new ArrayList<>();
    private final List<Long> createdObjectiveIds = new ArrayList<>();
    private final List<Long> createdKrIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdProjectIds = new ArrayList<>();
    private final List<Long> createdDeptIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    // ------------------------------------------------------------------
    // ① 立项 → 关联目标 → 里程碑（M03 完成度）→ 状态流转 → 归档只读
    // ------------------------------------------------------------------

    @Test
    void projectInitiationEndToEnd() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long objectiveId = createObjectiveFixture(admin, suffix, "M14 项目目标" + suffix);
        Long krId = createQuantitativeKr(admin, objectiveId, 100, 40);

        // 立项（关联目标；负责人=admin；周期 2026 Q4）
        Long projectId = createProject(admin, "P-" + suffix, "M14 立项项目" + suffix, objectiveId, 1L, 5L,
                "2026-10-01", "2026-12-31");
        Map<String, Object> detail = dataOf(get(admin, "/api/project/projects/" + projectId));
        assertThat(detail).containsEntry("status", "draft");
        assertThat(((Number) detail.get("objectiveId")).longValue()).isEqualTo(objectiveId);
        Map<String, Object> objective = mapValue(detail.get("objective"));
        assertThat(objective.get("objectiveTitle")).isEqualTo("M14 项目目标" + suffix);
        assertThat(decimalValue(objective, "progress")).as("目标进度经 M02 服务实时读取").isEqualTo(40.0);
        assertThat(String.valueOf(detail.get("objectiveTitleSource"))).contains("M02");
        List<Map<String, Object>> members = listValue(detail.get("members"));
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("memberRole", "owner").containsEntry("userId", 1);
        assertThat(listValue(detail.get("milestones"))).isEmpty();
        Map<String, Object> progress = mapValue(detail.get("progress"));
        assertThat(progress.get("projectProgress")).isNull();

        // 状态流转 draft → active
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 里程碑：A 绑定 KR（完成度经 M03 汇总）；B 不绑定且计划完成日已过（延期 R7）
        Long milestoneA = createMilestone(admin, projectId, "里程碑A" + suffix, krId, "2026-10-01", "2026-12-31");
        Long milestoneB = createMilestone(admin, projectId, "里程碑B" + suffix, null, "2026-10-01", "2026-10-15");

        // M03 任务：KR 下 3 个顶层任务，2 个完成 → 完成率 66.67%
        Long task1 = createTask(admin, "M14任务1-" + suffix, krId, "2026-12-31");
        Long task2 = createTask(admin, "M14任务2-" + suffix, krId, "2026-12-31");
        createTask(admin, "M14任务3-" + suffix, krId, "2026-12-31");
        completeTask(admin, task1);
        completeTask(admin, task2);

        Map<String, Object> milestoneDetail = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneA));
        assertThat(milestoneDetail).containsEntry("krLinked", true);
        assertThat(longOf(milestoneDetail, "taskTotal")).isEqualTo(3);
        assertThat(longOf(milestoneDetail, "taskDone")).isEqualTo(2);
        assertThat(decimalValue(milestoneDetail, "completion")).as("完成度 = 2/3 = 66.67%（M03 数据）")
                .isEqualTo(66.67);
        assertThat(milestoneDetail).containsEntry("delayed", false);

        // 与 M03 任务列表同条件交叉核对（证明数据来自 M03，而非本模块快照）
        Map<String, Object> taskPage = dataOf(get(admin, "/api/task/tasks?krId=" + krId + "&pageSize=100"));
        List<Map<String, Object>> tasks = listValue(taskPage.get("rows"));
        long rootTasks = tasks.stream().filter(row -> row.get("parentTaskId") == null).count();
        long doneTasks = tasks.stream().filter(row -> row.get("parentTaskId") == null
                && "done".equals(row.get("status"))).count();
        assertThat(rootTasks).isEqualTo(3);
        assertThat(doneTasks).isEqualTo(2);

        // 未绑定 KR 的里程碑：完成度 null + 延期标记（R3/R7）
        Map<String, Object> milestoneBDetail = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneB));
        assertThat(milestoneBDetail).containsEntry("krLinked", false);
        assertThat(milestoneBDetail.get("completion")).isNull();
        assertThat(milestoneBDetail).containsEntry("delayed", true);
        assertThat(String.valueOf(milestoneBDetail.get("completionNote"))).contains("未绑定");

        // 项目完成度 = 已绑定里程碑的平均值（仅 A → 66.67%）
        Map<String, Object> afterMilestones = dataOf(get(admin, "/api/project/projects/" + projectId));
        Map<String, Object> projectProgress = mapValue(afterMilestones.get("progress"));
        assertThat(longOf(projectProgress, "milestoneCount")).isEqualTo(2);
        assertThat(longOf(projectProgress, "linkedMilestoneCount")).isEqualTo(1);
        assertThat(longOf(projectProgress, "delayedMilestoneCount")).isEqualTo(1);
        assertThat(decimalValue(projectProgress, "projectProgress")).isEqualTo(66.67);

        // 里程碑状态推进：planned → in_progress → completed（完成时自动补实际完成日期）
        Map<String, Object> inProgress = dataOf(put(admin,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneA,
                Map.of("status", "in_progress")));
        assertThat(inProgress).containsEntry("status", "in_progress");
        assertThat(inProgress.get("actualStartDate")).isNotNull();
        Map<String, Object> completed = dataOf(put(admin,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneA,
                Map.of("status", "completed")));
        assertThat(completed).containsEntry("status", "completed");
        assertThat(completed.get("actualEndDate")).isNotNull();

        // 状态流转 active → closed → archived；归档后只读
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "closed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "archived")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> archived = dataOf(get(admin, "/api/project/projects/" + projectId));
        assertThat(archived).containsEntry("status", "archived").containsEntry("editable", false);

        Map<String, Object> editBody = new LinkedHashMap<>();
        editBody.put("projectName", "归档后改名");
        ResponseEntity<Map<String, Object>> editRejected = put(admin,
                "/api/project/projects/" + projectId, editBody);
        assertThat(editRejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(editRejected.getBody().get("message"))).contains("只读");
        ResponseEntity<Map<String, Object>> milestoneRejected = post(admin,
                "/api/project/projects/" + projectId + "/milestones", Map.of("milestoneName", "归档后新增里程碑"));
        assertThat(milestoneRejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(milestoneRejected.getBody().get("message"))).contains("只读");
    }

    // ------------------------------------------------------------------
    // ② 状态机非法迁移被拒（项目 / 里程碑）
    // ------------------------------------------------------------------

    @Test
    void illegalStateTransitionsAreRejected() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long objectiveId = createObjectiveFixture(admin, suffix, "M14 状态机目标" + suffix);
        Long projectId = createProject(admin, "PS-" + suffix, "M14 状态机项目" + suffix, objectiveId, 1L, 5L,
                "2026-10-01", "2026-12-31");

        // draft → closed：跳跃被拒
        ResponseEntity<Map<String, Object>> jump = put(admin,
                "/api/project/projects/" + projectId + "/status", Map.of("status", "closed"));
        assertThat(jump.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(jump.getBody().get("message"))).contains("不允许");

        // draft → active → closed → archived 主线
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // active → archived：被拒（归档只能从结项进入）
        ResponseEntity<Map<String, Object>> skipClose = put(admin,
                "/api/project/projects/" + projectId + "/status", Map.of("status", "archived"));
        assertThat(skipClose.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "closed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // closed → active：回退被拒
        ResponseEntity<Map<String, Object>> reopen = put(admin,
                "/api/project/projects/" + projectId + "/status", Map.of("status", "active"));
        assertThat(reopen.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reopen.getBody().get("message")).asString().contains("不允许");
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "archived")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // archived → draft：终态不可逆
        ResponseEntity<Map<String, Object>> rollback = put(admin,
                "/api/project/projects/" + projectId + "/status", Map.of("status", "draft"));
        assertThat(rollback.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 里程碑：planned → completed 跳级被拒；planned → in_progress → completed 正常
        Long objectiveId2 = createObjectiveFixture(admin, suffix + "-b", "M14 里程碑目标" + suffix);
        Long projectId2 = createProject(admin, "PM-" + suffix, "M14 里程碑项目" + suffix, objectiveId2, 1L, 5L,
                "2026-10-01", "2026-12-31");
        assertThat(put(admin, "/api/project/projects/" + projectId2 + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Long milestoneId = createMilestone(admin, projectId2, "跳级里程碑" + suffix, null, "2026-10-01", "2026-12-31");
        ResponseEntity<Map<String, Object>> jumpMilestone = put(admin,
                "/api/project/projects/" + projectId2 + "/milestones/" + milestoneId,
                Map.of("status", "completed"));
        assertThat(jumpMilestone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(jumpMilestone.getBody().get("message"))).contains("不允许");
        assertThat(put(admin, "/api/project/projects/" + projectId2 + "/milestones/" + milestoneId,
                Map.of("status", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(admin, "/api/project/projects/" + projectId2 + "/milestones/" + milestoneId,
                Map.of("status", "completed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // completed 终态不可回退
        assertThat(put(admin, "/api/project/projects/" + projectId2 + "/milestones/" + milestoneId,
                Map.of("status", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // ③ R1 项目不得悬空；详情目标实时读取；M14 表无目标标题副本
    // ------------------------------------------------------------------

    @Test
    void objectiveLinkRequiredAndNoObjectiveCopyInTables() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();

        // 未关联目标且未填原因 → 拒绝（R1）
        Map<String, Object> dangling = new LinkedHashMap<>();
        dangling.put("projectCode", "PD-" + suffix);
        dangling.put("projectName", "悬空项目" + suffix);
        ResponseEntity<Map<String, Object>> rejected = post(admin, "/api/project/projects", dangling);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message")))
                .contains("R1").contains("至少一项");

        // 只填不关联原因 → 允许；详情 objective 为 null
        Map<String, Object> reasonOnly = new LinkedHashMap<>();
        reasonOnly.put("projectCode", "PR-" + suffix);
        reasonOnly.put("projectName", "无目标项目" + suffix);
        reasonOnly.put("noObjectiveReason", "公司级探索性项目，暂不设目标");
        reasonOnly.put("ownerId", 1L);
        Long projectId = idOf(post(admin, "/api/project/projects", reasonOnly));
        createdProjectIds.add(projectId);
        Map<String, Object> detail = dataOf(get(admin, "/api/project/projects/" + projectId));
        assertThat(detail.get("objective")).isNull();
        assertThat(detail).containsEntry("noObjectiveReason", "公司级探索性项目，暂不设目标");

        // 后续补关联目标：详情立即返回 M02 的目标标题与进度
        Long objectiveId = createObjectiveFixture(admin, suffix, "M14 补关联目标" + suffix);
        createQuantitativeKr(admin, objectiveId, 100, 40);
        assertThat(put(admin, "/api/project/projects/" + projectId,
                Map.of("objectiveId", objectiveId)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> linked = dataOf(get(admin, "/api/project/projects/" + projectId));
        Map<String, Object> objective = mapValue(linked.get("objective"));
        assertThat(objective.get("objectiveTitle")).isEqualTo("M14 补关联目标" + suffix);
        assertThat(decimalValue(objective, "progress")).isEqualTo(40.0);

        // 查库证明：M14 表内只有引用列，没有任何目标标题副本
        Integer projectTitleColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('proj_project', 'proj_milestone') "
                        + "AND (column_name LIKE '%objective_title%' OR column_name LIKE '%kr_title%' "
                        + "OR column_name LIKE '%task_title%' OR column_name LIKE '%title_snapshot%')",
                Integer.class);
        assertThat(projectTitleColumns).as("M14 表内不得有目标/KR/任务标题副本").isZero();
        List<String> projectColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'proj_project'", String.class);
        assertThat(projectColumns).contains("objective_id", "no_objective_reason");
        List<String> milestoneColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'proj_milestone'", String.class);
        assertThat(milestoneColumns).contains("kr_id", "plan_end_date", "actual_end_date");
        assertThat(milestoneColumns).doesNotContain("kr_title", "task_status");
        Long storedObjectiveId = jdbcTemplate.queryForObject(
                "SELECT objective_id FROM proj_project WHERE id = ?", Long.class, projectId);
        assertThat(storedObjectiveId).as("项目表只存目标 ID 引用").isEqualTo(objectiveId);
    }

    // ------------------------------------------------------------------
    // ④ 成员生命周期（负责人保护 / 重复添加 / 移出 / 重新加入）
    // ------------------------------------------------------------------

    @Test
    void memberLifecycleAndOwnerProtection() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long objectiveId = createObjectiveFixture(admin, suffix, "M14 成员目标" + suffix);
        Long projectId = createProject(admin, "PE-" + suffix, "M14 成员项目" + suffix, objectiveId, 1L, 5L,
                "2026-10-01", "2026-12-31");
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 添加参与人（chensy=2）
        Long memberId = idOf(post(admin, "/api/project/projects/" + projectId + "/members",
                Map.of("userId", 2, "memberRole", "member")));
        List<Map<String, Object>> members = listOf(get(admin,
                "/api/project/projects/" + projectId + "/members"));
        assertThat(members).hasSize(2);

        // 重复添加同一人 → 拒绝
        ResponseEntity<Map<String, Object>> duplicate = post(admin,
                "/api/project/projects/" + projectId + "/members", Map.of("userId", 2, "memberRole", "viewer"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(duplicate.getBody().get("message"))).contains("已在项目中");

        // 成员接口不能设置 owner（负责人由项目主数据维护）
        ResponseEntity<Map<String, Object>> ownerRole = post(admin,
                "/api/project/projects/" + projectId + "/members", Map.of("userId", 3, "memberRole", "owner"));
        assertThat(ownerRole.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(ownerRole.getBody().get("message"))).contains("负责人");

        // 移出参与人 → 列表只剩负责人；该行保留 leave_time（历史可追溯）
        assertThat(delete(admin, "/api/project/projects/" + projectId + "/members/" + memberId)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listOf(get(admin, "/api/project/projects/" + projectId + "/members"))).hasSize(1);
        Long leaveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proj_member WHERE project_id = ? AND user_id = 2 AND leave_time IS NOT NULL",
                Long.class, projectId);
        assertThat(leaveRows).as("移出只写 leave_time，不删行").isEqualTo(1);

        // 重新加入 → 复活同一行
        Long revivedId = idOf(post(admin, "/api/project/projects/" + projectId + "/members",
                Map.of("userId", 2, "memberRole", "viewer")));
        assertThat(revivedId).isEqualTo(memberId);
        assertThat(listOf(get(admin, "/api/project/projects/" + projectId + "/members"))).hasSize(2);

        // 移出负责人 → 拒绝
        List<Map<String, Object>> current = listOf(get(admin,
                "/api/project/projects/" + projectId + "/members"));
        Map<String, Object> ownerRow = current.stream()
                .filter(row -> "owner".equals(row.get("memberRole"))).findFirst().orElseThrow();
        ResponseEntity<Map<String, Object>> removeOwner = delete(admin,
                "/api/project/projects/" + projectId + "/members/" + ((Number) ownerRow.get("id")).longValue());
        assertThat(removeOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(removeOwner.getBody().get("message"))).contains("负责人");

        // 变更负责人 → 新旧负责人成员角色同步
        assertThat(put(admin, "/api/project/projects/" + projectId,
                Map.of("ownerId", 2)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> afterTransfer = dataOf(get(admin, "/api/project/projects/" + projectId));
        assertThat(((Number) afterTransfer.get("ownerId")).longValue()).isEqualTo(2);
        List<Map<String, Object>> transferred = listValue(afterTransfer.get("members"));
        Map<String, Object> newOwner = transferred.stream()
                .filter(row -> ((Number) row.get("userId")).longValue() == 2).findFirst().orElseThrow();
        assertThat(newOwner).containsEntry("memberRole", "owner");
    }

    // ------------------------------------------------------------------
    // ⑤ M14 口径修复：受限数据范围账号与管理员看到的里程碑完成度必须一致
    //    （D-2026-10-26-09 结论二：聚合端口刻意不叠加登录用户数据范围）
    // ------------------------------------------------------------------

    @Test
    void milestoneCompletionIsConsistentForRestrictedDataScope() {
        HttpHeaders admin = adminHeaders();
        String suffix = nextSuffix();
        Long objectiveId = createObjectiveFixture(admin, suffix, "M14 口径目标" + suffix);
        Long krId = createQuantitativeKr(admin, objectiveId, 100, 40);

        // 独立部门 + DEPT 数据范围账号（部门=项目部门 → 可看项目；任务归属 admin 部门 → 旧实现会因任务范围过滤而偏低）
        Long deptId = createDept(admin, suffix);
        TestUser restricted = createRestrictedUser(deptId, 3L, suffix);

        Long projectId = createProject(admin, "PSC-" + suffix, "受限口径项目" + suffix, objectiveId, 1L, deptId,
                "2026-10-01", "2026-12-31");
        assertThat(put(admin, "/api/project/projects/" + projectId + "/status",
                Map.of("status", "active")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Long milestoneId = createMilestone(admin, projectId, "口径里程碑" + suffix, krId, "2026-10-01", "2026-12-31");

        Long task1 = createTask(admin, "口径任务1-" + suffix, krId, "2026-12-31");
        Long task2 = createTask(admin, "口径任务2-" + suffix, krId, "2026-12-31");
        createTask(admin, "口径任务3-" + suffix, krId, "2026-12-31");
        completeTask(admin, task1);
        completeTask(admin, task2);

        // 管理员视角
        Map<String, Object> adminView = dataOf(get(admin,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneId));
        double adminCompletion = decimalValue(adminView, "completion");

        // DEPT 数据范围受限账号视角
        HttpHeaders restrictedHeaders = bearerHeaders(login(restricted.username(), "admin123"));
        Map<String, Object> restrictedView = dataOf(get(restrictedHeaders,
                "/api/project/projects/" + projectId + "/milestones/" + milestoneId));
        double restrictedCompletion = decimalValue(restrictedView, "completion");

        System.out.println("[M14口径对比] admin(ALL)=" + adminCompletion + ", restricted(DEPT, deptId=" + deptId + ")=" + restrictedCompletion);
        assertThat(adminCompletion).as("管理员视角完成度 = 2/3").isEqualTo(66.67);
        assertThat(restrictedCompletion)
                .as("受限数据范围账号与管理员看到的里程碑完成度必须一致（本次修复核心）")
                .isEqualTo(adminCompletion);
        assertThat(longOf(restrictedView, "taskTotal")).isEqualTo(3);
        assertThat(longOf(restrictedView, "taskDone")).isEqualTo(2);

        // 聚合端口：只返回 4 个聚合字段；且不叠加登录用户数据范围（测试线程无登录上下文，若叠加 scope 会返回 0/0）
        List<Map<String, Object>> stats = taskStatsQueryService.taskStatsByKrIds(List.of(krId));
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).keySet())
                .as("聚合端口不得返回任何任务明细字段")
                .containsExactlyInAnyOrder("krId", "taskTotal", "taskDone", "completionPercent");
        assertThat(longOf(stats.get(0), "taskTotal")).isEqualTo(3);
        assertThat(longOf(stats.get(0), "taskDone")).isEqualTo(2);
        assertThat(decimalValue(stats.get(0), "completionPercent")).isEqualTo(66.67);
    }
    // ------------------------------------------------------------------
    // 造数辅助（真实 HTTP：M02 目标/KR + M03 任务）
    // ------------------------------------------------------------------

    private Long createObjectiveFixture(HttpHeaders headers, String suffix, String title) {
        Map<String, Object> periodBody = new LinkedHashMap<>();
        periodBody.put("periodName", "IT-M14-P-" + suffix);
        periodBody.put("periodType", "quarter");
        periodBody.put("startDate", "2026-10-01");
        periodBody.put("endDate", "2026-12-31");
        Long periodId = idOf(post(headers, "/api/okr/periods", periodBody));
        createdPeriodIds.add(periodId);
        assertThat(put(headers, "/api/okr/periods/" + periodId + "/status", Map.of("status", "active"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> objectiveBody = new LinkedHashMap<>();
        objectiveBody.put("periodId", periodId);
        objectiveBody.put("parentId", 0L);
        objectiveBody.put("objectiveTitle", title);
        objectiveBody.put("level", "company");
        objectiveBody.put("ownerId", 1L);
        objectiveBody.put("weight", 100);
        objectiveBody.put("status", "active");
        Long objectiveId = idOf(post(headers, "/api/okr/objectives", objectiveBody));
        createdObjectiveIds.add(objectiveId);
        return objectiveId;
    }

    private Long createQuantitativeKr(HttpHeaders headers, Long objectiveId, int targetValue, int currentValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", "M14量化KR" + nextSuffix());
        body.put("krType", "quantitative");
        body.put("targetValue", targetValue);
        body.put("currentValue", currentValue);
        body.put("direction", "positive");
        body.put("ownerId", 1L);
        Long krId = idOf(post(headers, "/api/okr/objectives/" + objectiveId + "/krs", body));
        createdKrIds.add(krId);
        return krId;
    }

    private Long createTask(HttpHeaders headers, String title, Long krId, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M14 里程碑完成度测试任务");
        body.put("krId", krId);
        body.put("assigneeId", 1L);
        body.put("priority", "high");
        body.put("dueDate", dueDate);
        Long taskId = idOf(post(headers, "/api/task/tasks", body));
        createdTaskIds.add(taskId);
        return taskId;
    }

    private void completeTask(HttpHeaders headers, Long taskId) {
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status",
                Map.of("toStatus", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/status",
                Map.of("toStatus", "done")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long createProject(HttpHeaders headers, String code, String name, Long objectiveId,
                               Long ownerId, Long deptId, String startDate, String endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", code);
        body.put("projectName", name);
        body.put("projectDesc", "M14 立项测试项目");
        body.put("objectiveId", objectiveId);
        body.put("ownerId", ownerId);
        body.put("deptId", deptId);
        body.put("startDate", startDate);
        body.put("endDate", endDate);
        body.put("priority", "high");
        Long projectId = idOf(post(headers, "/api/project/projects", body));
        createdProjectIds.add(projectId);
        return projectId;
    }

    private Long createMilestone(HttpHeaders headers, Long projectId, String name, Long krId,
                                 String planStart, String planEnd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("milestoneName", name);
        body.put("milestoneDesc", "M14 里程碑测试");
        if (krId != null) {
            body.put("krId", krId);
        }
        body.put("planStartDate", planStart);
        body.put("planEndDate", planEnd);
        return idOf(post(headers, "/api/project/projects/" + projectId + "/milestones", body));
    }

    /** 建独立测试部门（system API），供数据范围用例使用。 */
    private Long createDept(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", 0L);
        body.put("name", "M14口径测试部" + suffix);
        body.put("sort", 98);
        body.put("status", 1);
        Long deptId = idOf(post(headers, "/api/system/depts", body));
        createdDeptIds.add(deptId);
        return deptId;
    }

    /** 建 DEPT 数据范围受限账号（JDBC 直插，密码哈希复用 admin；role 3 = 业务管理员 DEPT）。 */
    private TestUser createRestrictedUser(Long deptId, Long roleId, String suffix) {
        String username = "m03_it_" + suffix + "_" + SEQ.incrementAndGet();
        String passwordHash = jdbcTemplate.queryForObject("SELECT password FROM sys_user WHERE id = 1", String.class);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by) "
                            + "VALUES (?, ?, ?, ?, 1, 'system', 'system')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, "M03口径测试用户" + suffix);
            statement.setLong(4, deptId);
            return statement;
        }, keyHolder);
        Long userId = keyHolder.getKey().longValue();
        createdUserIds.add(userId);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return new TestUser(userId, username);
    }

    private record TestUser(Long id, String username) {
    }
    // ------------------------------------------------------------------
    // 自清理（M14 + 本用例造的 M02/M03 数据）
    // ------------------------------------------------------------------

    @AfterEach
    void cleanup() {
        try {
            if (!createdTaskIds.isEmpty()) {
                String taskMarks = placeholders(createdTaskIds.size());
                jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id IN ("
                        + "SELECT id FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + taskMarks + "))", createdTaskIds.toArray());
                jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id IN ("
                        + "SELECT id FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + taskMarks + "))", createdTaskIds.toArray());
                jdbcTemplate.update("DELETE FROM flow_instance WHERE instance_type = 'status' AND biz_id IN ("
                        + taskMarks + ")", createdTaskIds.toArray());
                deleteByIds("DELETE FROM task_assignee WHERE task_id IN (%s)", createdTaskIds);
                deleteByIds("DELETE FROM task_relation WHERE task_id IN (%s)", createdTaskIds);
                deleteByIds("DELETE FROM task_task WHERE id IN (%s)", createdTaskIds);
            }
            if (!createdKrIds.isEmpty()) {
                deleteByIds("DELETE FROM okr_kr_task_link WHERE kr_id IN (%s)", createdKrIds);
                deleteByIds("DELETE FROM okr_task_event_log WHERE kr_id IN (%s)", createdKrIds);
                deleteByIds("DELETE FROM okr_progress_log WHERE kr_id IN (%s)", createdKrIds);
                deleteByIds("DELETE FROM okr_key_result WHERE id IN (%s)", createdKrIds);
            }
            deleteByIds("DELETE FROM okr_objective WHERE id IN (%s)", createdObjectiveIds);
            deleteByIds("DELETE FROM okr_period WHERE id IN (%s)", createdPeriodIds);
            if (!createdProjectIds.isEmpty()) {
                deleteByIds("DELETE FROM proj_milestone WHERE project_id IN (%s)", createdProjectIds);
                deleteByIds("DELETE FROM proj_member WHERE project_id IN (%s)", createdProjectIds);
                deleteByIds("DELETE FROM proj_project WHERE id IN (%s)", createdProjectIds);
            }
            if (!createdUserIds.isEmpty()) {
                deleteByIds("DELETE FROM sys_user_role WHERE user_id IN (%s)", createdUserIds);
                deleteByIds("DELETE FROM sys_user WHERE id IN (%s)", createdUserIds);
            }
            deleteByIds("DELETE FROM sys_dept WHERE id IN (%s)", createdDeptIds);
        } catch (RuntimeException ignored) {
            // 清理失败不掩盖真实断言错误
        }
        createdPeriodIds.clear();
        createdObjectiveIds.clear();
        createdKrIds.clear();
        createdTaskIds.clear();
        createdProjectIds.clear();
        createdDeptIds.clear();
        createdUserIds.clear();
    }

    private void deleteByIds(String sqlTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update(String.format(sqlTemplate, placeholders(ids.size())), ids.toArray());
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

    private ResponseEntity<Map<String, Object>> delete(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), MAP_TYPE);
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
}
