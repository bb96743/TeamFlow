package com.teamflow.server;

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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M04 团队与成员（阶段一）接口集成测试：团队 CRUD / 成员生命周期 / 我参与的团队 / 成员负载 / 边界。
 *
 * <p>全部走真实 HTTP（{@code RANDOM_PORT} + 真实 JWT 登录）。重点覆盖：</p>
 * <ol>
 *   <li>建团队 → 加成员 → 设 OWNER → 移出成员：历史行不删、{@code leave_time} 有值（R3）；</li>
 *   <li>「我参与的团队」按 {@code leave_time IS NULL} 筛选，移出后不再返回但详情历史仍在；</li>
 *   <li><b>DoD 第 4 条</b>：团队角色变更后 {@code sys_user_role} 零变化，且该用户 {@code /api/auth/me}
 *       的权限集合与变更前完全一致（团队角色与系统授权正交，D-2026-10-26-08 结论二）；</li>
 *   <li>成员负载来自 M03 只读端口（{@code TaskPerfQueryService#memberTaskLoads}），M04 不直连 {@code task_*}；</li>
 *   <li>一人可属多团队；同团队重复加入被拒；团队选项已登录即可访问；解散需团队 OWNER 且保留成员历史。</li>
 * </ol>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class TeamApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // ① 团队 CRUD + 成员生命周期（R3：移出不删行）
    // ------------------------------------------------------------------

    @Test
    void teamCrudAndMemberHistory() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long teamId = createTeam(headers, "团队" + suffix, 2L);

        Map<String, Object> detail = teamData(headers, teamId);
        assertThat(detail).containsEntry("teamName", "团队" + suffix).containsEntry("status", "active")
                .containsEntry("statusLabel", "正常");
        assertThat(((Number) detail.get("ownerId")).longValue()).isEqualTo(2L);
        assertThat(((Number) detail.get("memberCount")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) detail.get("members");
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("userId", 2).containsEntry("teamRole", "OWNER")
                .containsEntry("teamRoleLabel", "负责人");

        // 添加成员（观察者）
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("userId", 3);
        add.put("teamRole", "VIEWER");
        add.put("remark", "观察者加入");
        ResponseEntity<Map<String, Object>> added = post(headers, "/api/team/teams/" + teamId + "/members", add);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long memberId = idOf(added);
        assertThat(teamData(headers, teamId)).containsEntry("memberCount", 2);

        // 变更角色 VIEWER → MEMBER（R6 @OperLog）
        ResponseEntity<Map<String, Object>> changed = put(headers,
                "/api/team/teams/" + teamId + "/members/" + memberId + "/role", Map.of("teamRole", "MEMBER"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findMember(membersOf(headers, teamId, false), memberId))
                .containsEntry("teamRole", "MEMBER").containsEntry("teamRoleLabel", "成员");

        // 移出成员：只写 leave_time，不删行
        assertThat(delete(headers, "/api/team/teams/" + teamId + "/members/" + memberId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> afterLeave = teamData(headers, teamId);
        assertThat(afterLeave).containsEntry("memberCount", 1);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ?", Integer.class, teamId);
        assertThat(rowCount).as("R3：移出不删行").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND user_id = 3 AND leave_time IS NOT NULL",
                Integer.class, teamId)).as("leave_time 必须写值").isEqualTo(1);

        // 历史行仍可查（团队详情的历史查询）
        List<Map<String, Object>> history = membersOf(headers, teamId, true);
        assertThat(history).hasSize(2);
        assertThat(findMember(history, memberId)).containsEntry("active", false);
        assertThat(findMember(history, memberId).get("leaveTime")).isNotNull();
    }

    // ------------------------------------------------------------------
    // ② 「我参与的团队」= leave_time IS NULL
    // ------------------------------------------------------------------

    @Test
    void myTeamsFilterUsesActiveMembership() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        String teamName = "我的团队" + suffix;
        Long teamId = createTeam(headers, teamName, 1L);

        Map<String, Object> mine = pageData(headers,
                "/api/team/teams?mine=true&pageSize=100&keyword=" + teamName);
        assertThat(pageTotal(mine)).isEqualTo(1);

        Long memberId = ((Number) membersOf(headers, teamId, false).get(0).get("id")).longValue();
        assertThat(delete(headers, "/api/team/teams/" + teamId + "/members/" + memberId).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> mineAfter = pageData(headers,
                "/api/team/teams?mine=true&pageSize=100&keyword=" + teamName);
        assertThat(pageTotal(mineAfter)).as("移出后不再返回").isZero();
        // 但历史行仍在（团队详情的历史查询可见）
        assertThat(membersOf(headers, teamId, true)).hasSize(1);
        assertThat(membersOf(headers, teamId, true).get(0)).containsEntry("active", false);
    }

    // ------------------------------------------------------------------
    // ③ DoD 第 4 条：团队角色变更不影响系统权限（查库 + /api/auth/me 前后对比）
    // ------------------------------------------------------------------

    @Test
    void teamRoleChangeDoesNotAffectSystemAuth() {
        HttpHeaders adminHeaders = adminHeaders();
        HttpHeaders userHeaders = bearerHeaders(login("chensy", "admin123"));
        String suffix = nextSuffix();

        List<String> userRoleSnapshotBefore = sysUserRoleSnapshot();
        Map<String, Object> meBefore = dataOf(get(userHeaders, "/api/auth/me"));
        List<String> rolesBefore = stringList(meBefore.get("roles"));
        List<String> permissionsBefore = stringList(meBefore.get("permissions"));
        assertThat(permissionsBefore).isNotEmpty();

        Long teamId = createTeam(adminHeaders, "权限正交团队" + suffix, 1L);
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("userId", 2);
        add.put("teamRole", "MEMBER");
        Long memberId = idOf(post(adminHeaders, "/api/team/teams/" + teamId + "/members", add));

        // 把用户 2 设为团队 OWNER（团队内角色）
        assertThat(put(adminHeaders, "/api/team/teams/" + teamId + "/members/" + memberId + "/role",
                Map.of("teamRole", "OWNER")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 查库：团队角色确实变为 OWNER
        assertThat(jdbcTemplate.queryForObject(
                "SELECT team_role FROM team_member WHERE id = ?", String.class, memberId)).isEqualTo("OWNER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM team_team WHERE id = ?", Long.class, teamId)).isEqualTo(2L);

        // 查库：sys_user_role 零变化
        List<String> userRoleSnapshotAfter = sysUserRoleSnapshot();
        assertThat(userRoleSnapshotAfter).as("DoD 第 4 条：团队角色不得写入 sys_user_role")
                .containsExactlyElementsOf(userRoleSnapshotBefore);

        // /api/auth/me：角色与权限集合与变更前完全一致
        Map<String, Object> meAfter = dataOf(get(userHeaders, "/api/auth/me"));
        assertThat(stringList(meAfter.get("roles"))).as("系统角色不变").containsExactlyElementsOf(rolesBefore);
        assertThat(stringList(meAfter.get("permissions"))).as("系统权限集合完全一致")
                .containsExactlyElementsOf(permissionsBefore);
    }

    // ------------------------------------------------------------------
    // ④ 成员负载经 M03 只读端口获取
    // ------------------------------------------------------------------

    @Test
    void memberLoadComesFromM03Port() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long inProgressTask = createTask(headers, "负载任务-进行中" + suffix, 1L);
        createTask(headers, "负载任务-待办" + suffix, 1L);
        assertThat(put(headers, "/api/task/tasks/" + inProgressTask + "/status",
                Map.of("toStatus", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Long teamId = createTeam(headers, "负载团队" + suffix, 1L);
        List<Map<String, Object>> members = membersOf(headers, teamId, false);
        Map<String, Object> row = members.stream()
                .filter(item -> ((Number) item.get("userId")).longValue() == 1L)
                .findFirst().orElseThrow();

        assertThat(((Number) row.get("taskTotal")).longValue())
                .as("任务总数来自 M03 端口，至少包含刚建的 2 个顶层任务").isGreaterThanOrEqualTo(2L);
        assertThat(((Number) row.get("taskInProgress")).longValue())
                .as("进行中数来自 M03 端口").isGreaterThanOrEqualTo(1L);
        // 展示信息来自 sys_user 实时关联（不落库缓存）
        assertThat(row.get("nickname")).isNotNull();
        assertThat(members).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // ⑤ 一人可属多团队；同团队重复加入被拒
    // ------------------------------------------------------------------

    @Test
    void oneUserCanJoinMultipleTeamsAndDuplicateRejected() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long teamA = createTeam(headers, "多团队A-" + suffix, 1L);
        Long teamB = createTeam(headers, "多团队B-" + suffix, 1L);

        Integer activeTeams = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE user_id = 1 AND leave_time IS NULL AND deleted = 0",
                Integer.class);
        assertThat(activeTeams).as("同一用户可属多团队（R5）").isGreaterThanOrEqualTo(2);
        assertThat(teamA).isNotEqualTo(teamB);

        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("userId", 1);
        duplicate.put("teamRole", "MEMBER");
        ResponseEntity<Map<String, Object>> dup = post(headers, "/api/team/teams/" + teamA + "/members", duplicate);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(dup.getBody().get("message"))).contains("已在团队中");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND user_id = 1", Integer.class, teamA))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ⑥ 团队选项：已登录即可（无 team:* 权限码）
    // ------------------------------------------------------------------

    @Test
    void optionsAvailableToAnyLoggedInUser() {
        HttpHeaders adminHeaders = adminHeaders();
        String suffix = nextSuffix();
        Long teamId = createTeam(adminHeaders, "选项团队" + suffix, 1L);

        // 用户 2（chensy，仅有 sys_admin 角色，无 team:* 权限）也能取团队选项
        HttpHeaders userHeaders = bearerHeaders(login("chensy", "admin123"));
        ResponseEntity<Map<String, Object>> response = get(userHeaders, "/api/team/teams/options");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(options).anySatisfy(row -> {
            assertThat(((Number) row.get("id")).longValue()).isEqualTo(teamId);
            assertThat(String.valueOf(row.get("teamName"))).isEqualTo("选项团队" + suffix);
        });
        // 选项只返回启用团队
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_team WHERE id = ? AND status = 'active' AND deleted = 0",
                Integer.class, teamId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ⑦ 解散需团队 OWNER，且保留成员历史（R4）
    // ------------------------------------------------------------------

    @Test
    void dissolveRequiresOwnerRoleAndKeepsHistory() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long teamId = createTeam(headers, "待解散团队" + suffix, 2L);
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("userId", 3);
        add.put("teamRole", "MEMBER");
        post(headers, "/api/team/teams/" + teamId + "/members", add);

        // admin 有 team:team:remove 权限但不是团队 OWNER → 团队内校验拒绝
        ResponseEntity<Map<String, Object>> denied = delete(headers, "/api/team/teams/" + teamId);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(denied.getBody().get("message"))).contains("OWNER");

        // 设置 admin 为负责人后可解散
        assertThat(put(headers, "/api/team/teams/" + teamId + "/owner", Map.of("userId", 1))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delete(headers, "/api/team/teams/" + teamId).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> dissolved = jdbcTemplate.queryForMap(
                "SELECT status, deleted FROM team_team WHERE id = ?", teamId);
        assertThat(dissolved.get("status")).isEqualTo("dissolved");
        assertThat(((Number) dissolved.get("deleted")).intValue()).isEqualTo(1);
        Integer memberRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ?", Integer.class, teamId);
        assertThat(memberRows).as("R4：解散保留成员历史").isEqualTo(3);
        assertThat(get(headers, "/api/team/teams/" + teamId).getStatusCode())
                .as("解散后团队不可见").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // 请求与数据辅助
    // ------------------------------------------------------------------

    private Long createTeam(HttpHeaders headers, String name, Long ownerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamName", name);
        body.put("teamCode", "T-" + SEQ.incrementAndGet());
        body.put("teamDesc", "M04 集成测试团队");
        body.put("deptId", 5);
        body.put("ownerId", ownerId);
        body.put("status", "active");
        body.put("sort", 1);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/team/teams", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private Long createTask(HttpHeaders headers, String title, Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("priority", "normal");
        body.put("assigneeId", assigneeId);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/task/tasks", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private Map<String, Object> teamData(HttpHeaders headers, Long teamId) {
        return dataOf(get(headers, "/api/team/teams/" + teamId));
    }

    private List<Map<String, Object>> membersOf(HttpHeaders headers, Long teamId, boolean includeLeft) {
        ResponseEntity<Map<String, Object>> response = get(headers,
                "/api/team/teams/" + teamId + "/members?includeLeft=" + includeLeft);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("data");
        return rows;
    }

    private Map<String, Object> findMember(List<Map<String, Object>> members, Long memberId) {
        if (memberId == null) {
            return members.get(0);
        }
        return members.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == memberId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到成员 " + memberId));
    }

    /** {@code sys_user_role} 全表快照（按 user_id, role_id 排序），用于 DoD 第 4 条的"零变化"证明。 */
    private List<String> sysUserRoleSnapshot() {
        return jdbcTemplate.queryForList(
                "SELECT CONCAT(user_id, ':', role_id) FROM sys_user_role ORDER BY user_id, role_id", String.class);
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

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

    private Map<String, Object> pageData(HttpHeaders headers, String url) {
        return dataOf(get(headers, url));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageRows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    private long pageTotal(Map<String, Object> page) {
        return ((Number) page.get("total")).longValue();
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }
}
