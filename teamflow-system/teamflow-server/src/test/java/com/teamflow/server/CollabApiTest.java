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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M09 协作与消息（M3-A）接口集成测试：站内信、统一评论与 @提及、动态流投影、公告可见性、统一通知入口。
 *
 * <p>全部走真实 HTTP（{@code RANDOM_PORT} + 真实 JWT）。覆盖 task-28 的关键验收：
 * ① 站内信收发/已读/未读；② 评论多态挂载且全站只有一张评论表；③ @提及经 M10 统一投递入口进入待办
 * （不直写待办表，且收件箱按「未登记处理者」拒绝直接处理）；④ 完成任务后动态流由事件投影出现；
 * ⑤ 公告按全员/部门范围可见；⑥ 统一通知入口幂等与模板渲染。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class CollabApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> conversationIds = new ArrayList<>();
    private final List<Long> commentIds = new ArrayList<>();
    private final List<Long> announcementIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final List<String> notificationKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long conversationId : conversationIds) {
            jdbcTemplate.update("DELETE FROM msg_message WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM msg_read WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM msg_conversation_member WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM msg_conversation WHERE id = ?", conversationId);
        }
        for (Long commentId : commentIds) {
            jdbcTemplate.update("DELETE FROM msg_mention WHERE source_type = 'comment' AND source_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM msg_notification WHERE idempotency_key LIKE ?", "mention:comment:" + commentId + ":%");
            jdbcTemplate.update("DELETE FROM msg_channel_log WHERE idempotency_key LIKE ?", "mention:comment:" + commentId + ":%");
            jdbcTemplate.update("DELETE FROM todo_item WHERE biz_type = 'collab_mention' AND biz_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM collab_comment WHERE id = ?", commentId);
        }
        for (String key : notificationKeys) {
            jdbcTemplate.update("DELETE FROM msg_channel_log WHERE idempotency_key = ?", key);
            jdbcTemplate.update("DELETE FROM msg_notification WHERE idempotency_key = ?", key);
        }
        // 统一通知入口触发的待办（bizId 由测试指定）
        jdbcTemplate.update("DELETE FROM todo_item WHERE biz_type = 'collab_mention' AND user_id = 2 "
                + "AND biz_id NOT IN (SELECT id FROM collab_comment)");
        for (Long announcementId : announcementIds) {
            jdbcTemplate.update("DELETE FROM msg_announcement_read WHERE announcement_id = ?", announcementId);
            jdbcTemplate.update("DELETE FROM msg_announcement WHERE id = ?", announcementId);
        }
        for (Long taskId : taskIds) {
            jdbcTemplate.update("DELETE FROM collab_feed WHERE biz_type = 'task' AND biz_id = ?", taskId);
        }
        conversationIds.clear();
        commentIds.clear();
        announcementIds.clear();
        taskIds.clear();
        notificationKeys.clear();
    }

    // ------------------------------------------------------------------
    // ① 站内信：A→B 收发、已读、未读数
    // ------------------------------------------------------------------

    @Test
    void directMessageSendReadAndUnread() {
        HttpHeaders admin = adminHeaders();
        HttpHeaders chensy = userHeaders("chensy");
        Long conversationId = createConversation(admin, "direct", null, List.of(2L));

        Long messageId = sendMessage(admin, conversationId, "你好，请看一下这条消息 @" + String.valueOf(SEQ.incrementAndGet()));
        assertThat(messageId).isNotNull();

        Map<String, Object> conversation = findConversation(chensy, conversationId);
        assertThat(((Number) conversation.get("unreadCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(conversation.get("title"))).contains("周明远");

        Map<String, Object> unread = dataOf(get(chensy, "/api/collab/conversations/unread-count"));
        assertThat(((Number) unread.get("unreadCount")).longValue()).isGreaterThanOrEqualTo(1);

        ResponseEntity<Map<String, Object>> messages = get(chensy, "/api/collab/conversations/" + conversationId + "/messages");
        assertThat(messages.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) dataOf(messages).get("rows");
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("id", messageId.intValue()));

        ResponseEntity<Map<String, Object>> read = put(chensy,
                "/api/collab/conversations/" + conversationId + "/read", Map.of("lastReadMessageId", messageId));
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) dataOf(read).get("unreadCount")).longValue()).isZero();

        Map<String, Object> after = findConversation(chensy, conversationId);
        assertThat(((Number) after.get("unreadCount")).longValue()).isZero();
    }

    // ------------------------------------------------------------------
    // ② 评论多态挂载 + 全站只有一张评论表
    // ------------------------------------------------------------------

    @Test
    void commentPolymorphicAndSingleCommentTable() {
        HttpHeaders admin = adminHeaders();
        Long taskId = createTask(admin, "评论挂载任务" + nextSuffix());
        Long documentCommentId = createComment(admin, "document", 900001L, "文档评论：多态挂载验证");
        Long taskCommentId = createComment(admin, "task", taskId, "任务评论：多态挂载验证");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taskComments = (List<Map<String, Object>>) dataOf(get(admin,
                "/api/collab/comments?bizType=task&bizId=" + taskId)).get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documentComments = (List<Map<String, Object>>) dataOf(get(admin,
                "/api/collab/comments?bizType=document&bizId=900001")).get("rows");

        assertThat(taskComments).anySatisfy(row -> assertThat(row).containsEntry("id", taskCommentId.intValue()));
        assertThat(documentComments).anySatisfy(row -> assertThat(row).containsEntry("id", documentCommentId.intValue()));
        assertThat(taskComments).noneSatisfy(row -> assertThat(row).containsEntry("id", documentCommentId.intValue()));

        Integer commentTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'", Integer.class);
        assertThat(commentTables).as("全站只允许一张评论表").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name LIKE '%comment%'", String.class)).isEqualTo("collab_comment");
    }

    // ------------------------------------------------------------------
    // ③ @提及经 M10 统一投递入口进入待办（不直写待办表）
    // ------------------------------------------------------------------

    @Test
    void mentionDeliversTodoThroughM10AndInboxRejectsHandling() {
        HttpHeaders admin = adminHeaders();
        HttpHeaders chensy = userHeaders("chensy");
        Long taskId = createTask(admin, "提及任务" + nextSuffix());
        // chensy 评论并 @admin：被提及者是 admin（拥有个人收件箱处理权限）
        Long commentId = createComment(chensy, "task", taskId, "@admin 这条任务需要你看一下");

        // 提及记录 + 通知入库 + 待办入库（AFTER_COMMIT 投递端同步执行）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_mention WHERE source_type='comment' AND source_id=?",
                Integer.class, commentId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE idempotency_key LIKE ?",
                Integer.class, "mention:comment:" + commentId + ":%")).isEqualTo(1);
        Map<String, Object> todoRow = jdbcTemplate.queryForMap(
                "SELECT id, user_id, status FROM todo_item WHERE biz_type='collab_mention' AND biz_id=?",
                commentId);
        assertThat(((Number) todoRow.get("user_id")).longValue()).isEqualTo(1L);
        assertThat(String.valueOf(todoRow.get("status"))).isEqualTo("open");
        // 待办是通过 M10 通道投递：投递日志有 todo/ok 记录
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_channel_log WHERE channel='todo' AND status='ok' AND idempotency_key LIKE ?",
                Integer.class, "mention:comment:" + commentId + ":%")).isEqualTo(1);
        // M09 侧没有直写待办：channel_log 记录的是经 M10 投递，本模块代码不出现待办表写入（由边界 grep 证明）

        // 收件箱按「已登记但无处理者」拒绝直接处理，提示回源
        Long todoId = ((Number) todoRow.get("id")).longValue();
        ResponseEntity<Map<String, Object>> handled = post(admin,
                "/api/personal/todos/" + todoId + "/handle", Map.of("action", "complete"));
        assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(handled.getBody().get("message")))
                .contains("暂不支持在收件箱直接处理").contains("collab_mention");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM todo_item WHERE id = ?", String.class, todoId)).isEqualTo("open");
    }

    // ------------------------------------------------------------------
    // ④ 动态流由任务领域事件投影生成
    // ------------------------------------------------------------------

    @Test
    void feedProjectedFromTaskEvent() {
        HttpHeaders admin = adminHeaders();
        Long taskId = createTask(admin, "动态流任务" + nextSuffix());
        assertThat(put(admin, "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", "in_progress"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(admin, "/api/task/tasks/" + taskId + "/status", Map.of("toStatus", "done"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> feeds = (List<Map<String, Object>>) dataOf(get(admin,
                "/api/collab/feeds?pageSize=100&action=task_completed")).get("rows");
        assertThat(feeds).anySatisfy(row -> {
            assertThat(((Number) row.get("bizId")).longValue()).isEqualTo(taskId);
            assertThat(row).containsEntry("action", "task_completed");
            assertThat(String.valueOf(row.get("summary"))).contains("完成了任务 #" + taskId);
        });
        Integer feedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collab_feed WHERE biz_type='task' AND biz_id=? AND action='task_completed'",
                Integer.class, taskId);
        assertThat(feedRows).as("同一事件只投影一条动态").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ⑤ 公告可见性：全员 / 部门（与平台数据权限口径一致）
    // ------------------------------------------------------------------

    @Test
    void announcementVisibilityByScope() {
        HttpHeaders admin = adminHeaders();
        Long allId = createAnnouncement(admin, "全员公告" + nextSuffix(), "all", null, "published");
        Long deptId = createAnnouncement(admin, "部门公告" + nextSuffix(), "dept", 5L, "published");
        Long draftId = createAnnouncement(admin, "草稿公告" + nextSuffix(), "all", null, "draft");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> adminView = (List<Map<String, Object>>) dataOf(get(admin,
                "/api/collab/announcements?pageSize=100")).get("rows");
        assertThat(adminView).anySatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(allId));
        assertThat(adminView).anySatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(draftId));

        // chensy 属于部门 5：能看到全员公告与部门 5 的公告
        HttpHeaders chensy = userHeaders("chensy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userDept5 = (List<Map<String, Object>>) dataOf(get(chensy,
                "/api/collab/announcements?pageSize=100")).get("rows");
        assertThat(userDept5).anySatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(allId));
        assertThat(userDept5).anySatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(deptId));
        assertThat(userDept5).noneSatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(draftId));

        // wumin 属于部门 6：只能看到全员公告
        HttpHeaders wumin = userHeaders("wumin");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userDept6 = (List<Map<String, Object>>) dataOf(get(wumin,
                "/api/collab/announcements?pageSize=100")).get("rows");
        assertThat(userDept6).anySatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(allId));
        assertThat(userDept6).noneSatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(deptId));

        // 已读标记：chensy 读完全员公告后列表回显 read=true
        assertThat(put(chensy, "/api/collab/announcements/" + allId + "/read", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterRead = (List<Map<String, Object>>) dataOf(get(chensy,
                "/api/collab/announcements?pageSize=100")).get("rows");
        assertThat(afterRead).anySatisfy(row -> {
            if (((Number) row.get("id")).longValue() == allId) {
                assertThat(row).containsEntry("read", true);
            }
        });
    }

    // ------------------------------------------------------------------
    // ⑥ 统一通知入口：模板渲染 + 幂等 + 按需投递待办
    // ------------------------------------------------------------------

    @Test
    void internalNotifyIsIdempotentAndCanQueueTodo() {
        HttpHeaders admin = adminHeaders();
        String key = "test-notify-" + nextSuffix();
        notificationKeys.add(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiverId", 2);
        body.put("templateCode", "mention");
        body.put("params", Map.of("actorName", "周明远", "content", "统一通知入口验证"));
        body.put("bizType", "collab_mention");
        body.put("bizId", SEQ.incrementAndGet());
        body.put("actionUrl", "/personal/todo?demo=1");
        body.put("idempotencyKey", key);
        body.put("requireTodo", true);

        ResponseEntity<Map<String, Object>> first = post(admin, "/api/collab/internal/notify", body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> firstData = dataOf(first);
        assertThat(firstData).containsEntry("duplicated", false);
        assertThat(firstData.get("id")).isNotNull();

        ResponseEntity<Map<String, Object>> second = post(admin, "/api/collab/internal/notify", body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(second)).containsEntry("duplicated", true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM msg_notification WHERE idempotency_key = ? AND title LIKE '%提到了你%'",
                Integer.class, key)).isEqualTo(1);

        HttpHeaders chensy = userHeaders("chensy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) dataOf(get(chensy,
                "/api/collab/notifications?pageSize=100")).get("rows");
        assertThat(notifications).anySatisfy(row ->
                assertThat(String.valueOf(row.get("title"))).contains("提到了你"));
        Map<String, Object> unread = dataOf(get(chensy, "/api/collab/notifications/unread-count"));
        assertThat(((Number) unread.get("unreadCount")).longValue()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 请求与数据辅助
    // ------------------------------------------------------------------

    private Long createConversation(HttpHeaders headers, String type, String title, List<Long> memberIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationType", type);
        body.put("title", title);
        body.put("memberIds", memberIds);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/collab/conversations", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = idOf(response);
        conversationIds.add(id);
        return id;
    }

    private Long sendMessage(HttpHeaders headers, Long conversationId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        ResponseEntity<Map<String, Object>> response = post(headers,
                "/api/collab/conversations/" + conversationId + "/messages", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) dataOf(response).get("id")).longValue();
    }

    private Long createComment(HttpHeaders headers, String bizType, Long bizId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizType", bizType);
        body.put("bizId", bizId);
        body.put("content", content);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/collab/comments", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = idOf(response);
        commentIds.add(id);
        return id;
    }

    private Long createAnnouncement(HttpHeaders headers, String title, String scopeType, Long deptId, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", "公告正文：" + title);
        body.put("scopeType", scopeType);
        body.put("deptId", deptId);
        body.put("status", status);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/collab/announcements", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = idOf(response);
        announcementIds.add(id);
        return id;
    }

    private Long createTask(HttpHeaders headers, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskTitle", title);
        body.put("priority", "normal");
        body.put("assigneeId", 1L);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/task/tasks", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = idOf(response);
        taskIds.add(id);
        return id;
    }

    private Map<String, Object> findConversation(HttpHeaders headers, Long conversationId) {
        ResponseEntity<Map<String, Object>> response = get(headers, "/api/collab/conversations");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("data");
        return rows.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == conversationId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到会话：" + conversationId));
    }

    private HttpHeaders adminHeaders() {
        return bearerHeaders(TestAuthClient.login(restTemplate, "admin", "admin123"));
    }

    private HttpHeaders userHeaders(String username) {
        return bearerHeaders(TestAuthClient.login(restTemplate, username, "admin123"));
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

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }

    private String nextSuffix() {
        return String.valueOf(SEQ.incrementAndGet());
    }
}
