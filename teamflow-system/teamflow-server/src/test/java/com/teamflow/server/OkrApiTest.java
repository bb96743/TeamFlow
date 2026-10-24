package com.teamflow.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M02 目标与 OKR 集成测试：覆盖 1.1.1 目标周期与目标拆解、1.1.5 对齐度检查骨架，
 * 以及 M02 RULE R1/R2/R4/R5/R6 与 L0 §9.1 D-2026-10-25-03 进度口径。
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class OkrApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void periodObjectiveAndKeyResultChainPersistsProgressTrace() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);

        Long objectiveId = createObjective(headers, periodId, 0L, "公司目标" + suffix, "company", null, 1L, 100, "active");
        Long quantitativeKrId = createKr(headers, objectiveId, krBody("量化 KR" + suffix, "quantitative", 100, 40));
        Long taskKrId = createKr(headers, objectiveId, krBody("任务 KR" + suffix, "task_driven", null, null));

        // 量化型正向：40/100 = 40%；任务驱动型：无关联任务时 0 且标「待关联」
        Map<String, Object> quantitativeKr = dataOf(get(headers, "/api/okr/objectives/" + objectiveId + "/krs/" + quantitativeKrId));
        assertThat(decimalOf(quantitativeKr, "progress")).isEqualTo(40.0);
        assertThat(quantitativeKr).containsEntry("progressSource", "quantitative");
        Map<String, Object> taskKr = dataOf(get(headers, "/api/okr/objectives/" + objectiveId + "/krs/" + taskKrId));
        assertThat(decimalOf(taskKr, "progress")).isEqualTo(0.0);
        assertThat(taskKr).containsEntry("taskLinkStatus", "unlinked");

        // 更新当前值：按公式重算（80/100 = 80%）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentValue", 80);
        ResponseEntity<Map<String, Object>> progressResponse = put(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + quantitativeKrId + "/progress", body);
        assertThat(progressResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> progressData = dataOf(progressResponse);
        assertThat(decimalOf(progressData, "progress")).isEqualTo(80.0);
        assertThat(progressData).containsEntry("progressSource", "quantitative");

        // 人工校准必须写原因，来源记为 manual
        Map<String, Object> manualBody = new LinkedHashMap<>();
        manualBody.put("progress", 65);
        manualBody.put("reason", "负责人校准：数据口径调整");
        Map<String, Object> manualData = dataOf(put(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + quantitativeKrId + "/progress", manualBody));
        assertThat(decimalOf(manualData, "progress")).isEqualTo(65.0);
        assertThat(manualData).containsEntry("progressSource", "manual");

        // R1 进度留痕：至少 创建初始化 / 当前值更新 / 人工校准 三条，最新一条为 manual
        List<Map<String, Object>> logs = listOf(get(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + quantitativeKrId + "/progress-logs"));
        assertThat(logs).hasSizeGreaterThanOrEqualTo(3);
        assertThat(logs.get(0)).containsEntry("progressSource", "manual");
        assertThat(decimalOf(logs.get(0), "toProgress")).isEqualTo(65.0);

        // 目标进度由 KR 归集：(65 + 0) / 2 = 32.5
        Map<String, Object> objective = dataOf(get(headers, "/api/okr/objectives/" + objectiveId));
        assertThat(decimalOf(objective, "progress")).isEqualTo(32.5);
        assertThat(objective).containsEntry("krCount", 2);

        // 三层归属：部门目标挂到公司目标下，权重 100 才能置为进行中
        Long deptObjectiveId = createObjective(headers, periodId, objectiveId, "部门目标" + suffix, "dept", 6L, 2L, 100, "active");
        List<Map<String, Object>> tree = listOf(get(headers, "/api/okr/objectives/tree?periodId=" + periodId));
        assertThat(tree).isNotEmpty();
        assertThat(tree.get(0).get("id")).isEqualTo(objectiveId.intValue());
        assertThat(childrenOf(tree.get(0))).extracting(row -> row.get("id")).contains(deptObjectiveId.intValue());

        // R6 对齐：子目标 → 父目标
        Map<String, Object> alignBody = new LinkedHashMap<>();
        alignBody.put("parentObjectiveId", objectiveId);
        alignBody.put("alignType", "parent");
        ResponseEntity<Map<String, Object>> alignResponse = post(headers,
                "/api/okr/objectives/" + deptObjectiveId + "/aligns", alignBody);
        assertThat(alignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> aligns = listOf(get(headers, "/api/okr/objectives/" + deptObjectiveId + "/aligns"));
        assertThat(aligns).hasSize(1);
        assertThat(aligns.get(0).get("parentObjectiveId")).isEqualTo(objectiveId.intValue());

        // 1.1.5 对齐度检查：部门目标无 KR 应被检出；无承接人目标为空
        Map<String, Object> alignCheck = dataOf(get(headers, "/api/okr/align-check?periodId=" + periodId));
        assertThat(idsOf(alignCheck.get("unownedObjectives"))).isEmpty();
        assertThat(idsOf(alignCheck.get("objectivesWithoutKr"))).containsExactly(deptObjectiveId.intValue());
        assertThat((List<?>) alignCheck.get("tasksWithoutObjective")).isEmpty();
        assertThat(String.valueOf(alignCheck.get("tasksWithoutObjectiveNote"))).contains("M03");

        // 周期列表可检索到本周期
        Map<String, Object> periods = dataOf(get(headers, "/api/okr/periods?pageNum=1&pageSize=10&keyword=" + suffix));
        assertThat(rowsOf(periods)).extracting(row -> row.get("id")).contains(periodId.intValue());
    }

    @Test
    void siblingWeightMustSumToHundredBeforeActivation() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long parentId = createObjective(headers, periodId, 0L, "父目标" + suffix, "company", null, 1L, 100, "active");

        Long childA = createObjective(headers, periodId, parentId, "子目标A" + suffix, "dept", 6L, 1L, 60, "draft");
        createObjective(headers, periodId, parentId, "子目标B" + suffix, "dept", 6L, 1L, 30, "draft");

        // 权重和 90% ≠ 100%，进行中被拒绝
        ResponseEntity<Map<String, Object>> activateA = changeObjectiveStatus(headers, periodId, parentId, childA, "active");
        assertThat(activateA.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(activateA.getBody().get("message"))).contains("100%");

        // 调整到 100% 后可置为进行中
        Map<String, Object> updateB = new LinkedHashMap<>();
        updateB.put("weight", 40);
        assertThat(put(headers, "/api/okr/objectives/" + objectiveIdOfSibling(headers, periodId, parentId, "子目标B" + suffix), updateB)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changeObjectiveStatus(headers, periodId, parentId, childA, "active").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 已满 100% 后再加 50% 子目标被拒绝（合计不得超过 100%）
        Map<String, Object> extra = objectiveBody(periodId, parentId, "子目标C" + suffix, "dept", 6L, 1L, 50, "draft");
        ResponseEntity<Map<String, Object>> createExtra = post(headers, "/api/okr/objectives", extra);
        assertThat(createExtra.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(createExtra.getBody().get("message"))).contains("100%");
    }

    @Test
    void archivedPeriodIsReadOnly() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveId = createObjective(headers, periodId, 0L, "归档前目标" + suffix, "company", null, 1L, 100, "active");
        Long krId = createKr(headers, objectiveId, krBody("归档前 KR" + suffix, "quantitative", 100, 10));

        ResponseEntity<Map<String, Object>> archive = put(headers, "/api/okr/periods/" + periodId + "/status",
                Map.of("status", "archived"));
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> updateObjective = new LinkedHashMap<>();
        updateObjective.put("objectiveTitle", "归档后篡改标题");
        ResponseEntity<Map<String, Object>> objectiveEdit = put(headers, "/api/okr/objectives/" + objectiveId, updateObjective);
        assertThat(objectiveEdit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(objectiveEdit.getBody().get("message"))).contains("归档");

        Map<String, Object> krUpdate = new LinkedHashMap<>();
        krUpdate.put("krTitle", "归档后篡改 KR");
        ResponseEntity<Map<String, Object>> krEdit = put(headers,
                "/api/okr/objectives/" + objectiveId + "/krs/" + krId, krUpdate);
        assertThat(krEdit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(krEdit.getBody().get("message"))).contains("归档");

        ResponseEntity<Map<String, Object>> createInArchived = post(headers, "/api/okr/objectives",
                objectiveBody(periodId, 0L, "归档后新目标" + suffix, "company", null, 1L, 0, "draft"));
        assertThat(createInArchived.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(createInArchived.getBody().get("message"))).contains("归档");

        ResponseEntity<Map<String, Object>> periodEdit = put(headers, "/api/okr/periods/" + periodId,
                Map.of("periodName", "改名" + suffix));
        assertThat(periodEdit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delete(headers, "/api/okr/periods/" + periodId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void alignCycleIsRejected() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);
        Long objectiveA = createObjective(headers, periodId, 0L, "目标A" + suffix, "company", null, 1L, 100, "active");
        Long objectiveB = createObjective(headers, periodId, objectiveA, "目标B" + suffix, "dept", 6L, 1L, 0, "draft");
        Long objectiveC = createObjective(headers, periodId, objectiveA, "目标C" + suffix, "dept", 7L, 1L, 0, "draft");

        assertThat(createAlign(headers, objectiveB, objectiveA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createAlign(headers, objectiveC, objectiveB).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A → C 会形成 A→C→B→A 环
        ResponseEntity<Map<String, Object>> cycle = createAlign(headers, objectiveA, objectiveC);
        assertThat(cycle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(cycle.getBody().get("message"))).contains("环");

        ResponseEntity<Map<String, Object>> self = createAlign(headers, objectiveA, objectiveA);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(self.getBody().get("message"))).contains("自身");
    }

    @Test
    void objectiveWithoutOwnerStaysDraftAndShowsInAlignCheck() {
        HttpHeaders headers = adminHeaders();
        String suffix = uniqueSuffix();
        Long periodId = createActivePeriod(headers, suffix);

        Map<String, Object> activeWithoutOwner = objectiveBody(periodId, 0L, "无承接人目标" + suffix, "company", null, null, 0, "active");
        ResponseEntity<Map<String, Object>> rejected = post(headers, "/api/okr/objectives", activeWithoutOwner);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message"))).contains("草稿");

        Map<String, Object> draftWithoutOwner = objectiveBody(periodId, 0L, "草稿无承接人" + suffix, "company", null, null, 0, "draft");
        Long draftId = idOf(post(headers, "/api/okr/objectives", draftWithoutOwner));
        assertThat(draftId).isNotNull();

        Map<String, Object> alignCheck = dataOf(get(headers, "/api/okr/align-check?periodId=" + periodId));
        assertThat(idsOf(alignCheck.get("unownedObjectives"))).containsExactly(draftId.intValue());
        assertThat(idsOf(alignCheck.get("objectivesWithoutKr"))).containsExactly(draftId.intValue());
    }

    @Test
    void okrPermissionsAreEnforcedOnEveryEndpoint() {
        HttpHeaders headers = bearerHeaders(login("chensy", "admin123"));
        assertThat(get(headers, "/api/okr/periods").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(headers, "/api/okr/objectives").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(headers, "/api/okr/align-check").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(headers, "/api/okr/periods", Map.of("periodName", "越权" + uniqueSuffix())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- 请求辅助 ----------

    private Map<String, Object> krBody(String title, String type, Integer targetValue, Integer currentValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("krTitle", title);
        body.put("krType", type);
        if (targetValue != null) {
            body.put("targetValue", targetValue);
            body.put("metricUnit", "%");
            body.put("direction", "positive");
        }
        if (currentValue != null) {
            body.put("currentValue", currentValue);
        }
        return body;
    }

    private Map<String, Object> objectiveBody(
            Long periodId, Long parentId, String title, String level, Long deptId, Long ownerId, Integer weight, String status
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodId", periodId);
        body.put("parentId", parentId);
        body.put("objectiveTitle", title);
        body.put("objectiveDesc", "集成测试目标");
        body.put("level", level);
        if (deptId != null) {
            body.put("deptId", deptId);
        }
        if (ownerId != null) {
            body.put("ownerId", ownerId);
        }
        body.put("weight", weight);
        body.put("status", status);
        return body;
    }

    private Long createActivePeriod(HttpHeaders headers, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodName", "IT-OKR-" + suffix);
        body.put("periodType", "quarter");
        body.put("startDate", "2026-10-01");
        body.put("endDate", "2026-12-31");
        body.put("sort", 1);
        body.put("remark", "集成测试周期");
        Long periodId = idOf(post(headers, "/api/okr/periods", body));
        assertThat(periodId).isNotNull();
        assertThat(put(headers, "/api/okr/periods/" + periodId + "/status", Map.of("status", "active")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return periodId;
    }

    private Long createObjective(
            HttpHeaders headers, Long periodId, Long parentId, String title,
            String level, Long deptId, Long ownerId, Integer weight, String status
    ) {
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/okr/objectives",
                objectiveBody(periodId, parentId, title, level, deptId, ownerId, weight, status));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private Long createKr(HttpHeaders headers, Long objectiveId, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> response = post(headers,
                "/api/okr/objectives/" + objectiveId + "/krs", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private ResponseEntity<Map<String, Object>> changeObjectiveStatus(
            HttpHeaders headers, Long periodId, Long parentId, Long objectiveId, String status
    ) {
        Map<String, Object> existing = dataOf(get(headers, "/api/okr/objectives/" + objectiveId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objectiveTitle", existing.get("objectiveTitle"));
        body.put("level", existing.get("level"));
        if (existing.get("deptId") != null) {
            body.put("deptId", existing.get("deptId"));
        }
        if (existing.get("ownerId") != null) {
            body.put("ownerId", existing.get("ownerId"));
        }
        body.put("parentId", parentId);
        body.put("weight", existing.get("weight"));
        body.put("status", status);
        return put(headers, "/api/okr/objectives/" + objectiveId, body);
    }

    private Long objectiveIdOfSibling(HttpHeaders headers, Long periodId, Long parentId, String titlePart) {
        Map<String, Object> page = dataOf(get(headers,
                "/api/okr/objectives?pageNum=1&pageSize=100&periodId=" + periodId));
        return rowsOf(page).stream()
                .filter(row -> parentId.equals(((Number) row.get("parentId")).longValue()))
                .filter(row -> String.valueOf(row.get("objectiveTitle")).contains(titlePart))
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到子目标：" + titlePart));
    }

    private ResponseEntity<Map<String, Object>> createAlign(HttpHeaders headers, Long childId, Long parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentObjectiveId", parentId);
        body.put("alignType", "parent");
        return post(headers, "/api/okr/objectives/" + childId + "/aligns", body);
    }

    // ---------- HTTP 与断言辅助 ----------

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

    private String login(String username, String password) {
        return TestAuthClient.login(restTemplate, username, password);
    }

    private HttpHeaders adminHeaders() {
        return bearerHeaders(login("admin", "admin123"));
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String uniqueSuffix() {
        return String.valueOf(System.nanoTime());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> body) {
        assertThat(body).containsEntry("code", 200);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", 200);
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> childrenOf(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    private List<Integer> idsOf(Object rows) {
        assertThat(rows).isInstanceOf(List.class);
        return ((List<?>) rows).stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).intValue())
                .toList();
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }

    private double decimalOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }
}
