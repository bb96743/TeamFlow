package com.teamflow.server;

import com.teamflow.system.config.FileStorageProperties;
import com.teamflow.system.service.FileService;
import com.teamflow.system.service.FileStorageSupport;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-1 统一文件上传集成测试。
 *
 * <p>覆盖验收要求：上传 → 下载成功；未认证 401、无权限 403；危险后缀被拒；超 20MB 被拒；
 * {@code ref_count>0} 删除被拒、归零后删除成功且磁盘文件清理；UUID 重命名（不继承原始名）。</p>
 *
 * <p>存储目录覆盖为系统临时目录，避免测试文件落到项目根目录。</p>
 */
@SpringBootTest(
        classes = TeamFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "teamflow.file.storage-path=${java.io.tmpdir}/teamflow-file-it",
        "teamflow.file.max-size=20MB",
        "spring.servlet.multipart.max-file-size=20MB",
        "spring.servlet.multipart.max-request-size=20MB",
        // 必须大于文件上限：否则超限请求会被 Tomcat 直接断连，客户端拿不到 400 响应
        "server.tomcat.max-swallow-size=30MB"
})
class FileUploadApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileStorageProperties fileStorageProperties;

    @AfterEach
    void cleanupUploadedFiles() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, storage_name FROM sys_file WHERE original_name LIKE 'it-file-%'");
        for (Map<String, Object> row : rows) {
            String storageName = String.valueOf(row.get("storage_name"));
            try {
                Files.deleteIfExists(FileStorageSupport.resolveWithin(fileStorageProperties.storageRoot(), storageName));
            } catch (Exception ignored) {
                // 清理失败不影响断言
            }
            jdbcTemplate.update("DELETE FROM sys_file WHERE id = ?", ((Number) row.get("id")).longValue());
        }
    }

    // ------------------------------------------------------------------
    // ① 上传 → 下载 → 引用计数 → 删除（含磁盘清理）
    // ------------------------------------------------------------------

    @Test
    void uploadDownloadAndDeleteChainRespectsRefCount() throws Exception {
        HttpHeaders headers = adminHeaders();
        String filename = "it-file-报告-" + uniqueSuffix() + ".txt";
        byte[] content = "汇流 TeamFlow 统一文件上传内容".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> uploadResponse = upload(headers, filename, content);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> uploaded = dataOf(uploadResponse);
        long fileId = ((Number) uploaded.get("id")).longValue();
        assertThat(uploaded).containsEntry("originalName", filename);
        assertThat(((Number) uploaded.get("size")).longValue()).isEqualTo(content.length);
        assertThat(String.valueOf(uploaded.get("url"))).isEqualTo("/api/system/files/" + fileId);

        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                "/api/system/files/" + fileId, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isEqualTo(content);
        assertThat(String.valueOf(downloadResponse.getHeaders().getContentDisposition()))
                .contains("attachment");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT storage_name, ref_count, storage_provider FROM sys_file WHERE id = ?", fileId);
        String storageName = String.valueOf(row.get("storage_name"));
        assertThat(storageName).as("存储名必须是 UUID，不继承客户端文件名")
                .doesNotContain("报告").doesNotContain("it-file-").matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.txt");
        assertThat(((Number) row.get("ref_count")).intValue()).isZero();
        assertThat(row.get("storage_provider")).isEqualTo("local");
        Path storedPath = FileStorageSupport.resolveWithin(fileStorageProperties.storageRoot(), storageName);
        assertThat(Files.isRegularFile(storedPath)).as("上传后磁盘文件应存在").isTrue();

        // 引用计数 > 0 → 删除被拒
        assertThat(fileService.incrementRef(fileId)).isEqualTo(1);
        ResponseEntity<Map<String, Object>> blocked = delete(headers, fileId);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(blocked.getBody().get("message"))).contains("引用");
        assertThat(Files.isRegularFile(storedPath)).as("被引用时不得清理磁盘").isTrue();

        // 归零 → 删除成功，磁盘与元数据都清理
        assertThat(fileService.decrementRef(fileId)).isEqualTo(1);
        assertThat(delete(headers, fileId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_file WHERE id = ?", Integer.class, fileId)).isZero();
        assertThat(Files.exists(storedPath)).as("删除后磁盘文件应清理").isFalse();
    }

    // ------------------------------------------------------------------
    // ② 鉴权：未认证 401、无权限 403
    // ------------------------------------------------------------------

    @Test
    void downloadRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/system/files/1", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void downloadRequiresPermission() {
        HttpHeaders headers = bearerHeaders(login("chensy", "admin123"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/system/files/1", HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void uploadRequiresPermission() {
        HttpHeaders headers = bearerHeaders(login("chensy", "admin123"));

        ResponseEntity<Map<String, Object>> response = upload(headers, "it-file-denied.txt",
                "x".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // ③ 安全校验：危险后缀、超大小上限
    // ------------------------------------------------------------------

    @Test
    void dangerousExtensionsAreRejected() {
        HttpHeaders headers = adminHeaders();

        for (String filename : List.of("it-file-shell.jsp", "it-file-malware.exe", "it-file-run.SH",
                "it-file-batch.bat", "it-file-lib.dll")) {
            ResponseEntity<Map<String, Object>> response = upload(headers, filename,
                    "dangerous".getBytes(StandardCharsets.UTF_8));
            assertThat(response.getStatusCode()).as("%s 应被拒绝", filename).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(response.getBody().get("message"))).contains("不允许上传");
        }
    }

    @Test
    void oversizedFileIsRejected() {
        // 仅比上限多 1KB：服务端在 20MB 处停止读取后，剩余字节可被 socket 缓冲吸收；
        // 配合下面 uploadWithContentLength 的「已知 Content-Length（非 chunked）」写入，
        // 客户端能完整写完请求体并读到 400 响应（用 MultiValueMap 会走 chunked，服务端提前
        // 返回时 JDK HttpClient 抛 "chunked transfer encoding, state: READING_LENGTH"，拿不到响应）。
        byte[] tooLarge = new byte[20 * 1024 * 1024 + 1024];

        ResponseEntity<Map<String, Object>> response = uploadWithContentLength(
                adminHeaders(), "it-file-big.bin", tooLarge);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("message"))).contains("大小限制");
    }

    // ------------------------------------------------------------------
    // 上传辅助
    // ------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> upload(HttpHeaders headers, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/system/files", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), MAP_TYPE);
    }

    /**
     * 手写 multipart body 并显式设置 Content-Length（非 chunked）。
     *
     * <p>用于超限场景：multipart 解析超限时服务端会立即返回 400 并停止读取请求体；
     * 若客户端用 chunked 分块发送，JDK HttpClient 会在写入中断时报
     * {@code chunked transfer encoding, state: READING_LENGTH}，从而拿不到 400 响应。
     * 已知长度时客户端可先写完请求体再读响应，能稳定验证「超限返回明确 400 而非 500」。</p>
     */
    private ResponseEntity<Map<String, Object>> uploadWithContentLength(
            HttpHeaders headers, String filename, byte[] content
    ) {
        String boundary = "----TeamFlowG1Boundary" + System.nanoTime();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
        requestHeaders.setContentLength(body.length);
        return restTemplate.exchange("/api/system/files", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), MAP_TYPE);
    }

    private ResponseEntity<Map<String, Object>> delete(HttpHeaders headers, long fileId) {
        return restTemplate.exchange("/api/system/files/" + fileId, HttpMethod.DELETE,
                new HttpEntity<>(headers), MAP_TYPE);
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

    private String uniqueSuffix() {
        return String.valueOf(System.nanoTime());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).containsEntry("code", 200);
        return (Map<String, Object>) response.getBody().get("data");
    }
}
