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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M06 日程与会议集成测试（真实 HTTP + 真实 M07/M09/M10 服务链路；测试库 teamflow_test，自清理）。
 *
 * <p>覆盖：会议室冲突检测、会议全生命周期（创建/改期/取消）、纪要归档 M07（只存 pageId）、
 * 纪要待办经 M10 进入主持人收件箱、邀请/改期/取消通知经 M09、评论经 M09 且无评论表、
 * 日程与模板、跨模块写入边界扫描。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class MeetingApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> roomIds = new ArrayList<>();
    private final List<Long> meetingIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();
    private final List<Long> commentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long meetingId : meetingIds) {
            List<Long> minutesPageIds = jdbcTemplate.queryForList(
                    "SELECT minutes_page_id FROM meeting_meeting WHERE id = ? AND minutes_page_id IS NOT NULL",
                    Long.class, meetingId);
            for (Long minutesPageId : minutesPageIds) {
                jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'page' AND resource_id = ?", minutesPageId);
                jdbcTemplate.update("DELETE FROM knowledge_page_version WHERE page_id = ?", minutesPageId);
                jdbcTemplate.update("DELETE FROM knowledge_file WHERE page_id = ?", minutesPageId);
                jdbcTemplate.update("DELETE FROM knowledge_page WHERE id = ?", minutesPageId);
            }
            jdbcTemplate.update("DELETE FROM todo_item WHERE biz_type = 'meeting_invite' AND biz_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM msg_notification WHERE biz_type = 'meeting' AND biz_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM msg_channel_log WHERE biz_type = 'meeting' AND biz_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM meeting_participant WHERE meeting_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM meeting_event WHERE meeting_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM meeting_booking WHERE meeting_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM meeting_meeting WHERE id = ?", meetingId);
        }
        for (Long roomId : roomIds) {
            jdbcTemplate.update("DELETE FROM meeting_booking WHERE room_id = ?", roomId);
            jdbcTemplate.update("DELETE FROM meeting_room WHERE id = ?", roomId);
        }
        for (Long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM meeting_event WHERE id = ?", eventId);
        }
        for (Long templateId : templateIds) {
            jdbcTemplate.update("DELETE FROM meeting_template WHERE id = ?", templateId);
        }
        for (Long commentId : commentIds) {
            jdbcTemplate.update("DELETE FROM msg_mention WHERE source_type = 'comment' AND source_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM collab_comment WHERE id = ?", commentId);
        }
        roomIds.clear();
        meetingIds.clear();
        eventIds.clear();
        templateIds.clear();
        commentIds.clear();
    }

    // ------------------------------------------------------------------
    // 1. 会议室冲突检测（R1）
    // ------------------------------------------------------------------

    @Test
    void roomBookingConflictIsRejectedWithReadableMessage() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long roomId = createRoom(admin, "IT-ROOM-" + suffix, 10);

        Long firstBooking = idOf(post(admin, "/api/meeting/bookings", bookingBody(
                roomId, "评审会" + suffix, "2026-11-02T14:00:00", "2026-11-02T15:00:00")));
        assertThat(firstBooking).isNotNull();

        ResponseEntity<Map<String, Object>> conflict = post(admin, "/api/meeting/bookings", bookingBody(
                roomId, "冲突预定" + suffix, "2026-11-02T14:30:00", "2026-11-02T15:30:00"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = String.valueOf(conflict.getBody().get("message"));
        assertThat(message).contains("已被占用").contains("冲突").contains("IT-ROOM-" + suffix);

        // 边界：首尾相接不算冲突
        Long adjacent = idOf(post(admin, "/api/meeting/bookings", bookingBody(
                roomId, "紧邻预定" + suffix, "2026-11-02T15:00:00", "2026-11-02T16:00:00")));
        assertThat(adjacent).isNotNull();

        List<Map<String, Object>> availability = listOf(get(admin,
                "/api/meeting/rooms/availability?startTime=2026-11-02T14:30:00&endTime=2026-11-02T15:30:00&keyword=IT-ROOM-" + suffix));
        assertThat(availability).hasSize(1);
        assertThat(availability.get(0)).containsEntry("available", false);
        assertThat(((Number) availability.get(0).get("conflictCount")).intValue()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 2. 会议全链路 + 纪要归档 M07（核心）
    // ------------------------------------------------------------------

    @Test
    void meetingLifecycleAndMinutesArchiveToKnowledge() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long roomId = createRoom(admin, "IT-MEET-" + suffix, 8);
        Long meetingId = createMeeting(admin, roomId, "季度评审会" + suffix,
                "2026-11-03T10:00:00", "2026-11-03T11:00:00", List.of(2L));

        Map<String, Object> created = dataOf(get(admin, "/api/meeting/meetings/" + meetingId));
        assertThat(created).containsEntry("status", "scheduled").containsEntry("roomName", "IT-MEET-" + suffix);
        assertThat((List<?>) created.get("participants")).hasSize(2);

        // 改期：换时间，会议室冲突检测与学生成
        Map<String, Object> schedule = dataOf(put(admin, "/api/meeting/meetings/" + meetingId + "/schedule",
                Map.of("startTime", "2026-11-03T15:00:00", "endTime", "2026-11-03T16:00:00", "roomId", roomId)));
        assertThat(String.valueOf(schedule.get("startTime"))).contains("2026-11-03 15:00");
        assertThat(((Number) schedule.get("rescheduleCount")).intValue()).isEqualTo(1);

        // 结束会议 → 生成纪要待办（经 M10）
        Map<String, Object> ended = dataOf(post(admin, "/api/meeting/meetings/" + meetingId + "/end", Map.of()));
        assertThat(ended).containsEntry("status", "ended");
        assertThat(ended.get("minutesTodoId")).isNotNull();

        // 归档纪要到 M07（核心）：只保存 minutes_page_id
        String content = "# 季度评审会纪要\n决议：知识库全文检索先行；目标对齐检查下周复盘。关键结论：联调窗口提前。";
        Map<String, Object> archived = dataOf(post(admin, "/api/meeting/meetings/" + meetingId + "/minutes",
                Map.of("content", content, "summary", "评审结论：按期推进", "tags", List.of("会议纪要", "评审"))));
        Long pageId = ((Number) archived.get("minutesPageId")).longValue();
        assertThat(pageId).isNotNull();
        assertThat(archived).containsEntry("pageType", "minutes");

        Map<String, Object> detail = dataOf(get(admin, "/api/meeting/meetings/" + meetingId));
        assertThat(((Number) detail.get("minutesPageId")).longValue()).isEqualTo(pageId);

        // 查库：meeting_meeting 只有 pageId，无任何纪要正文字段
        Long minutesPageIdInDb = jdbcTemplate.queryForObject(
                "SELECT minutes_page_id FROM meeting_meeting WHERE id = ?", Long.class, meetingId);
        assertThat(minutesPageIdInDb).isEqualTo(pageId);
        Long contentColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'meeting_meeting'
                  AND (LOWER(column_name) LIKE '%content%' OR LOWER(column_name) LIKE '%body%'
                       OR LOWER(column_name) LIKE '%markdown%')
                """, Long.class);
        assertThat(contentColumns).as("meeting_meeting 不得有纪要正文字段").isZero();

        // 纪要归档后通知参会人（推送参与者，经 M09）
        HttpHeaders chensy = bearerHeaders(login("chensy", "admin123"));
        List<Map<String, Object>> chensyNotifications = rowsOf(dataOf(get(chensy, "/api/collab/notifications?unreadOnly=false")));
        assertThat(chensyNotifications.stream()
                .filter(row -> row.get("bizId") != null && meetingId.equals(((Number) row.get("bizId")).longValue()))
                .toList())
                .anySatisfy(row -> assertThat(String.valueOf(row.get("title"))).contains("会议纪要已归档"));

        // 正文只存在 M07 页面，且可被 M07 检索命中
        Map<String, Object> pageRow = jdbcTemplate.queryForMap(
                "SELECT page_title, content, source_module FROM knowledge_page WHERE id = ?", pageId);
        assertThat(String.valueOf(pageRow.get("content"))).contains("联调窗口提前");
        assertThat(String.valueOf(pageRow.get("source_module"))).isEqualTo("M06");
        Map<String, Object> search = dataOf(get(admin, "/api/knowledge/search?keyword=联调窗口提前"));
        assertThat(resultPageIds(search)).contains(pageId.intValue());
    }

    // ------------------------------------------------------------------
    // 3. 纪要待办进主持人收件箱（M10 投递）
    // ------------------------------------------------------------------

    @Test
    void minutesTodoLandsInOrganizerInboxThroughM10() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long meetingId = createMeeting(admin, null, "纪要待办会" + suffix,
                "2026-11-04T09:00:00", "2026-11-04T09:30:00", List.of(2L));
        Map<String, Object> ended = dataOf(post(admin, "/api/meeting/meetings/" + meetingId + "/end", Map.of()));
        Long todoId = ((Number) ended.get("minutesTodoId")).longValue();
        assertThat(todoId).isNotNull();

        Map<String, Object> page = dataOf(get(admin,
                "/api/personal/todos?status=open&bizType=meeting_invite&bizId=" + meetingId));
        List<Map<String, Object>> rows = rowsOf(page);
        Map<String, Object> minutesTodo = rows.stream()
                .filter(row -> "write_minutes".equals(row.get("actionType")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(minutesTodo.get("title"))).contains("撰写会议纪要");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM todo_item WHERE id = ?", Long.class, todoId)).isEqualTo(1L);

        // 库里确认该待办由统一投递入口写入（M06 未直写）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM todo_item WHERE id = ? AND biz_type = 'meeting_invite' AND action_type = 'write_minutes'",
                Integer.class, todoId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 4. 会议邀请通知（M09）
    // ------------------------------------------------------------------

    @Test
    void inviteNotificationReachesParticipantThroughCollab() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long meetingId = createMeeting(admin, null, "邀请通知会" + suffix,
                "2026-11-05T14:00:00", "2026-11-05T15:00:00", List.of(2L));

        HttpHeaders chensy = bearerHeaders(login("chensy", "admin123"));
        Map<String, Object> page = dataOf(get(chensy, "/api/collab/notifications?unreadOnly=false"));
        List<Map<String, Object>> rows = rowsOf(page);
        Map<String, Object> notification = rows.stream()
                .filter(row -> row.get("bizId") != null && meetingId.equals(((Number) row.get("bizId")).longValue()))
                .filter(row -> String.valueOf(row.get("title")).contains("邀请通知会" + suffix))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(notification.get("title"))).contains("会议邀请");
        assertThat(notification).containsEntry("unread", true);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM msg_notification
                WHERE receiver_id = 2 AND biz_type = 'meeting' AND biz_id = ?
                  AND title LIKE '%邀请通知会%'
                """, Integer.class, meetingId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 5. 改期 / 取消：会议室释放 + 日程同步
    // ------------------------------------------------------------------

    @Test
    void rescheduleThenCancelReleaseRoomAndSyncEvents() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long roomA = createRoom(admin, "IT-RA-" + suffix, 6);
        Long roomB = createRoom(admin, "IT-RB-" + suffix, 6);
        Long meetingId = createMeeting(admin, roomA, "改期取消会" + suffix,
                "2026-11-06T10:00:00", "2026-11-06T11:00:00", List.of(2L));

        // R1 参会人时段冲突：同一参会人同一时段再开会 → 400 可读提示
        Map<String, Object> participantConflictBody = new LinkedHashMap<>();
        participantConflictBody.put("meetingTitle", "参会人冲突会" + suffix);
        participantConflictBody.put("startTime", "2026-11-06T10:30:00");
        participantConflictBody.put("endTime", "2026-11-06T11:30:00");
        participantConflictBody.put("participantIds", List.of(2L));
        ResponseEntity<Map<String, Object>> participantConflict = post(admin, "/api/meeting/meetings", participantConflictBody);
        assertThat(participantConflict.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(participantConflict.getBody().get("message")))
                .contains("参会人").contains("时间冲突");

        put(admin, "/api/meeting/meetings/" + meetingId + "/schedule",
                Map.of("startTime", "2026-11-06T13:00:00", "endTime", "2026-11-06T14:00:00", "roomId", roomB));

        // 旧会议室预定时段释放，新会议室被占用
        List<Map<String, Object>> roomAAvailability = listOf(get(admin,
                "/api/meeting/rooms/availability?startTime=2026-11-06T10:00:00&endTime=2026-11-06T11:00:00&keyword=IT-RA-" + suffix));
        assertThat(roomAAvailability.get(0)).containsEntry("available", true);
        List<Map<String, Object>> roomBAvailability = listOf(get(admin,
                "/api/meeting/rooms/availability?startTime=2026-11-06T13:00:00&endTime=2026-11-06T14:00:00&keyword=IT-RB-" + suffix));
        assertThat(roomBAvailability.get(0)).containsEntry("available", false);

        // 参会人日程同步为新时间
        List<Map<String, Object>> events = rowsOf(dataOf(get(admin,
                "/api/meeting/events?eventType=meeting&fromTime=2026-11-06T00:00:00&toTime=2026-11-07T00:00:00")));
        assertThat(events.stream().filter(row -> meetingId.equals(((Number) row.get("meetingId")).longValue())))
                .anySatisfy(row -> assertThat(String.valueOf(row.get("startTime"))).contains("13:00"));

        dataOf(post(admin, "/api/meeting/meetings/" + meetingId + "/cancel", Map.of("cancelReason", "计划调整")));
        Map<String, Object> detail = dataOf(get(admin, "/api/meeting/meetings/" + meetingId));
        assertThat(detail).containsEntry("status", "cancelled").containsEntry("cancelReason", "计划调整");
        assertThat(detail.get("cancelBy")).isNotNull();

        // 取消后会议室释放，会议日程全部取消
        List<Map<String, Object>> afterCancel = listOf(get(admin,
                "/api/meeting/rooms/availability?startTime=2026-11-06T13:00:00&endTime=2026-11-06T14:00:00&keyword=IT-RB-" + suffix));
        assertThat(afterCancel.get(0)).containsEntry("available", true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM meeting_event WHERE meeting_id = ? AND status = 'active'",
                Integer.class, meetingId)).isZero();
    }

    // ------------------------------------------------------------------
    // 6. 会议评论走 M09；M06 无评论表
    // ------------------------------------------------------------------

    @Test
    void meetingCommentsGoThroughCollabAndNoCommentTable() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long meetingId = createMeeting(admin, null, "评论会议" + suffix,
                "2026-11-09T10:00:00", "2026-11-09T11:00:00", List.of(2L));

        Map<String, Object> comment = dataOf(post(admin, "/api/collab/comments", Map.of(
                "bizType", "meeting",
                "bizId", meetingId,
                "content", "会议评论走统一评论能力" + suffix)));
        Long commentId = ((Number) comment.get("id")).longValue();
        commentIds.add(commentId);

        Map<String, Object> comments = dataOf(get(admin,
                "/api/collab/comments?bizType=meeting&bizId=" + meetingId));
        assertThat(rowsOf(comments)).extracting(row -> ((Number) row.get("id")).longValue()).contains(commentId);

        Integer commentTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'
                """, Integer.class);
        assertThat(commentTables).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'
                """, String.class)).isEqualTo("collab_comment");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE 'meeting%comment%'
                """, Integer.class)).isZero();
    }

    // ------------------------------------------------------------------
    // 7. 日程与会议模板
    // ------------------------------------------------------------------

    @Test
    void personalEventsAndBuiltinTemplatesWork() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();

        Long eventId = idOf(post(admin, "/api/meeting/events", Map.of(
                "eventTitle", "个人日程" + suffix,
                "eventType", "personal",
                "startTime", "2026-11-10T09:00:00",
                "endTime", "2026-11-10T10:00:00",
                "remindMinutes", 10
        )));
        assertThat(eventId).isNotNull();
        eventIds.add(eventId);
        Map<String, Object> eventPage = dataOf(get(admin,
                "/api/meeting/events?eventType=personal&keyword=个人日程" + suffix));
        assertThat(rowsOf(eventPage)).extracting(row -> ((Number) row.get("id")).longValue()).contains(eventId);
        assertThat(get(admin, "/api/meeting/events/" + eventId).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> templates = listOf(get(admin, "/api/meeting/templates"));
        assertThat(templates).extracting(row -> String.valueOf(row.get("templateCode")))
                .contains("normal", "okr_calibration", "retro");

        Long templateId = idOf(post(admin, "/api/meeting/templates", Map.of(
                "templateCode", "it_tpl_" + suffix,
                "templateName", "集成测试模板" + suffix,
                "templateType", "normal",
                "agendaTemplate", "## 议程\n1. 进展\n2. 结论",
                "defaultDurationMinutes", 45
        )));
        assertThat(templateId).isNotNull();
        templateIds.add(templateId);
        assertThat(delete(admin, "/api/meeting/templates/" + templateId).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rooms = listOf(get(admin, "/api/meeting/rooms"));
        assertThat(rooms).extracting(row -> String.valueOf(row.get("roomCode"))).contains("A-301", "B-201");
    }

    // ------------------------------------------------------------------
    // 8. 跨模块边界：不直写待办/评论、不读他人业务表、不直写知识页面
    // ------------------------------------------------------------------

    @Test
    void meetingPackageHasNoCrossModuleWrites() throws IOException {
        Path meetingRoot = Paths.get("..", "teamflow-system", "src", "main", "java", "com", "teamflow", "meeting");
        assertThat(Files.isDirectory(meetingRoot)).as("应能定位 M06 源码目录").isTrue();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(meetingRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.matches("(?is).*(INTO\\s+todo_item|INTO\\s+collab_comment|FROM\\s+okr_|FROM\\s+task_|INTO\\s+knowledge_page).*")) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertThat(violations).as("M06 只能经 Service 投递待办/通知/归档，不得直写他人表").isEmpty();
    }

    // ------------------------------------------------------------------
    // 请求辅助
    // ------------------------------------------------------------------

    private Long createRoom(HttpHeaders headers, String roomName, int capacity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomName", roomName);
        body.put("roomCode", roomName);
        body.put("location", "集成测试楼层");
        body.put("capacity", capacity);
        body.put("equipment", "投影,白板");
        body.put("status", "available");
        Long id = idOf(post(headers, "/api/meeting/rooms", body));
        roomIds.add(id);
        return id;
    }

    private Map<String, Object> bookingBody(Long roomId, String title, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("bookingTitle", title);
        body.put("startTime", start);
        body.put("endTime", end);
        return body;
    }

    private Long createMeeting(HttpHeaders headers, Long roomId, String title,
                               String start, String end, List<Long> participantIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meetingTitle", title);
        body.put("roomId", roomId);
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("agenda", "## 议程\n1. 进展同步\n2. 风险与结论");
        body.put("participantIds", participantIds);
        ResponseEntity<Map<String, Object>> response = post(headers, "/api/meeting/meetings", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = ((Number) dataOf(response).get("id")).longValue();
        meetingIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------
    // HTTP 与断言辅助
    // ------------------------------------------------------------------

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
        Map<String, Object> body = response.getBody();
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

    private List<Integer> resultPageIds(Map<String, Object> search) {
        Object results = search.get("results");
        if (!(results instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> ((Number) ((Map<?, ?>) item).get("pageId")).intValue())
                .toList();
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }
}
