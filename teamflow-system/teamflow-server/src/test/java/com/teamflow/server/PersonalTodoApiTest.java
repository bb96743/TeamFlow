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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M10 个人效率（阶段一）接口集成测试：待办收件箱 + 回写扩展点（D-2026-10-26-07）+ 工时 + 复盘 + 效能画像。
 *
 * <p>全部走真实 HTTP（{@code RANDOM_PORT} + 真实 JWT 登录），重点覆盖：</p>
 * <ol>
 *   <li><b>端到端</b>：M03 建任务并指派 → M10 收件箱出现 {@code task_assign} → 收件箱处理 → M03 任务状态真的变了；</li>
 *   <li><b>幂等（R2）</b>：重复投递未处理事项不产生第二条；</li>
 *   <li><b>R4（V15 修正后）</b>：已处理行冻结不可改、不可重开；重复投递视为新业务事件<b>可新建</b>
 *       （仅未处理唯一，历史行共存）；含"退回后重新派给同一人"的缺陷修复端到端验证；</li>
 *   <li><b>R3 强约束</b>：handler 返回 false（忽略/任务已删除）时待办**不关闭**；</li>
 *   <li><b>并发</b>：并发投递同一未处理事项，唯一键保证只有一条；</li>
 *   <li><b>未登记 handler 的 bizType</b>：允许投递、处理时拒绝；</li>
 *   <li><b>审批待办</b>：收件箱驱动 M03 审批决策；</li>
 *   <li><b>工时 R6</b>：无 task_id 被拒、按任务汇总正确；</li>
 *   <li><b>画像 R7</b>：只读实时聚合、无明细画像表。</li>
 * </ol>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class PersonalTodoApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // ① 端到端：M03 指派 → M10 收件箱 → 处理 → M03 任务状态真的变了
    // ------------------------------------------------------------------

    @Test
    void assignTodoAppearsAndHandleWritesBackToTask() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        String title = "收件箱端到端任务" + suffix;
        Long taskId = createTask(headers, title, 1L, null);

        // M03 指派时已通过统一投递入口写入 M10（M03 不允许直写 todo_item）
        Map<String, Object> todo = findOpenTodo(headers, "task_assign", taskId, "start");
        assertThat(todo).containsEntry("bizType", "task_assign").containsEntry("actionType", "start")
                .containsEntry("status", "open").containsEntry("statusLabel", "未处理")
                .containsEntry("handlerRegistered", true);
        assertThat(String.valueOf(todo.get("title"))).startsWith("任务指派：").contains(title);
        assertThat(String.valueOf(todo.get("actionUrl"))).contains("/task/list?taskId=" + taskId);

        Long todoId = longOf(todo, "id");
        ResponseEntity<Map<String, Object>> handleResponse = post(headers,
                "/api/personal/todos/" + todoId + "/handle", Map.of("action", "complete"));
        assertThat(handleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(handleResponse)).containsEntry("status", "handled");

        // R3 证据：M03 任务状态真的从 todo 变成了 in_progress
        Map<String, Object> task = taskData(headers, taskId);
        assertThat(task).as("收件箱处理必须回写 M03 任务状态").containsEntry("status", "in_progress");
        assertThat(todoStatus("task_assign", taskId, 1L, "start")).isEqualTo("handled");
        assertThat(handleLogCount(todoId, "closed")).isEqualTo(1);

        // 待办已处理，不再出现在「未处理 + 该业务单据」的查询结果里
        ResponseEntity<Map<String, Object>> openAfter = get(headers,
                "/api/personal/todos?status=open&bizType=task_assign&bizId=" + taskId);
        assertThat(pageRows(dataOf(openAfter))).isEmpty();
    }

    // ------------------------------------------------------------------
    // ② 幂等（R2）：重复投递不产生第二条、只刷新未处理行
    // ------------------------------------------------------------------

    @Test
    void duplicatePushIsIdempotent() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "幂等待办任务" + suffix, 1L, null);

        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start'", Integer.class, taskId);
        assertThat(before).isEqualTo(1);

        // 同一事项重复投递（改标题），走 internal/push 统一入口
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("bizType", "task_assign");
        push.put("bizId", taskId);
        push.put("userId", 1);
        push.put("actionType", "start");
        push.put("title", "重复投递更新后的标题" + suffix);
        push.put("actionUrl", "/task/list?taskId=" + taskId);
        push.put("priority", "urgent");
        ResponseEntity<Map<String, Object>> pushed = post(headers, "/api/personal/todos/internal/push", push);
        assertThat(pushed.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start'", Integer.class, taskId);
        assertThat(after).as("重复投递不得新增第二条").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start'", String.class, taskId))
                .as("未处理行按投递刷新展示字段").contains("重复投递更新后的标题");
    }

    // ------------------------------------------------------------------
    // ③ R4（V15 修正后语义）：已处理行冻结不可改；重复投递视为新事件 → 可新建
    // ------------------------------------------------------------------

    @Test
    void handledRowIsFrozenAndRepushCreatesNewTodo() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "防复活任务" + suffix, 1L, null);

        Map<String, Object> todo = findOpenTodo(headers, "task_assign", taskId, "start");
        Long todoId = longOf(todo, "id");
        assertThat(post(headers, "/api/personal/todos/" + todoId + "/handle",
                Map.of("action", "complete")).getStatusCode()).isEqualTo(HttpStatus.OK);
        String titleAfterHandle = jdbcTemplate.queryForObject(
                "SELECT title FROM todo_item WHERE id = ?", String.class, todoId);

        // 已处理后再投递（视为新的业务事件）：V15 起应新建一条 open 待办，旧行冻结
        pushTaskAssign(headers, taskId, "事件重投-新待办" + suffix);
        Long newTodoId = latestTodoId(taskId);
        assertThat(newTodoId).as("新事件必须能新建待办（V15 修复点）").isNotEqualTo(todoId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM todo_item WHERE id = ?", String.class, newTodoId))
                .isEqualTo("open");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM todo_item WHERE id = ?", String.class, newTodoId))
                .contains("事件重投-新待办");
        // R4：已处理的历史行不被修改、不被重开
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM todo_item WHERE id = ?", String.class, todoId))
                .as("已处理行保持 handled，不被重开").isEqualTo("handled");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM todo_item WHERE id = ?", String.class, todoId))
                .as("已处理行标题不被改写").isEqualTo(titleAfterHandle);
        assertThat(todoRowCount(taskId)).as("历史 1 条 + 新建 1 条").isEqualTo(2);

        // R2 仍成立：再投递时命中「未处理唯一」，不产生第三条，只刷新 open 行
        pushTaskAssign(headers, taskId, "再次投递-刷新未处理" + suffix);
        assertThat(todoRowCount(taskId)).as("未处理唯一：不新增第三条").isEqualTo(2);
        assertThat(openTodoRowCount(taskId)).as("open 行始终只有 1 条").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM todo_item WHERE id = ?", String.class, newTodoId))
                .as("未处理行按最新投递刷新").contains("再次投递-刷新未处理");
    }

    // ------------------------------------------------------------------
    // ③b 缺陷修复端到端：任务退回并重新派给同一人 → 收件箱再次出现待办
    // ------------------------------------------------------------------

    @Test
    void reassignedAfterHandleShowsNewTodo() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "退回重派任务" + suffix, 1L, null);

        Long firstTodoId = longOf(findOpenTodo(headers, "task_assign", taskId, "start"), "id");
        assertThat(post(headers, "/api/personal/todos/" + firstTodoId + "/handle",
                Map.of("action", "complete")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).containsEntry("status", "in_progress");

        // 退回待认领池，再重新派给同一人（修复前：唯一键冲突 → 收件箱不再出现待办）
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 0))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).containsEntry("status", "pool");
        assertThat(put(headers, "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 1))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).containsEntry("status", "todo");

        Map<String, Object> secondTodo = findOpenTodo(headers, "task_assign", taskId, "start");
        Long secondTodoId = longOf(secondTodo, "id");
        assertThat(secondTodoId).as("重新指派必须产生新的未处理待办").isNotEqualTo(firstTodoId);
        assertThat(secondTodo).containsEntry("status", "open");
        assertThat(todoRowCount(taskId)).as("历史 handled 行保留，共 2 行").isEqualTo(2);

        // 新待办仍可正常回写
        assertThat(post(headers, "/api/personal/todos/" + secondTodoId + "/handle",
                Map.of("action", "complete")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskData(headers, taskId)).containsEntry("status", "in_progress");
    }

    // ------------------------------------------------------------------
    // ③c 多条 handled 历史共存不冲突（生成列唯一索引允许多 NULL）
    // ------------------------------------------------------------------

    @Test
    void handledHistoryRowsCoexist() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "历史共存任务" + suffix, 1L, null);

        for (int round = 1; round <= 3; round++) {
            Map<String, Object> todo = findOpenTodo(headers, "task_assign", taskId, "start");
            assertThat(post(headers, "/api/personal/todos/" + longOf(todo, "id") + "/handle",
                    Map.of("action", "complete")).getStatusCode())
                    .as("第 %s 轮处理成功", round).isEqualTo(HttpStatus.OK);
            // 任务退回 + 重新指派同一人，制造下一轮投递
            put(headers, "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 0));
            put(headers, "/api/task/tasks/" + taskId + "/assign", Map.of("assigneeId", 1));
        }

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start' ORDER BY id", Long.class, taskId);
        assertThat(ids).as("3 轮处理 = 3 条 handled 历史 + 1 条当前未处理").hasSize(4);
        assertThat(ids).doesNotHaveDuplicates();
        Integer handled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_id=? AND status='handled'", Integer.class, taskId);
        assertThat(handled).as("3 条已处理历史共存").isEqualTo(3);
        assertThat(openTodoRowCount(taskId)).as("当前 1 条未处理").isEqualTo(1);
        Integer nullActiveKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_id=? AND active_uk IS NULL", Integer.class, taskId);
        assertThat(nullActiveKeys).as("handled 行的 active_uk 为 NULL").isEqualTo(3);

        // 表结构：V15 后只保留「仅未处理唯一」的生成列唯一索引
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'todo_item'", String.class);
        assertThat(indexes).contains("uk_todo_item_active").doesNotContain("uk_todo_item_biz");
    }

    // ------------------------------------------------------------------
    // ③d 并发投递同一未处理事项：唯一键仍保证只有一条
    // ------------------------------------------------------------------

    @Test
    void concurrentPushKeepsSingleOpenRow() throws Exception {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "并发投递任务" + suffix, 1L, null);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(executor.submit(() -> post(headers, "/api/personal/todos/internal/push",
                        Map.of("bizType", "task_assign", "bizId", taskId, "userId", 1, "actionType", "start",
                                "title", "并发投递-" + index, "actionUrl", "/task/list?taskId=" + taskId,
                                "priority", "high"))));
            }
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                assertThat(future.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(todoRowCount(taskId)).as("并发投递同一未处理事项不得产生多条").isEqualTo(1);
        assertThat(openTodoRowCount(taskId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ④ R3 强约束：handler 返回 false / 业务不存在时，待办不得关闭
    // ------------------------------------------------------------------

    @Test
    void rejectedHandlerKeepsTodoOpenAndBusinessUnchanged() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "R3强约束任务" + suffix, 1L, null);
        Map<String, Object> todo = findOpenTodo(headers, "task_assign", taskId, "start");
        Long todoId = longOf(todo, "id");

        // action=ignore：M03 handler 明确返回 false（不允许"只关待办不改业务"）
        ResponseEntity<Map<String, Object>> ignored = post(headers,
                "/api/personal/todos/" + todoId + "/handle", Map.of("action", "ignore"));
        assertThat(ignored.getStatusCode()).as("原模块拒绝必须返回 400").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(ignored.getBody().get("message"))).contains("未接受").contains("保持未处理");
        assertThat(todoStatus("task_assign", taskId, 1L, "start")).as("待办必须保持未处理").isEqualTo("open");
        assertThat(taskData(headers, taskId)).as("业务状态不得被改动").containsEntry("status", "todo");
        assertThat(handleLogCount(todoId, "rejected")).as("拒绝也要留痕").isEqualTo(1);

        // 业务侧删除任务后再处理：handler 找不到任务返回 false，待办同样不得关闭
        assertThat(delete(headers, "/api/task/tasks/" + taskId).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> deleted = post(headers,
                "/api/personal/todos/" + todoId + "/handle", Map.of("action", "complete"));
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(todoStatus("task_assign", taskId, 1L, "start")).isEqualTo("open");
        assertThat(handleLogCount(todoId, "rejected")).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------
    // ⑤ 未登记 handler 的 bizType：允许投递、处理时拒绝
    // ------------------------------------------------------------------

    @Test
    void reservedBizTypeCanBePushedButNotHandled() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();

        // meeting_invite 是已登记但无 handler 的预留类型（M06 未开工）
        long inviteBizId = SEQ.incrementAndGet();
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("bizType", "meeting_invite");
        push.put("bizId", inviteBizId);
        push.put("userId", 1);
        push.put("title", "会议邀请（M06 未开工）" + suffix);
        push.put("actionUrl", "/meeting/calendar?invite=1");
        push.put("dueTime", "2020-01-01 10:00:00");
        push.put("priority", "high");
        ResponseEntity<Map<String, Object>> pushed = post(headers, "/api/personal/todos/internal/push", push);
        assertThat(pushed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long todoId = idOf(pushed);

        // 处理时必须拒绝，且待办保持未处理
        ResponseEntity<Map<String, Object>> handled = post(headers,
                "/api/personal/todos/" + todoId + "/handle", Map.of("action", "complete"));
        assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(handled.getBody().get("message")))
                .contains("暂不支持在收件箱直接处理").contains("meeting_invite");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM todo_item WHERE id = ?", String.class, todoId))
                .isEqualTo("open");

        // 未登记的 bizType 连投递都要拒绝
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("bizType", "not_registered_type");
        unknown.put("bizId", 1);
        unknown.put("userId", 1);
        unknown.put("title", "非法类型");
        unknown.put("actionUrl", "/nowhere");
        ResponseEntity<Map<String, Object>> rejected = post(headers, "/api/personal/todos/internal/push", unknown);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("message"))).contains("未登记的 bizType");

        // 截止时间筛选：2020 年的待办能被 dueBefore=2021-01-01 命中（同时按 bizId 精确定位）
        ResponseEntity<Map<String, Object>> dueList = get(headers,
                "/api/personal/todos?status=open&bizType=meeting_invite&bizId=" + inviteBizId
                        + "&dueBefore=2021-01-01");
        assertThat(pageRows(dataOf(dueList))).hasSize(1);
        // 未来截止时间不应被该筛选命中
        ResponseEntity<Map<String, Object>> futureExcluded = get(headers,
                "/api/personal/todos?status=open&bizType=meeting_invite&bizId=" + inviteBizId
                        + "&dueBefore=2019-01-01");
        assertThat(pageRows(dataOf(futureExcluded))).isEmpty();
    }

    // ------------------------------------------------------------------
    // ⑥ 审批待办：收件箱处理驱动 M03 审批决策
    // ------------------------------------------------------------------

    @Test
    void approvalTodoDrivesM03ApprovalDecision() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskId = createTask(headers, "收件箱审批任务" + suffix, 1L, null);
        assertThat(transition(headers, taskId, "in_progress").getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> approvalRequest = new LinkedHashMap<>();
        approvalRequest.put("targetStatus", "done");
        approvalRequest.put("approverId", 1);
        approvalRequest.put("opinion", "申请完成");
        ResponseEntity<Map<String, Object>> startResponse = post(headers,
                "/api/task/tasks/" + taskId + "/approvals", approvalRequest);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long instanceId = idOf(startResponse);

        Map<String, Object> instance = dataOf(get(headers, "/api/task/flow/instances/" + instanceId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flowTasks = (List<Map<String, Object>>) instance.get("flowTasks");
        Long flowTaskId = longOf(flowTasks.get(0), "id");

        Map<String, Object> todo = findOpenTodo(headers, "task_approval", flowTaskId, "approve");
        Long todoId = longOf(todo, "id");
        assertThat(post(headers, "/api/personal/todos/" + todoId + "/handle",
                Map.of("action", "complete")).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(taskData(headers, taskId)).as("审批通过后复用的 M03 状态流转生效")
                .containsEntry("status", "done");
        Map<String, Object> after = dataOf(get(headers, "/api/task/flow/instances/" + instanceId));
        assertThat(after).containsEntry("status", "completed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterTasks = (List<Map<String, Object>>) after.get("flowTasks");
        assertThat(afterTasks.get(0)).containsEntry("status", "approved");
    }

    // ------------------------------------------------------------------
    // ⑦ 工时（R6）：无 task_id 被拒；按任务汇总正确
    // ------------------------------------------------------------------

    @Test
    void timeLogRequiresTaskAndSummariesByTask() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();
        Long taskA = createTask(headers, "工时任务A" + suffix, 1L, null);
        Long taskB = createTask(headers, "工时任务B" + suffix, 1L, null);
        String workDate = "2026-10-26";

        ResponseEntity<Map<String, Object>> noTask = post(headers, "/api/personal/time-logs",
                Map.of("durationMinutes", 30, "workDate", workDate, "description", "裸工时必须被拒"));
        assertThat(noTask.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(noTask.getBody().get("message"))).contains("taskId").contains("裸工时");

        ResponseEntity<Map<String, Object>> zero = post(headers, "/api/personal/time-logs",
                Map.of("taskId", taskA, "durationMinutes", 0, "workDate", workDate));
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(post(headers, "/api/personal/time-logs",
                Map.of("taskId", taskA, "durationMinutes", 60, "workDate", workDate, "description", "A-1"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(headers, "/api/personal/time-logs",
                Map.of("taskId", taskA, "durationMinutes", 30, "workDate", workDate, "description", "A-2"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(headers, "/api/personal/time-logs",
                Map.of("taskId", taskB, "durationMinutes", 45, "workDate", workDate, "description", "B-1"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> summary = get(headers,
                "/api/personal/time-logs/summary?fromDate=2026-10-26&toDate=2026-10-26");
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.getBody().get("data");
        assertThat(rows).anySatisfy(row -> {
            assertThat(((Number) row.get("taskId")).longValue()).isEqualTo(taskA);
            assertThat(((Number) row.get("totalMinutes")).intValue()).isEqualTo(90);
            assertThat(((Number) row.get("logCount")).intValue()).isEqualTo(2);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(((Number) row.get("taskId")).longValue()).isEqualTo(taskB);
            assertThat(((Number) row.get("totalMinutes")).intValue()).isEqualTo(45);
        });
    }

    // ------------------------------------------------------------------
    // ⑧ 复盘 + 专注会话（后端留痕）
    // ------------------------------------------------------------------

    @Test
    void reviewCrudAndFocusSessionWork() {
        HttpHeaders headers = adminHeaders();
        String suffix = nextSuffix();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reviewType", "custom");
        body.put("reviewTitle", "复盘-" + suffix);
        body.put("periodStart", "2026-10-19");
        body.put("periodEnd", "2026-10-25");
        body.put("content", "本周复盘正文");
        body.put("tags", "复盘,测试");
        ResponseEntity<Map<String, Object>> created = post(headers, "/api/personal/reviews", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long reviewId = idOf(created);

        Map<String, Object> detail = dataOf(get(headers, "/api/personal/reviews/" + reviewId));
        assertThat(detail).containsEntry("reviewTitle", "复盘-" + suffix).containsEntry("visibility", "private");

        body.put("reviewTitle", "复盘-改名-" + suffix);
        assertThat(put(headers, "/api/personal/reviews/" + reviewId, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(get(headers, "/api/personal/reviews/" + reviewId)))
                .containsEntry("reviewTitle", "复盘-改名-" + suffix);

        Long taskId = createTask(headers, "专注任务" + suffix, 1L, null);
        ResponseEntity<Map<String, Object>> focusStarted = post(headers, "/api/personal/focus-sessions",
                Map.of("taskId", taskId, "remark", "专注测试"));
        assertThat(focusStarted.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long sessionId = idOf(focusStarted);
        ResponseEntity<Map<String, Object>> focusEnded = put(headers,
                "/api/personal/focus-sessions/" + sessionId + "/end",
                Map.of("interruptCount", 1, "status", "finished"));
        assertThat(focusEnded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(focusEnded)).containsEntry("status", "finished");
        assertThat(pageRows(dataOf(get(headers, "/api/personal/focus-sessions?pageSize=100"))))
                .anySatisfy(row -> assertThat(row).containsEntry("status", "finished"));

        assertThat(delete(headers, "/api/personal/reviews/" + reviewId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(headers, "/api/personal/reviews/" + reviewId).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // ⑨ 效能画像（R7）：只读实时聚合、无明细画像表
    // ------------------------------------------------------------------

    @Test
    void profileIsReadOnlyAggregationWithoutPortraitTable() {
        HttpHeaders headers = adminHeaders();
        assertThat(post(headers, "/api/personal/time-logs",
                Map.of("taskId", createTask(headers, "画像任务" + nextSuffix(), 1L, null),
                        "durationMinutes", 15, "workDate", "2026-10-26"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> response = get(headers, "/api/personal/profile");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> profile = dataOf(response);
        assertThat(profile).containsEntry("readOnly", true).containsKeys(
                "todoThroughput", "byBizType", "workTime", "focus", "reviewCount", "sourceTables");
        @SuppressWarnings("unchecked")
        Map<String, Object> workTime = (Map<String, Object>) profile.get("workTime");
        assertThat(((Number) workTime.get("totalMinutes")).longValue()).isGreaterThanOrEqualTo(15);
        assertThat(((Number) workTime.get("taskCount")).longValue()).isGreaterThanOrEqualTo(1);

        // R7：不存在任何"画像/快照"明细表（实时聚合，不落库）
        Integer portraitTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND (table_name LIKE '%profile%' OR table_name LIKE '%portrait%')", Integer.class);
        assertThat(portraitTables).as("R7：不得落明细画像表").isZero();
    }

    // ------------------------------------------------------------------
    // 请求与数据辅助
    // ------------------------------------------------------------------

    private Long createTask(HttpHeaders headers, String title, Long assigneeId, Long krId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("taskDesc", "M10 集成测试任务");
        body.put("priority", "normal");
        body.put("dueDate", "2026-12-31");
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        if (krId != null) {
            body.put("krId", krId);
        }
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/task/tasks", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return idOf(response);
    }

    private ResponseEntity<Map<String, Object>> transition(HttpHeaders headers, Long taskId, String toStatus) {
        return put(headers, "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", toStatus));
    }

    private Map<String, Object> taskData(HttpHeaders headers, Long taskId) {
        return dataOf(get(headers, "/api/task/tasks/" + taskId));
    }

    /** 在本人收件箱里按 bizType + bizId + actionType 找命中的未处理行（按业务键精确筛选，不依赖分页）。 */
    private Map<String, Object> findOpenTodo(HttpHeaders headers, String bizType, Long bizId, String actionType) {
        ResponseEntity<Map<String, Object>> response = get(headers,
                "/api/personal/todos?status=open&bizType=" + bizType + "&bizId=" + bizId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return pageRows(dataOf(response)).stream()
                .filter(row -> longOf(row, "bizId") == bizId.longValue())
                .filter(row -> actionType.equals(String.valueOf(row.get("actionType"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到待办：bizType=" + bizType + " bizId=" + bizId
                        + " actionType=" + actionType));
    }

    private String todoStatus(String bizType, Long bizId, Long userId, String actionType) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM todo_item WHERE biz_type=? AND biz_id=? AND user_id=? AND action_type=? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, bizType, bizId, userId, actionType);
    }

    /** 直接调用统一投递入口（模拟业务侧重复事件）。 */
    private void pushTaskAssign(HttpHeaders headers, Long taskId, String title) {
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("bizType", "task_assign");
        push.put("bizId", taskId);
        push.put("userId", 1);
        push.put("actionType", "start");
        push.put("title", title);
        push.put("actionUrl", "/task/list?taskId=" + taskId);
        push.put("priority", "normal");
        assertThat(post(headers, "/api/personal/todos/internal/push", push).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private Long latestTodoId(Long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start' ORDER BY id DESC LIMIT 1", Long.class, taskId);
    }

    private int todoRowCount(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start'", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private int openTodoRowCount(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE biz_type='task_assign' AND biz_id=? AND user_id=1 "
                        + "AND action_type='start' AND status='open'", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private int handleLogCount(Long todoId, String result) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_handle_log WHERE todo_id=? AND result=?", Integer.class, todoId, result);
        return count == null ? 0 : count;
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
    private List<Map<String, Object>> pageRows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
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
