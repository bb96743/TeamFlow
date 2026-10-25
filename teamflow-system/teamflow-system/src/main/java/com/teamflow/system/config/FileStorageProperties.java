package com.teamflow.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.List;

/**
 * 统一文件上传配置（G-1）。
 *
 * <p>配置前缀 {@code teamflow.file}，对应 {@code application.yml}：</p>
 * <pre>
 * teamflow:
 *   file:
 *     storage-path: ./data/uploads      # 本地存储根目录（生产建议绝对路径，如 /data/teamflow/uploads）
 *     max-size: 20MB                    # 单文件大小上限（与 spring.servlet.multipart 保持一致）
 *     denied-extensions: [jsp, exe, ...] # 危险后缀黑名单（始终生效）
 *     allowed-extensions: []            # 可选白名单；非空时只允许列表内后缀
 * </pre>
 *
 * <p>{@code storage_provider} 目前固定 {@code local}，预留对象存储扩展点。</p>
 */
@Component
@ConfigurationProperties(prefix = "teamflow.file")
public class FileStorageProperties {

    /** 本地存储根目录（相对路径按进程工作目录解析）。 */
    private String storagePath = "./data/uploads";

    /** 单文件大小上限。 */
    private DataSize maxSize = DataSize.ofMegabytes(20);

    /** 危险后缀黑名单（小写、不带点）；始终生效。 */
    private List<String> deniedExtensions = List.of(
            "jsp", "jspx", "exe", "sh", "bat", "cmd", "dll", "com", "msi", "scr", "vbs", "js",
            "jar", "war", "class", "php", "asp", "aspx");

    /** 可选白名单：非空时只允许列表内后缀（在黑名单之后生效）；为空表示不做白名单限制。 */
    private List<String> allowedExtensions = List.of();

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public DataSize getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(DataSize maxSize) {
        this.maxSize = maxSize;
    }

    public List<String> getDeniedExtensions() {
        return deniedExtensions;
    }

    public void setDeniedExtensions(List<String> deniedExtensions) {
        this.deniedExtensions = deniedExtensions;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    /** 规范化后的存储根目录（绝对路径）。 */
    public Path storageRoot() {
        return Path.of(storagePath).toAbsolutePath().normalize();
    }
}
