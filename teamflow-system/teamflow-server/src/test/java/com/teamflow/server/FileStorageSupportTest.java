package com.teamflow.server;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.system.config.FileStorageProperties;
import com.teamflow.system.service.FileStorageSupport;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G-1 文件上传安全规则单元测试（纯 JUnit，不启动 Spring 上下文、不触发数据库迁移）。
 */
class FileStorageSupportTest {

    private static FileStorageProperties properties() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setMaxSize(DataSize.ofMegabytes(20));
        properties.setDeniedExtensions(List.of("jsp", "jspx", "exe", "sh", "bat", "cmd", "dll"));
        properties.setAllowedExtensions(List.of());
        return properties;
    }

    @Test
    void stripsClientSidePathFromOriginalName() {
        assertThat(FileStorageSupport.normalizeOriginalName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileStorageSupport.normalizeOriginalName("C:\\Users\\x\\报表.xlsx")).isEqualTo("报表.xlsx");
        assertThat(FileStorageSupport.normalizeOriginalName("docs/季度/计划.pdf")).isEqualTo("计划.pdf");
        assertThat(FileStorageSupport.normalizeOriginalName("  报告.docx ")).isEqualTo("报告.docx");
    }

    @Test
    void rejectsEmptyOrDotOnlyName() {
        assertThatThrownBy(() -> FileStorageSupport.normalizeOriginalName(null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("文件名不合法");
        assertThatThrownBy(() -> FileStorageSupport.normalizeOriginalName(".."))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FileStorageSupport.normalizeOriginalName("dir/.."))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void extensionIsLowercaseWithoutDot() {
        assertThat(FileStorageSupport.extensionOf("A.JSP")).isEqualTo("jsp");
        assertThat(FileStorageSupport.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileStorageSupport.extensionOf("noext")).isEmpty();
        assertThat(FileStorageSupport.extensionOf("trailing.")).isEmpty();
        assertThat(FileStorageSupport.extensionOf(null)).isEmpty();
    }

    @Test
    void deniesDangerousExtensionsEvenForUppercaseName() {
        FileStorageProperties properties = properties();
        for (String extension : List.of("jsp", "jspx", "exe", "sh", "bat", "cmd", "dll")) {
            assertThatThrownBy(() -> FileStorageSupport.validate(extension, 1024, properties))
                    .as("应拒绝 .%s", extension)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许上传");
        }
        assertThatThrownBy(() -> FileStorageSupport.validate(
                FileStorageSupport.extensionOf("shell.EXE"), 1024, properties))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOversizedFileAndEmptyFile() {
        FileStorageProperties properties = properties();
        long max = properties.getMaxSize().toBytes();

        assertThatThrownBy(() -> FileStorageSupport.validate("pdf", max + 1, properties))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("大小超过上限");
        assertThatThrownBy(() -> FileStorageSupport.validate("pdf", 0, properties))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

        FileStorageSupport.validate("pdf", max, properties);
    }

    @Test
    void allowsNormalBusinessExtensions() {
        FileStorageProperties properties = properties();
        for (String extension : List.of("pdf", "png", "jpg", "docx", "xlsx", "zip", "")) {
            FileStorageSupport.validate(extension, 1024, properties);
        }
    }

    @Test
    void optionalWhitelistIsAppliedOnTopOfDenylist() {
        FileStorageProperties properties = properties();
        properties.setAllowedExtensions(List.of("png", "jpg"));

        assertThatThrownBy(() -> FileStorageSupport.validate("pdf", 1024, properties))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅允许");
        assertThatThrownBy(() -> FileStorageSupport.validate("exe", 1024, properties))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许上传");
        FileStorageSupport.validate("png", 1024, properties);
    }

    @Test
    void storageNameIsUuidAndKeepsOnlyExtension() {
        String name = FileStorageSupport.storageName("pdf");

        assertThat(name).matches("[0-9a-f]{32}\\.pdf");
        assertThat(name).doesNotContain("..").doesNotContain("/").doesNotContain("\\");
        assertThat(FileStorageSupport.storageName("")).matches("[0-9a-f]{32}");
    }

    @Test
    void datePathIsYearMonthDaySegments() {
        Path path = FileStorageSupport.datePath(LocalDate.of(2026, 10, 25));

        assertThat(path.toString().replace('\\', '/')).isEqualTo("2026/10/25");
    }

    @Test
    void resolveWithinRejectsTraversalOutsideRoot() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "teamflow-root-check");

        Path inside = FileStorageSupport.resolveWithin(root, "2026/10/25/abc.pdf");
        assertThat(inside.startsWith(root.toAbsolutePath().normalize())).isTrue();

        assertThatThrownBy(() -> FileStorageSupport.resolveWithin(root, "../evil.txt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件存储路径");
        assertThatThrownBy(() -> FileStorageSupport.resolveWithin(root, "a/../../evil.txt"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void contentTypeIsTruncatedToColumnLength() {
        assertThat(FileStorageSupport.normalizeContentType(null)).isNull();
        assertThat(FileStorageSupport.normalizeContentType("  ")).isNull();
        assertThat(FileStorageSupport.normalizeContentType(" text/plain ")).isEqualTo("text/plain");
        assertThat(FileStorageSupport.normalizeContentType("a".repeat(200))).hasSize(128);
    }
}
