package com.teamflow.system.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.system.config.FileStorageProperties;
import com.teamflow.system.domain.vo.FileDownload;
import com.teamflow.system.domain.vo.FileUploadResponse;
import com.teamflow.system.mapper.SysFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一文件上传服务（G-1，平台能力，不属业务域）。
 *
 * <p><b>安全约束</b>（缺口工单 G-1 硬要求）：</p>
 * <ul>
 *   <li>后缀黑名单（`.jsp/.jspx/.exe/.sh/.bat/.cmd/.dll` 等）始终拒绝，可选白名单叠加；</li>
 *   <li>大小上限 20MB（{@code teamflow.file.max-size}，与 {@code spring.servlet.multipart} 同源）；</li>
 *   <li>存储名一律 UUID 重命名（不继承客户端文件名），按 {@code yyyy/MM/dd} 分片，防路径穿越；</li>
 *   <li>下载必须经鉴权接口 {@code GET /api/system/files/{id}}，**不注册静态目录直出**；</li>
 *   <li>引用计数 {@code ref_count} 由业务模块通过 {@link #incrementRef}/{@link #decrementRef} 维护，
 *       {@code ref_count > 0} 时拒绝删除。</li>
 * </ul>
 *
 * <p><b>数据边界</b>：M00 只维护 {@code sys_file} 与本地磁盘；业务模块只在自身表里存 file id 引用，
 * 不得自建文件表或本地路径（M03 RULE R5、M07 RULE §二）。</p>
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final String PROVIDER_LOCAL = "local";
    private static final String DOWNLOAD_URL_PREFIX = "/api/system/files/";

    private final SysFileMapper fileMapper;
    private final FileStorageProperties properties;
    private final Path storageRoot;

    public FileService(SysFileMapper fileMapper, FileStorageProperties properties) {
        this.fileMapper = fileMapper;
        this.properties = properties;
        this.storageRoot = properties.storageRoot();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建文件存储目录：" + storageRoot, exception);
        }
    }

    /**
     * 上传文件：校验 → UUID 重命名落盘 → 写元数据。
     *
     * @param file    上传文件（multipart）
     * @param bizType 业务类型（可空，如 task / kb_page）
     * @param bizId   业务对象 ID（可空）
     */
    public FileUploadResponse upload(MultipartFile file, String bizType, Long bizId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String originalName = FileStorageSupport.normalizeOriginalName(file.getOriginalFilename());
        String extension = FileStorageSupport.extensionOf(originalName);
        FileStorageSupport.validate(extension, file.getSize(), properties);

        String storageName = FileStorageSupport.storageName(extension);
        String relativePath = FileStorageSupport.datePath(LocalDate.now())
                .resolve(storageName).toString().replace('\\', '/');
        Path target = FileStorageSupport.resolveWithin(storageRoot, relativePath);

        try {
            Files.createDirectories(target.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BusinessException("文件保存失败：" + exception.getMessage());
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("originalName", originalName);
        row.put("storageName", relativePath);
        row.put("url", DOWNLOAD_URL_PREFIX);
        row.put("contentType", FileStorageSupport.normalizeContentType(file.getContentType()));
        row.put("size", file.getSize());
        row.put("bizType", trimToNull(bizType));
        row.put("bizId", bizId);
        row.put("refCount", 0);
        row.put("storageProvider", PROVIDER_LOCAL);

        try {
            fileMapper.insertFile(row, currentUsername());
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }

        Long id = numberAsLong(row.get("id"));
        String url = DOWNLOAD_URL_PREFIX + id;
        fileMapper.updateUrl(id, url);
        log.info("文件上传成功：id={} originalName={} size={} storage={}", id, originalName, file.getSize(), relativePath);

        return new FileUploadResponse(id, originalName, file.getSize(),
                (String) row.get("contentType"), (String) row.get("bizType"), bizId, url);
    }

    /**
     * 下载前置校验：返回磁盘资源与响应元数据。
     *
     * <p>只接受元数据里的 {@code storage_name}（服务端生成），并经 {@link FileStorageSupport#resolveWithin}
     * 校验仍在存储根目录内，客户端无法控制磁盘路径。</p>
     */
    public FileDownload download(Long id) {
        Map<String, Object> record = requireFile(id);
        String storageName = String.valueOf(record.get("storageName"));
        Path target = FileStorageSupport.resolveWithin(storageRoot, storageName);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException("文件内容不存在（可能已被清理）");
        }
        String contentType = record.get("contentType") == null
                ? "application/octet-stream"
                : String.valueOf(record.get("contentType"));
        Resource resource = new FileSystemResource(target);
        return new FileDownload(resource, String.valueOf(record.get("originalName")),
                contentType, numberAsLong(record.get("size")));
    }

    /**
     * 删除文件：引用计数 &gt; 0 拒绝；归零后删除元数据并清理磁盘文件。
     */
    public void delete(Long id) {
        Map<String, Object> record = requireFile(id);
        long refCount = numberAsLong(record.get("refCount"));
        if (refCount > 0) {
            throw new BusinessException("文件仍被业务引用（refCount=" + refCount + "），不能删除");
        }
        int deleted = fileMapper.deleteIfUnreferenced(id);
        if (deleted == 0) {
            throw new BusinessException("文件不存在或引用计数已变化，请重试");
        }
        Path target = FileStorageSupport.resolveWithin(storageRoot, String.valueOf(record.get("storageName")));
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            // 元数据已删除，磁盘文件清理失败只记录告警（避免"删不掉"阻塞业务；孤儿文件可由运维清理）
            log.warn("文件元数据已删除，但磁盘清理失败：id={} path={}", id, target, exception);
        }
        log.info("文件删除成功：id={}", id);
    }

    /** 引用计数 +1（业务模块关联附件时调用）。 */
    public int incrementRef(Long id) {
        int rows = fileMapper.incrementRef(id, currentUsername());
        if (rows == 0) {
            throw new BusinessException("文件不存在");
        }
        return rows;
    }

    /** 引用计数 -1（下限 0；文件不存在时抛业务异常，已为 0 时幂等返回 0）。 */
    public int decrementRef(Long id) {
        int rows = fileMapper.decrementRef(id, currentUsername());
        if (rows > 0) {
            return rows;
        }
        if (fileMapper.selectFile(id) == null) {
            throw new BusinessException("文件不存在");
        }
        return 0;
    }

    private Map<String, Object> requireFile(Long id) {
        if (id == null) {
            throw new BusinessException("文件 ID 不能为空");
        }
        Map<String, Object> record = fileMapper.selectFile(id);
        if (record == null) {
            throw new BusinessException("文件不存在");
        }
        return record;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("上传失败回滚磁盘文件失败：{}", path, exception);
        }
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private long numberAsLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
