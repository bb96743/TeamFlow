package com.teamflow.system.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.system.config.FileStorageProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 统一文件上传的纯逻辑支撑（G-1）：文件名规范化、后缀/大小校验、存储名生成、路径防穿越。
 *
 * <p>抽成无状态静态工具，便于单测覆盖安全规则（不启动 Spring 上下文）。</p>
 */
public final class FileStorageSupport {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_CONTENT_TYPE_LENGTH = 128;

    private FileStorageSupport() {
    }

    /**
     * 规范化原始文件名：去掉客户端可能带上的路径（含 Windows 反斜杠），拒绝空名/.{@code ..}。
     * 这是防路径穿越的第一道防线（存储名另外由 UUID 生成，不继承客户端文件名）。
     */
    public static String normalizeOriginalName(String raw) {
        String cleaned = StringUtils.cleanPath(raw == null ? "" : raw).replace('\\', '/');
        String base = cleaned.substring(cleaned.lastIndexOf('/') + 1).trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            throw new BusinessException("文件名不合法");
        }
        if (base.length() > MAX_NAME_LENGTH) {
            int dot = base.lastIndexOf('.');
            String extension = dot > 0 ? base.substring(dot) : "";
            int keep = Math.max(MAX_NAME_LENGTH - extension.length(), 1);
            base = base.substring(0, keep) + extension;
        }
        return base;
    }

    /** 取小写后缀（不含点）；无后缀返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 校验文件是否允许上传：大小上限、危险后缀黑名单（始终生效）、可选白名单。
     */
    public static void validate(String extension, long size, FileStorageProperties properties) {
        if (size <= 0) {
            throw new BusinessException("上传文件不能为空");
        }
        if (size > properties.getMaxSize().toBytes()) {
            throw new BusinessException("文件大小超过上限 " + properties.getMaxSize().toMegabytes() + "MB");
        }
        List<String> denied = properties.getDeniedExtensions();
        if (extension != null && denied != null && denied.contains(extension)) {
            throw new BusinessException("不允许上传 ." + extension + " 类型文件（可执行/脚本类后缀禁止上传）");
        }
        List<String> allowed = properties.getAllowedExtensions();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(extension)) {
            throw new BusinessException("不允许上传 ." + extension + " 类型文件（仅允许：" + String.join(" / ", allowed) + "）");
        }
    }

    /** 生成存储文件名：UUID（无连字符）+ 原后缀，不继承客户端文件名。 */
    public static String storageName(String extension) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return extension == null || extension.isEmpty() ? uuid : uuid + "." + extension;
    }

    /** 按日期分片的相对目录：yyyy/MM/dd（避免单目录文件过多）。 */
    public static Path datePath(LocalDate date) {
        return Path.of(String.format("%04d", date.getYear()),
                String.format("%02d", date.getMonthValue()),
                String.format("%02d", date.getDayOfMonth()));
    }

    /**
     * 把相对路径解析到存储根目录下，并校验结果仍在根目录内（防路径穿越的最后一道防线）。
     */
    public static Path resolveWithin(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException("非法的文件存储路径");
        }
        return target;
    }

    /** content_type 字段长度保护（DB 为 VARCHAR(128)）。 */
    public static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String trimmed = contentType.trim();
        return trimmed.length() <= MAX_CONTENT_TYPE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_CONTENT_TYPE_LENGTH);
    }
}
