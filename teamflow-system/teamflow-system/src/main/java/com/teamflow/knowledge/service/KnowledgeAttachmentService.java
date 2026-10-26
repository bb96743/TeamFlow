package com.teamflow.knowledge.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.domain.dto.KnowledgeFileWriteRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.mapper.KnowledgeFileMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.system.domain.vo.FileDownload;
import com.teamflow.system.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M07 文档中心附件引用服务（1.2.2）。
 *
 * <p><b>附件不复制</b>：文件本体由平台统一上传能力维护，本模块只保存平台文件 ID，
 * 通过 M00 的文件服务增减引用计数；删除引用归零后平台文件才可被清理。</p>
 *
 * <p>下载统一走 M07 的鉴权入口（校验知识页面/空间读权限后再取平台文件流），
 * 业务用户无需额外持有平台文件管理权限，也避免静态地址直出。</p>
 */
@Service
public class KnowledgeAttachmentService extends KnowledgeServiceSupport {

    private static final Set<String> CATEGORIES = Set.of("attachment", "sop_doc", "image", "other");

    private final FileService fileService;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeAccessService access;

    public KnowledgeAttachmentService(
            KnowledgePlatformMapper platformMapper,
            FileService fileService,
            KnowledgeFileMapper fileMapper,
            KnowledgeAccessService access
    ) {
        super(platformMapper);
        this.fileService = fileService;
        this.fileMapper = fileMapper;
        this.access = access;
    }

    public List<Map<String, Object>> list(Long spaceId, Long pageId) {
        if (pageId != null) {
            access.requirePageRead(pageId);
        } else if (spaceId != null) {
            access.requireSpaceRead(spaceId);
        } else {
            throw new BusinessException("请指定空间 ID 或页面 ID");
        }
        return fileMapper.selectFiles(spaceId, pageId);
    }

    @Transactional
    public KnowledgeIdResponse attach(KnowledgeFileWriteRequest request) {
        if (request == null) {
            throw new BusinessException("附件引用内容不能为空");
        }
        Long pageId = request.getPageId();
        Long spaceId = request.getSpaceId();
        if (pageId != null) {
            Map<String, Object> page = access.requirePageRow(pageId);
            spaceId = numberAsLong(page.get("spaceId"));
            access.requirePageWrite(pageId);
        } else {
            if (spaceId == null) {
                throw new BusinessException("所属空间不能为空");
            }
            access.requireSpaceWrite(spaceId);
        }
        Long fileId = request.getFileId();
        if (fileId == null) {
            throw new BusinessException("平台文件 ID 不能为空（请先通过统一上传接口上传）");
        }
        String category = defaultString(request.getCategory(), "attachment");
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("附件分类只能为 attachment、sop_doc、image 或 other");
        }
        String title = defaultString(request.getFileTitle(), "附件-" + fileId);
        // 引用计数 +1：文件不存在时平台服务抛业务异常；被引用期间文件不可删除
        fileService.incrementRef(fileId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceId", spaceId);
        row.put("pageId", pageId);
        row.put("fileId", fileId);
        row.put("fileTitle", title);
        row.put("category", category);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        try {
            fileMapper.insertFile(row, currentUsername());
        } catch (RuntimeException exception) {
            fileService.decrementRef(fileId);
            throw exception;
        }
        return new KnowledgeIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void remove(Long id) {
        Map<String, Object> row = requireFileRow(id);
        Long pageId = numberAsLong(row.get("pageId"));
        if (pageId != null) {
            access.requirePageWrite(pageId);
        } else {
            access.requireSpaceWrite(numberAsLong(row.get("spaceId")));
        }
        Long fileId = numberAsLong(row.get("fileId"));
        fileMapper.softDeleteFile(id, currentUsername());
        if (fileId != null) {
            fileService.decrementRef(fileId);
        }
    }

    /** 下载前置校验：知识页面/空间读权限 → 平台文件流与元数据。 */
    public FileDownload download(Long id) {
        Map<String, Object> row = requireFileRow(id);
        Long pageId = numberAsLong(row.get("pageId"));
        if (pageId != null) {
            access.requirePageRead(pageId);
        } else {
            access.requireSpaceRead(numberAsLong(row.get("spaceId")));
        }
        return fileService.download(numberAsLong(row.get("fileId")));
    }

    private Map<String, Object> requireFileRow(Long id) {
        Map<String, Object> row = fileMapper.selectFileById(id);
        if (row == null) {
            throw new BusinessException("附件引用不存在或已移除");
        }
        return row;
    }
}
