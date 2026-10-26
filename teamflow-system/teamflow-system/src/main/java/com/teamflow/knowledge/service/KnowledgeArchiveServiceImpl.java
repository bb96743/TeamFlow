package com.teamflow.knowledge.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;
import com.teamflow.knowledge.mapper.KnowledgeNodeMapper;
import com.teamflow.knowledge.mapper.KnowledgePageMapper;
import com.teamflow.knowledge.mapper.KnowledgePageVersionMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.knowledge.mapper.KnowledgeSpaceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M07 跨模块归档服务实现（M06 会议纪要 / M14 项目复盘）。
 *
 * <p><b>契约</b>：业务正文只存 {@code knowledge_page}，业务模块表里只保存返回的 {@code pageId}
 * （L0 §9.1 D-2026-10-26-11 结论三）。同一来源三要素重复归档幂等返回同一页面；
 * 业务方如需更新正文，置 {@code overwriteExisting=true} 生成新版本（历史版本保留）。</p>
 *
 * <p><b>权限边界</b>：本 Service 是可信内部调用（M06/M14 对本模块的调用），不重复校验调用方业务对象的
 * 可见性；页面创建后给操作人写入 manage 授权，归档空间默认可见性为「已登录可读」。</p>
 */
@Service
public class KnowledgeArchiveServiceImpl extends KnowledgeServiceSupport implements KnowledgeArchiveService {

    private static final String DEFAULT_SPACE_CODE = "archive";
    private static final int CONTENT_MAX_LENGTH = 1_000_000;

    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgePageMapper pageMapper;
    private final KnowledgePageVersionMapper versionMapper;
    private final KnowledgeAccessService access;

    public KnowledgeArchiveServiceImpl(
            KnowledgePlatformMapper platformMapper,
            KnowledgeSpaceMapper spaceMapper,
            KnowledgeNodeMapper nodeMapper,
            KnowledgePageMapper pageMapper,
            KnowledgePageVersionMapper versionMapper,
            KnowledgeAccessService access
    ) {
        super(platformMapper);
        this.spaceMapper = spaceMapper;
        this.nodeMapper = nodeMapper;
        this.pageMapper = pageMapper;
        this.versionMapper = versionMapper;
        this.access = access;
    }

    @Override
    @Transactional
    public KnowledgeArchiveResult archive(KnowledgeArchiveRequest request) {
        if (request == null) {
            throw new BusinessException("归档请求不能为空");
        }
        String sourceModule = limited(request.sourceModule(), 32, "归档来源模块不能为空（如 M06）");
        String sourceBizType = limited(request.sourceBizType(), 32, "归档来源业务类型不能为空（如 meeting_minutes）");
        Long sourceBizId = request.sourceBizId();
        if (sourceBizId == null) {
            throw new BusinessException("归档来源业务单据 ID 不能为空");
        }
        String title = required(request.title(), "归档页面标题不能为空");
        String content = required(request.content(), "归档正文不能为空");
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException("归档正文不能超过 " + CONTENT_MAX_LENGTH + " 个字符");
        }

        Map<String, Object> space = resolveSpace(request.spaceId());
        Long spaceId = numberAsLong(space.get("id"));
        Long nodeId = request.nodeId() == null ? 0L : request.nodeId();
        if (nodeId > 0) {
            Map<String, Object> node = nodeMapper.selectNodeById(nodeId);
            if (node == null || !spaceId.equals(numberAsLong(node.get("spaceId")))) {
                throw new BusinessException("归档目录不存在或不属于目标空间");
            }
        }
        Long operatorId = resolveOperatorId(request.operatorUserId());
        if (operatorId == null) {
            operatorId = numberAsLong(space.get("ownerId"));
        }
        String operatorName = resolveOperatorName(request.operatorName(), operatorId);
        String pageType = pageTypeOf(sourceBizType);
        String tags = joinTags(request.tags());
        String summary = trimToNull(request.summary());

        Map<String, Object> existing = pageMapper.selectPageBySource(sourceModule, sourceBizType, sourceBizId);
        if (existing != null) {
            if (!request.overwriteExisting()) {
                return result(existing, false);
            }
            return overwrite(existing, title, content, tags, summary, operatorId, operatorName);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceId", spaceId);
        row.put("nodeId", nodeId);
        row.put("pageTitle", title);
        row.put("pageType", pageType);
        row.put("tags", tags);
        row.put("summary", summary);
        row.put("content", content);
        row.put("contentFormat", "markdown");
        row.put("visibility", "inherit");
        row.put("status", "published");
        row.put("currentVersion", 1);
        row.put("ownerId", operatorId);
        row.put("sourceModule", sourceModule);
        row.put("sourceBizType", sourceBizType);
        row.put("sourceBizId", sourceBizId);
        row.put("sort", 0);
        row.put("remark", "由 " + sourceModule + " 归档（" + sourceBizType + "#" + sourceBizId + "）");
        try {
            pageMapper.insertPage(row, operatorName);
        } catch (DuplicateKeyException exception) {
            // 并发重复归档：唯一键兜底，返回已存在页面
            Map<String, Object> raced = pageMapper.selectPageBySource(sourceModule, sourceBizType, sourceBizId);
            if (raced == null) {
                throw exception;
            }
            return result(raced, false);
        }
        Long pageId = numberAsLong(row.get("id"));
        insertVersion(pageId, 1, title, content, tags,
                "由 " + sourceModule + " 归档创建（" + sourceBizType + "#" + sourceBizId + "）",
                operatorId, operatorName);
        access.grantOwner("page", pageId, operatorId);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", pageId);
        created.put("spaceId", spaceId);
        created.put("nodeId", nodeId);
        created.put("currentVersion", 1);
        created.put("pageType", pageType);
        return result(created, true);
    }

    @Override
    public Optional<Long> findArchivedPageId(String sourceModule, String sourceBizType, Long sourceBizId) {
        if (sourceModule == null || sourceBizType == null || sourceBizId == null) {
            return Optional.empty();
        }
        Map<String, Object> page = pageMapper.selectPageBySource(sourceModule, sourceBizType, sourceBizId);
        return page == null ? Optional.empty() : Optional.of(numberAsLong(page.get("id")));
    }

    private KnowledgeArchiveResult overwrite(
            Map<String, Object> existing, String title, String content, String tags, String summary,
            Long operatorId, String operatorName
    ) {
        Long pageId = numberAsLong(existing.get("id"));
        int newVersion = intOf(existing.get("currentVersion")) + 1;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", intOf(existing.get("nodeId")));
        row.put("pageTitle", title);
        row.put("pageType", String.valueOf(existing.get("pageType")));
        row.put("tags", tags);
        row.put("summary", summary == null ? existing.get("summary") : summary);
        row.put("content", content);
        row.put("contentFormat", "markdown");
        row.put("visibility", String.valueOf(existing.get("visibility")));
        row.put("status", String.valueOf(existing.get("status")));
        row.put("currentVersion", newVersion);
        row.put("sort", intOf(existing.get("sort")));
        row.put("remark", existing.get("remark"));
        pageMapper.updatePage(pageId, row, operatorName);
        insertVersion(pageId, newVersion, title, content, tags, "归档内容更新", operatorId, operatorName);
        Map<String, Object> refreshed = new LinkedHashMap<>(existing);
        refreshed.put("id", pageId);
        refreshed.put("currentVersion", newVersion);
        return result(refreshed, false);
    }

    private KnowledgeArchiveResult result(Map<String, Object> page, boolean created) {
        return new KnowledgeArchiveResult(
                numberAsLong(page.get("id")),
                numberAsLong(page.get("spaceId")),
                page.get("nodeId") == null ? 0L : numberAsLong(page.get("nodeId")),
                page.get("currentVersion") == null ? 1 : intOf(page.get("currentVersion")),
                page.get("pageType") == null ? "wiki" : String.valueOf(page.get("pageType")),
                created
        );
    }

    private Map<String, Object> resolveSpace(Long spaceId) {
        Map<String, Object> space = spaceId == null
                ? spaceMapper.selectSpaceByCode(DEFAULT_SPACE_CODE)
                : spaceMapper.selectSpaceById(spaceId);
        if (space == null) {
            throw new BusinessException(spaceId == null
                    ? "默认归档空间不存在（请确认已初始化归档空间）"
                    : "归档空间不存在：" + spaceId);
        }
        return space;
    }

    private Long resolveOperatorId(Long explicitUserId) {
        if (explicitUserId != null && platformMapper.countActiveUser(explicitUserId) > 0) {
            return explicitUserId;
        }
        return currentUserIdOrNull();
    }

    private String resolveOperatorName(String explicitName, Long operatorId) {
        String name = trimToNull(explicitName);
        if (name != null) {
            return name;
        }
        if (operatorId != null) {
            Map<String, Object> user = platformMapper.selectUserBrief(operatorId);
            if (user != null && user.get("username") != null) {
                return String.valueOf(user.get("username"));
            }
        }
        return currentUsername();
    }

    private void insertVersion(Long pageId, int versionNo, String title, String content, String tags,
                               String changeSummary, Long operatorId, String operatorName) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("pageId", pageId);
        version.put("versionNo", versionNo);
        version.put("pageTitle", title);
        version.put("content", content);
        version.put("contentFormat", "markdown");
        version.put("tags", tags);
        version.put("changeSummary", changeSummary);
        version.put("editorId", operatorId);
        version.put("editorName", operatorName);
        versionMapper.insertVersion(version, operatorName);
    }

    private String pageTypeOf(String sourceBizType) {
        String type = sourceBizType.toLowerCase();
        if (type.contains("meeting") || type.contains("minutes")) {
            return "minutes";
        }
        if (type.contains("retro") || type.contains("review")) {
            return "retro";
        }
        return "wiki";
    }

    private String limited(String value, int maxLength, String message) {
        String text = required(value, message);
        if (text.length() > maxLength) {
            throw new BusinessException(message + "（长度不能超过 " + maxLength + "）");
        }
        return text;
    }
}
