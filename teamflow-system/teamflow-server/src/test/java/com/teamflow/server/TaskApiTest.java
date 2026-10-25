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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M03 任务与工作流（阶段一）接口集成测试：任务 CRUD / 指派认领 / 状态机 / 轨迹 / 单节点审批。
 *
 * <p>全部走真实 HTTP（{@code RANDOM_PORT} + {@code TestRestTemplate} + 真实 JWT 登录），
 * 覆盖 M03 RULE §八 DoD 中与「任务侧」相关的四条：</p>
 * <ol>
 *   <li>创建 → 指派 → 流转到完成，全程轨迹可查（flow_instance + flow_node_instance）；</li>
 *   <li>非法状态迁移被拒绝且错误信息明确（唯一判定入口 TaskStatusMachine）；</li>
 *   <li>子任务完成度正确汇总到父任务（且 isRoot=false）；</li>
 *   <li>无责任人任务只允许存在于待认领池（R4），认领后才进入待办。</li>
 * </ol>
 * 另覆盖线性单节点审批骨架（flow_definition + flow_instance + flow_task）与状态机导出接口。
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class TaskApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    /** 唯一的标题/编码后缀，避免与既有数据或其他用例冲突（Windows 时钟分辨率粗，不能用 nanoTime 当唯一键）。 */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------------------------
    // ① 创建 → 流转到完成，全程轨迹可查；R3 事件在真实事务内发布（不抛错、不影响主流程）
    // ------------------------------------------------------------------

    @Test
    void createAssignAndTransitionProducesFullTrace() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();

        Long taskId = createTask(headers, "轨迹任务" + suffix, 1L, null, null);
        Map<String, Object> created = taskData(headers, taskId);
        assertThat(created).containsEntry("status", "todo").containsEntry("statusLabel", "待办");
        assertThat(created).containsEntry("assigneeId", 1).containsEntry("isRoot", true);

        transition(headers, taskId, "in_progress", "开始处理");
        transition(headers, taskId, "done", "已完成");

        Map<String, Object> done = taskData(headers, taskId);
        assertThat(done).containsEntry("status", "done").containsEntry("statusLabel", "已完成");
        assertThat(done.get("doneTime")).as("完成时间应被写入").isNotNull();

        ResponseEntity<Map<String, Object>> traceResponse = get(headers, "/api/task/tasks/" + taskId + "/traces");
        assertThat(traceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) traceResponse.getBody().get("data");
        assertThat(traces).as("创建 + 两次流转 = 至少 3 条轨迹").hasSizeGreaterThanOrEqualTo(3);
        assertThat(traces.get(0)).containsEntry("nodeKey", "create").containsEntry("nodeStatus", "completed");
        assertThat(traces.get(1)).containsEntry("fromStatus", "todo").containsEntry("toStatus", "in_progress")
                .containsEntry("operatorName", "admin").containsEntry("opinion", "开始处理");
        assertThat(traces.get(2)).containsEntry("fromStatus", "in_progress").containsEntry("toStatus", "done")
                .containsEntry("opinion", "已完成");
        traces.forEach(row -> assertThat(row.get("operatedTime")).as("轨迹必须记录时间").isNotNull());
    }

    // ------------------------------------------------------------------
    // ② 非法迁移被拒绝，错误信息明确（错误文案来自唯一状态机）
    // ------------------------------------------------------------------

    @Test
    void illegalTransitionIsRejectedWithClearMessage() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "非法流转任务" + suffix, 1L, null, null);

        // 待办 → 已完成：状态机不允许（必须先进入进行中）
        ResponseEntity<Map<String, Object>> response = put(headers,
                "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", "done"));
        assertThat(response.getStatusCode()).as("非法迁移必须被拒绝").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", 400);
        String message = String.valueOf(response.getBody().get("message"));
        assertThat(message).as("错误信息必须明确到前后状态").contains("不允许").contains("待办").contains("已完成")
                .contains("进行中");

        // 未知状态值也必须被拒绝
        ResponseEntity<Map<String, Object>> unknown = put(headers,
                "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", "archived"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(unknown.getBody().get("message"))).contains("不合法");

        // 任务状态没有被半个事务改坏
        assertThat(taskData(headers, taskId)).containsEntry("status", "todo");
    }

    // ------------------------------------------------------------------
    // ③ 子任务完成度汇总到父任务；子任务 isRoot=false 且 rootTaskId 指向顶层
    // ------------------------------------------------------------------

    @Test
    void subtaskCompletionAggregatesToParentAndIsNotRoot() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long parentId = createTask(headers, "父任务" + suffix, 1L, null, null);
        Long childId = createTask(headers, "子任务" + suffix, 1L, parentId, null);

        Map<String, Object> parentBefore = taskData(headers, parentId);
        assertThat(parentBefore).containsEntry("subTaskCount", 1).containsEntry("doneSubTaskCount", 0);
        assertThat(decimalOf(parentBefore, "subProgress")).isEqualTo(0.0);

        Map<String, Object> child = taskData(headers, childId);
        assertThat(child).containsEntry("taskType", "subtask").containsEntry("isRoot", false);
        assertThat(longOf(child, "parentTaskId")).isEqualTo(parentId);
        assertThat(longOf(child, "rootTaskId")).isEqualTo(parentId);

        transition(headers, childId, "in_progress", null);
        transition(headers, childId, "done", "子任务完成");

        Map<String, Object> parentAfter = taskData(headers, parentId);
        assertThat(parentAfter).containsEntry("doneSubTaskCount", 1);
        assertThat(decimalOf(parentAfter, "subProgress")).as("1/1 子任务完成 = 100").isEqualTo(100.0);
        assertThat(parentAfter).as("父任务不因汇总被自动完成").containsEntry("status", "todo");

        // 有子任务的任务不允许删除（避免子任务成为孤儿）
        ResponseEntity<Map<String, Object>> deleteResponse = delete(headers, "/api/task/tasks/" + parentId);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(deleteResponse.getBody().get("message"))).contains("子任务");
    }

    // ------------------------------------------------------------------
    // ④ 无责任人任务只存在于待认领池（R4）；认领后进入待办
    // ------------------------------------------------------------------

    @Test
    void unassignedTaskOnlyLivesInClaimPool() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "待认领任务" + suffix, null, null, null);

        Map<String, Object> task = taskData(headers, taskId);
        assertThat(task).containsEntry("status", "pool").containsEntry("statusLabel", "待认领");
        assertThat(task.get("assigneeId")).isNull();

        // 待认领池可通过关键词查到
        Map<String, Object> pool = pageData(headers, "/api/task/tasks/pool?pageSize=100&keyword=待认领任务" + suffix);
        assertThat(pageTotal(pool)).isEqualTo(1);

        // 无责任人不得离开待认领池：pool → todo 在状态机上合法，但被 R4 责任人规则拦住
        ResponseEntity<Map<String, Object>> illegal = put(headers,
                "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", "todo"));
        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(illegal.getBody().get("message"))).contains("待认领池");

        // 认领：pool → todo，责任人变为当前登录用户（admin id=1）
        ResponseEntity<Map<String, Object>> claim = put(headers, "/api/task/tasks/" + taskId + "/claim", null);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).containsEntry("status", "todo").containsEntry("assigneeId", 1);

        // 认领后即离开待认领池
        Map<String, Object> poolAfter = pageData(headers,
                "/api/task/tasks/pool?pageSize=100&keyword=待认领任务" + suffix);
        assertThat(pageTotal(poolAfter)).as("认领后不再出现在待认领池").isZero();
    }

    // ------------------------------------------------------------------
    // ⑤ 指派 / 转派：责任人变更并留痕；收回指派退回待认领池
    // ------------------------------------------------------------------

    @Test
    void assignTransfersOwnerAndRecordsTrace() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "转派任务" + suffix, 1L, null, null);

        ResponseEntity<Map<String, Object>> assignResponse = put(headers,
                "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 2, "remark", "交给陈思远"));
        assertThat(assignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> assigned = taskData(headers, taskId);
        assertThat(assigned).containsEntry("assigneeId", 2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) assigned.get("assignees");
        assertThat(assignees).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", 2).containsEntry("assignType", "owner");
        });

        // 指派留痕：轨迹里出现 assign 节点
        List<Map<String, Object>> traces = traceList(headers, taskId);
        assertThat(traces).anySatisfy(row -> assertThat(row).containsEntry("nodeKey", "assign"));

        // 收回指派 → 退回待认领池，且状态机不允许再非法流转
        put(headers, "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 0));
        Map<String, Object> unassigned = taskData(headers, taskId);
        assertThat(unassigned).containsEntry("status", "pool");
        assertThat(unassigned.get("assigneeId")).isNull();
    }

    // ------------------------------------------------------------------
    // ⑥ 线性单节点审批：发起 → 审批通过 → 复用状态流转（状态、轨迹、实例均正确）
    // ------------------------------------------------------------------

    @Test
    void singleNodeApprovalDrivesStatusTransition() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "审批任务" + suffix, 1L, null, null);

        Map<String, Object> approvalRequest = new LinkedHashMap<>();
        approvalRequest.put("targetStatus", "in_progress");
        approvalRequest.put("approverId", 1);
        approvalRequest.put("opinion", "申请开始处理");
        ResponseEntity<Map<String, Object>> startResponse = post(headers,
                "/api/task/tasks/" + taskId + "/approvals", approvalRequest);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long instanceId = idOf(startResponse);

        // 审批未通过前，任务状态不变
        assertThat(taskData(headers, taskId)).containsEntry("status", "todo");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("approved", true);
        decision.put("opinion", "同意开始");
        ResponseEntity<Map<String, Object>> approveResponse = put(headers,
                "/api/task/flow/instances/" + instanceId + "/approve", decision);
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).as("审批通过后复用状态流转").containsEntry("status", "in_progress");

        Map<String, Object> instanceDetail = dataOf(get(headers, "/api/task/flow/instances/" + instanceId));
        assertThat(instanceDetail).containsEntry("status", "completed").containsEntry("instanceType", "approval");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) instanceDetail.get("nodes");
        assertThat(nodes).anySatisfy(row -> assertThat(row)
                .containsEntry("nodeKey", "approve").containsEntry("nodeStatus", "completed")
                .containsEntry("toStatus", "in_progress"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flowTasks = (List<Map<String, Object>>) instanceDetail.get("flowTasks");
        assertThat(flowTasks).anySatisfy(row -> assertThat(row).containsEntry("status", "approved"));

        // 重复审批必须被拒绝
        ResponseEntity<Map<String, Object>> again = put(headers,
                "/api/task/flow/instances/" + instanceId + "/approve", decision);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(again.getBody().get("message"))).contains("不能重复审批");
    }

    // ------------------------------------------------------------------
    // ⑦ 状态机与流程定义：唯一来源可查询；默认两个流程定义已种子化
    // ------------------------------------------------------------------

    @Test
    void statusMachineAndDefinitionsAreExposedAsData() {
        HttpHeaders headers = adminHeaders();

        Map<String, Object> machine = dataOf(get(headers, "/api/task/flow/status-machine"));
        assertThat(machine).containsEntry("definitionCode", "task_status_linear");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) machine.get("states");
        assertThat(states).hasSize(5);
        assertThat(states).extracting(row -> row.get("code"))
                .containsExactly("pool", "todo", "in_progress", "done", "closed");
        @SuppressWarnings("unchecked")
        Map<String, Object> transitions = (Map<String, Object>) machine.get("transitions");
        assertThat((List<String>) transitions.get("pool")).containsExactly("todo");
        assertThat((List<String>) transitions.get("todo")).contains("in_progress", "closed");
        assertThat((List<String>) transitions.get("closed")).as("已关闭为终态").isEmpty();

        Map<String, Object> definitions = pageData(headers,
                "/api/task/flow/definitions?pageSize=100&keyword=task_");
        assertThat(pageRows(definitions)).anySatisfy(row ->
                assertThat(row).containsEntry("defCode", "task_status_linear"));
        assertThat(pageRows(definitions)).anySatisfy(row ->
                assertThat(row).containsEntry("defCode", "task_approve_single"));

        // 流程定义可新增（线性节点清单，本期不做 BPMN 编排）
        String code = "it_approve_" + nextSuffix();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defCode", code);
        body.put("defName", "集成测试审批流");
        body.put("businessType", "task_approve");
        body.put("nodesJson", "[{\"key\":\"approve\",\"name\":\"审批\"}]");
        ResponseEntity<Map<String, Object>> created = post(headers, "/api/task/flow/definitions", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageRows(pageData(headers, "/api/task/flow/definitions?pageSize=100&keyword=" + code)))
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // 请求与断言辅助
    // ------------------------------------------------------------------

    private Long createTask(HttpHeaders headers, String title, Long assigneeId, Long parentTaskId, Long krId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M03 集成测试任务");
        body.put("priority", "high");
        body.put("dueDate", "2099-12-31");
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        if (parentTaskId != null) {
            body.put("parentTaskId", parentTaskId);
        }
        if (krId != null) {
            body.put("krId", krId);
        }
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/task/tasks", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private void transition(HttpHeaders headers, Long taskId, String toStatus, String opinion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toStatus", toStatus);
        if (opinion != null) {
            body.put("opinion", opinion);
        }
        ResponseEntity<Map<String, Object>> response = put(headers, "/api/task/tasks/" + taskId + "/status", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> taskData(HttpHeaders headers, Long taskId) {
        return dataOf(get(headers, "/api/task/tasks/" + taskId));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> traceList(HttpHeaders headers, Long taskId) {
        ResponseEntity<Map<String, Object>> response = get(headers, "/api/task/tasks/" + taskId + "/traces");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestAuthClient.login(restTemplate, "admin", "admin123"));
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
    private Map<String, Object> pageData(HttpHeaders headers, String url) {
        return dataOf(get(headers, url));
    }

    private List<Map<String, Object>> pageRows(Map<String, Object> page) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        return rows;
    }

    private long pageTotal(Map<String, Object> page) {
        return ((Number) page.get("total")).longValue();
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

    private long longOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertThat(value).as("字段 %s 应为数字", key).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }
}
