package com.teamflow.server;

import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;
import com.teamflow.knowledge.domain.vo.PageContentSlice;
import com.teamflow.knowledge.service.KnowledgeArchiveService;
import com.teamflow.knowledge.service.KnowledgeContentProvider;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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
 * M07 知识与文档集成测试（真实 HTTP + 真实 Service；测试库 teamflow_test，自清理）。
 *
 * <p>覆盖：空间/目录/页面三层与授权继承覆盖、版本与回滚、中文全文检索、跨模块归档 Service、
 * 评论经 M09（本模块零评论表）、M11 正文切片、附件只存文件 ID、跨模块 SQL 边界。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
class KnowledgeApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeArchiveService archiveService;

    @Autowired
    private KnowledgeContentProvider contentProvider;

    private final List<Long> spaceIds = new ArrayList<>();
    private final List<Long> pageIds = new ArrayList<>();
    private final List<Long> commentIds = new ArrayList<>();
    private final List<Long> fileIds = new ArrayList<>();
    /** 归档空间为预置数据，只清理测试产生的页面，不删除空间本身。 */
    private final List<Long> archivedPageIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        List<Long> pages = new ArrayList<>(pageIds);
        pages.addAll(archivedPageIds);
        for (Long pageId : pages) {
            jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'page' AND resource_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_page_version WHERE page_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_file WHERE page_id = ?", pageId);
            jdbcTemplate.update("DELETE FROM knowledge_page WHERE id = ?", pageId);
        }
        for (Long spaceId : spaceIds) {
            jdbcTemplate.update("DELETE FROM knowledge_file WHERE space_id = ?", spaceId);
            jdbcTemplate.update("""
                    DELETE FROM knowledge_acl
                    WHERE resource_type = 'node'
                      AND resource_id IN (SELECT id FROM knowledge_node WHERE space_id = ?)
                    """, spaceId);
            jdbcTemplate.update("DELETE FROM knowledge_node WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM knowledge_acl WHERE resource_type = 'space' AND resource_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM knowledge_space WHERE id = ?", spaceId);
        }
        for (Long commentId : commentIds) {
            jdbcTemplate.update("DELETE FROM msg_mention WHERE source_type = 'comment' AND source_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM collab_comment WHERE id = ?", commentId);
        }
        for (Long fileId : fileIds) {
            jdbcTemplate.update("DELETE FROM sys_file WHERE id = ?", fileId);
        }
        spaceIds.clear();
        pageIds.clear();
        commentIds.clear();
        fileIds.clear();
        archivedPageIds.clear();
    }

    // ------------------------------------------------------------------
    // 1. 空间 → 目录 → 页面 + 权限继承与覆盖
    // ------------------------------------------------------------------

    @Test
    void spaceNodePageTreeAndPermissionInheritanceWithOverride() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "受限知识空间" + suffix, "restricted");
        Long nodeId = createNode(admin, spaceId, 0L, "研发规范" + suffix);
        Long pageId = createPage(admin, spaceId, nodeId, "接口规范" + suffix, "# 接口规范\n所有对外接口必须走统一返回结构。");

        Map<String, Object> tree = dataOf(get(admin, "/api/knowledge/spaces/" + spaceId + "/tree"));
        assertThat(String.valueOf(tree.get("nodeCount"))).isEqualTo("1");
        assertThat(String.valueOf(tree.get("pageCount"))).isEqualTo("1");
        List<Map<String, Object>> nodes = rowsOf(tree, "nodes");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).get("id")).isEqualTo(nodeId.intValue());

        HttpHeaders chensy = bearerHeaders(login("chensy", "admin123"));
        // 受限空间未授权：有功能权限但无资源授权 → 403
        assertThat(get(chensy, "/api/knowledge/pages/" + pageId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 父级（空间）授权只读 → 子级页面继承可见
        grant(admin, "space", spaceId, 2L, "read");
        assertThat(get(chensy, "/api/knowledge/pages/" + pageId).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 目录 deny 覆盖空间授权 → 再次拒绝
        grant(admin, "node", nodeId, 2L, "deny");
        assertThat(get(chensy, "/api/knowledge/pages/" + pageId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 页面 read 覆盖目录 deny → 重新可见
        grant(admin, "page", pageId, 2L, "read");
        ResponseEntity<Map<String, Object>> allowed = get(chensy, "/api/knowledge/pages/" + pageId);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> access = mapValue(dataOf(allowed).get("access"));
        assertThat(access).containsEntry("canRead", true).containsEntry("canWrite", false);
        assertThat(String.valueOf(access.get("reason"))).contains("页面显式授权");

        // 业务管理员（biz_admin，只读）不能新增页面
        HttpHeaders wumin = bearerHeaders(login("wumin", "admin123"));
        ResponseEntity<Map<String, Object>> deniedCreate = post(wumin, "/api/knowledge/pages",
                pageBody(spaceId, nodeId, "越权页面" + suffix, "内容"));
        assertThat(deniedCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // 2. 版本与回滚
    // ------------------------------------------------------------------

    @Test
    void pageVersionsAndRollbackRestoreContent() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "版本空间" + suffix, "internal");
        Long pageId = createPage(admin, spaceId, 0L, "版本演示" + suffix, "版本一：初始内容" + suffix);

        assertThat(put(admin, "/api/knowledge/pages/" + pageId,
                pageUpdateBody("版本二：第二次编辑" + suffix, "第二次保存")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(admin, "/api/knowledge/pages/" + pageId,
                pageUpdateBody("版本三：第三次编辑" + suffix, "第三次保存")).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> versions = listOf(get(admin, "/api/knowledge/pages/" + pageId + "/versions"));
        assertThat(versions).hasSize(3);
        assertThat(((Number) versions.get(0).get("versionNo")).intValue()).isEqualTo(3);
        assertThat(String.valueOf(versions.get(2).get("changeSummary"))).contains("创建页面");

        Map<String, Object> versionOne = dataOf(get(admin, "/api/knowledge/pages/" + pageId + "/versions/1"));
        assertThat(String.valueOf(versionOne.get("content"))).contains("版本一：初始内容");

        Map<String, Object> rollback = dataOf(post(admin,
                "/api/knowledge/pages/" + pageId + "/versions/1/rollback",
                Map.of("changeSummary", "回滚验证")));
        assertThat(((Number) rollback.get("versionNo")).intValue()).isEqualTo(4);

        Map<String, Object> page = dataOf(get(admin, "/api/knowledge/pages/" + pageId));
        assertThat(String.valueOf(page.get("content"))).contains("版本一：初始内容");
        assertThat(((Number) page.get("currentVersion")).intValue()).isEqualTo(4);
        assertThat(listOf(get(admin, "/api/knowledge/pages/" + pageId + "/versions"))).hasSize(4);
    }

    // ------------------------------------------------------------------
    // 3. 跨模块归档 Service（M06 纪要 / M14 复盘）
    // ------------------------------------------------------------------

    @Test
    void archiveServiceCreatesSearchablePageForMeetingAndProject() {
        String suffix = uniqueSuffix();
        long meetingId = Math.abs(System.nanoTime() % 900_000_000L) + 10_000L;
        KnowledgeArchiveResult meeting = archiveService.archive(new KnowledgeArchiveRequest(
                "M06", "meeting_minutes", meetingId, null, null,
                "研发周会纪要" + suffix, "# 研发周会纪要\n讨论知识库归档联调；决议：全文检索先行，切片接口下周联调。",
                List.of("会议纪要", "周会"), "会议决议摘要", 1L, "admin", false));
        assertThat(meeting.pageId()).isNotNull();
        assertThat(meeting.created()).isTrue();
        assertThat(meeting.pageType()).isEqualTo("minutes");
        archivedPageIds.add(meeting.pageId());

        // 幂等：同一来源再次归档返回同一页面
        KnowledgeArchiveResult again = archiveService.archive(new KnowledgeArchiveRequest(
                "M06", "meeting_minutes", meetingId, null, null,
                "研发周会纪要" + suffix, "重复投递不应新建页面", List.of(), null, 1L, "admin", false));
        assertThat(again.pageId()).isEqualTo(meeting.pageId());
        assertThat(again.created()).isFalse();
        assertThat(archiveService.findArchivedPageId("M06", "meeting_minutes", meetingId))
                .contains(meeting.pageId());

        long projectId = meetingId + 1;
        KnowledgeArchiveResult retro = archiveService.archive(new KnowledgeArchiveRequest(
                "M14", "project_retro", projectId, null, null,
                "项目结项复盘" + suffix, "# 项目复盘\n做得好的：需求澄清充分；待改进：联调窗口偏晚。",
                List.of("项目复盘"), "结项结论：按期交付", 1L, "admin", false));
        assertThat(retro.pageId()).isNotNull();
        assertThat(retro.pageType()).isEqualTo("retro");
        archivedPageIds.add(retro.pageId());

        HttpHeaders admin = adminHeaders();
        Map<String, Object> page = dataOf(get(admin, "/api/knowledge/pages/" + meeting.pageId()));
        assertThat(page).containsEntry("pageType", "minutes").containsEntry("sourceModule", "M06");
        assertThat(String.valueOf(page.get("content"))).contains("知识库归档联调");

        // 归档页面可被检索命中（正文只存 M07，一处存放两处可读）
        Map<String, Object> search = dataOf(get(admin,
                "/api/knowledge/search?keyword=" + "归档联调"));
        assertThat(resultPageIds(search)).contains(meeting.pageId().intValue());

        Long storedPageId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_page WHERE source_module = 'M06' AND source_biz_id = ?",
                Long.class, meetingId);
        assertThat(storedPageId).isEqualTo(meeting.pageId());
    }

    // ------------------------------------------------------------------
    // 4. 中文全文检索 + 空数据/短词边界
    // ------------------------------------------------------------------

    @Test
    void chineseFulltextSearchReturnsSnippetAndElapsed() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "检索空间" + suffix, "internal");
        Long pageId = createPage(admin, spaceId, 0L, "全文检索验证" + suffix,
                "本周发布计划：完成知识库全文检索联调，并用中文关键词验证命中；目标周期评审在周五。");

        ResponseEntity<Map<String, Object>> response = get(admin,
                "/api/knowledge/search?keyword=" + "全文检索联调");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> search = dataOf(response);
        assertThat(String.valueOf(search.get("engine"))).isIn("fulltext-ngram", "fulltext-miss-like-fallback", "like-fallback");
        assertThat(((Number) search.get("elapsedMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(resultPageIds(search)).contains(pageId.intValue());
        Map<String, Object> hit = resultItems(search).stream()
                .filter(item -> pageId.equals(((Number) item.get("pageId")).longValue()))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(hit.get("snippet"))).isNotBlank();

        // 单字关键词：ngram=2 无法命中，回落 LIKE
        Map<String, Object> shortKeyword = dataOf(get(admin,
                "/api/knowledge/search?keyword=" + "检" + "&spaceId=" + spaceId));
        assertThat(String.valueOf(shortKeyword.get("engine"))).isEqualTo("like-short-keyword");
        assertThat(resultPageIds(shortKeyword)).contains(pageId.intValue());

        // 受限页面不出现在其他人的检索结果里
        Long restrictedPageId = createPage(admin, spaceId, 0L, "受限页面" + suffix, "绝密内容：量子计算路线", "restricted");
        HttpHeaders chensy = bearerHeaders(login("chensy", "admin123"));
        Map<String, Object> chensySearch = dataOf(get(chensy,
                "/api/knowledge/search?keyword=" + "量子计算"));
        assertThat(resultPageIds(chensySearch)).doesNotContain(restrictedPageId.intValue());
        assertThat(resultPageIds(dataOf(get(admin,
                "/api/knowledge/search?keyword=" + "量子计算"))))
                .contains(restrictedPageId.intValue());
    }

    // ------------------------------------------------------------------
    // 5. 评论经 M09：本模块不建评论表
    // ------------------------------------------------------------------

    @Test
    void pageCommentsGoThroughCollabAndKnowledgeHasNoCommentTable() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "评论空间" + suffix, "internal");
        Long pageId = createPage(admin, spaceId, 0L, "评论演示" + suffix, "页面评论走统一评论能力。");

        Map<String, Object> comment = dataOf(post(admin, "/api/collab/comments", Map.of(
                "bizType", "knowledge_page",
                "bizId", pageId,
                "content", "页面评论：统一评论能力验证" + suffix
        )));
        Long commentId = ((Number) comment.get("id")).longValue();
        commentIds.add(commentId);

        Map<String, Object> commentPage = dataOf(get(admin,
                "/api/collab/comments?bizType=knowledge_page&bizId=" + pageId));
        assertThat(rowsOf(commentPage)).extracting(row -> ((Number) row.get("id")).longValue()).contains(commentId);

        Integer commentTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'
                """, Integer.class);
        assertThat(commentTables).as("全站仍只允许 M09 一张评论表").isEqualTo(1);
        String commentTableName = jdbcTemplate.queryForObject("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE '%comment%'
                """, String.class);
        assertThat(commentTableName).isEqualTo("collab_comment");

        Integer knowledgeCommentTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE 'knowledge%comment%'
                """, Integer.class);
        assertThat(knowledgeCommentTables).as("M07 不得建任何评论表").isZero();
    }

    // ------------------------------------------------------------------
    // 6. M11 正文切片
    // ------------------------------------------------------------------

    @Test
    void internalContentSliceFeedForM11() {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "切片空间" + suffix, "internal");
        String content = "第一段落：知识与文档切片接口，供智能问答按页读取。第二段落：切片只返回正文片段，不包含向量。";
        Long pageId = createPage(admin, spaceId, 0L, "切片演示" + suffix, content);

        Map<String, Object> slice = dataOf(get(admin,
                "/api/knowledge/internal/pages/" + pageId + "/content?offset=5&limit=12"));
        assertThat(((Number) slice.get("pageId")).longValue()).isEqualTo(pageId);
        assertThat(String.valueOf(slice.get("content"))).hasSize(12);
        assertThat(((Number) slice.get("totalLength")).intValue()).isEqualTo(content.length());
        assertThat(slice).containsEntry("truncated", true);

        PageContentSlice direct = contentProvider.sliceContent(pageId, 0, 6);
        assertThat(direct.content()).isEqualTo(content.substring(0, 6));
        assertThat(direct.pageTitle()).contains("切片演示");

        HttpHeaders chensy = bearerHeaders(login("chensy", "admin123"));
        assertThat(get(chensy, "/api/knowledge/internal/pages/" + pageId + "/content")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // 7. 文档中心：只存文件 ID + 引用计数
    // ------------------------------------------------------------------

    @Test
    void attachmentReferenceCountsPlatformFileWithoutCopying() throws IOException {
        HttpHeaders admin = adminHeaders();
        String suffix = uniqueSuffix();
        Long spaceId = createSpace(admin, "文档空间" + suffix, "internal");
        Long pageId = createPage(admin, spaceId, 0L, "文档中心" + suffix, "附件走统一上传与引用计数。");
        Long fileId = uploadFile(admin, "知识附件" + suffix + ".txt", ("附件内容" + suffix).getBytes(StandardCharsets.UTF_8));
        fileIds.add(fileId);

        Map<String, Object> attach = dataOf(post(admin, "/api/knowledge/files", Map.of(
                "spaceId", spaceId, "pageId", pageId, "fileId", fileId, "fileTitle", "知识附件" + suffix
        )));
        Long referenceId = ((Number) attach.get("id")).longValue();

        assertThat(jdbcTemplate.queryForObject("SELECT ref_count FROM sys_file WHERE id = ?", Long.class, fileId))
                .as("引用附件后平台文件引用计数 +1").isEqualTo(1L);
        List<Map<String, Object>> files = listOf(get(admin, "/api/knowledge/files?pageId=" + pageId));
        assertThat(files).extracting(row -> ((Number) row.get("fileId")).longValue()).contains(fileId);

        ResponseEntity<byte[]> download = restTemplate.exchange(
                "/api/knowledge/files/" + referenceId + "/download",
                HttpMethod.GET, new HttpEntity<>(admin), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(download.getBody(), StandardCharsets.UTF_8)).contains("附件内容" + suffix);

        assertThat(delete(admin, "/api/knowledge/files/" + referenceId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT ref_count FROM sys_file WHERE id = ?", Long.class, fileId))
                .as("移除引用后计数归零").isZero();

        assertThat(delete(admin, "/api/system/files/" + fileId).getStatusCode()).isEqualTo(HttpStatus.OK);
        fileIds.remove(fileId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_file WHERE id = ?", Integer.class, fileId)).isZero();
    }

    // ------------------------------------------------------------------
    // 8. 跨模块边界：M07 不直连其他业务表、不写评论表
    // ------------------------------------------------------------------

    @Test
    void knowledgePackageHasNoCrossModuleSql() throws IOException {
        Path knowledgeRoot = Paths.get("..", "teamflow-system", "src", "main", "java", "com", "teamflow", "knowledge");
        assertThat(Files.isDirectory(knowledgeRoot)).as("应能定位 M07 源码目录").isTrue();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(knowledgeRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.matches("(?is).*(INTO\\s+collab_comment|FROM\\s+task_|FROM\\s+okr_|FROM\\s+perf_).*")) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertThat(violations).as("M07 不得直连其他模块业务表或写评论表").isEmpty();
    }

    // ------------------------------------------------------------------
    // 请求辅助
    // ------------------------------------------------------------------

    private Long createSpace(HttpHeaders headers, String name, String visibility) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceName", name);
        body.put("spaceCode", "it_" + Math.abs(System.nanoTime()));
        body.put("spaceDesc", "集成测试空间");
        body.put("visibility", visibility);
        Long id = idOf(post(headers, "/api/knowledge/spaces", body));
        spaceIds.add(id);
        return id;
    }

    private Long createNode(HttpHeaders headers, Long spaceId, Long parentId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("parentId", parentId);
        body.put("nodeName", name);
        body.put("visibility", "inherit");
        Long id = idOf(post(headers, "/api/knowledge/nodes", body));
        assertThat(id).isNotNull();
        return id;
    }

    private Long createPage(HttpHeaders headers, Long spaceId, Long nodeId, String title, String content) {
        return createPage(headers, spaceId, nodeId, title, content, "inherit");
    }

    private Long createPage(HttpHeaders headers, Long spaceId, Long nodeId, String title, String content, String visibility) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("nodeId", nodeId);
        body.put("pageTitle", title);
        body.put("pageType", "wiki");
        body.put("tags", "集成测试,知识库");
        body.put("content", content);
        body.put("visibility", visibility);
        body.put("status", "published");
        Long id = idOf(post(headers, "/api/knowledge/pages", body));
        pageIds.add(id);
        return id;
    }

    private Map<String, Object> pageBody(Long spaceId, Long nodeId, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("nodeId", nodeId);
        body.put("pageTitle", title);
        body.put("pageType", "wiki");
        body.put("content", content);
        return body;
    }

    private Map<String, Object> pageUpdateBody(String content, String changeSummary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("changeSummary", changeSummary);
        return body;
    }

    private void grant(HttpHeaders headers, String resourceType, Long resourceId, Long subjectId, String permission) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceType", resourceType);
        body.put("resourceId", resourceId);
        body.put("subjectType", "user");
        body.put("subjectId", subjectId);
        body.put("permission", permission);
        assertThat(post(headers, "/api/knowledge/acls", body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long uploadFile(HttpHeaders headers, String filename, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/system/files", HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) dataOf(response).get("id")).longValue();
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
    private List<Map<String, Object>> rowsOf(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", 200);
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultItems(Map<String, Object> search) {
        Object results = search.get("results");
        return results instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private List<Integer> resultPageIds(Map<String, Object> search) {
        return resultItems(search).stream().map(item -> ((Number) item.get("pageId")).intValue()).toList();
    }

    private Long idOf(ResponseEntity<Map<String, Object>> response) {
        Object id = dataOf(response).get("id");
        return id == null ? null : ((Number) id).longValue();
    }
}
