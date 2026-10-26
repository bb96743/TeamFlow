package com.teamflow.knowledge.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.domain.dto.KnowledgePageWriteRequest;
import com.teamflow.knowledge.domain.dto.KnowledgeVersionRollbackRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.domain.vo.PageContentSlice;
import com.teamflow.knowledge.mapper.KnowledgeFileMapper;
import com.teamflow.knowledge.mapper.KnowledgePageMapper;
import com.teamflow.knowledge.mapper.KnowledgePageVersionMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.knowledge.mapper.KnowledgeSpaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M07 页面服务：页面 CRUD、保存即版本化、版本历史与回滚、正文切片。
 *
 * <p>规则落地：</p>
 * <ul>
 *   <li>R1 保存即版本化：创建写入版本 1；每次保存写新版本快照（标题/正文/标签/变更摘要/编辑人），
 *       历史版本只追加不修改；回滚 = 用历史版本内容生成一条新版本（不覆盖历史）；</li>
 *   <li>R4 正文格式统一 Markdown；</li>
 *   <li>R7 删除为软删除并保留版本；有附件引用时先移除附件；</li>
 *   <li>权限：读取/编辑/管理分别要求页面授权链达到 read/write/manage（由访问控制服务判定）。</li>
 * </ul>
 */
@Service
public class KnowledgePageService extends KnowledgeServiceSupport implements KnowledgeContentProvider {

    private static final Set<String> PAGE_TYPES = Set.of("wiki", "sop", "decision", "minutes", "retro");
    private static final Set<String> VISIBILITIES = Set.of("inherit", "internal", "restricted");
    private static final Set<String> STATUSES = Set.of("draft", "published", "archived");
    private static final String FORMAT_MARKDOWN = "markdown";
    private static final int LIST_LIMIT = 2000;
    private static final int CONTENT_MAX_LENGTH = 1_000_000;
    private static final int SLICE_DEFAULT_LIMIT = 2000;
    private static final int SLICE_MAX_LIMIT = 8000;

    private final KnowledgePageMapper pageMapper;
    private final KnowledgePageVersionMapper versionMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeAccessService access;

    public KnowledgePageService(
            KnowledgePlatformMapper platformMapper,
            KnowledgePageMapper pageMapper,
            KnowledgePageVersionMapper versionMapper,
            KnowledgeFileMapper fileMapper,
            KnowledgeSpaceMapper spaceMapper,
            KnowledgeAccessService access
    ) {
        super(platformMapper);
        this.pageMapper = pageMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.spaceMapper = spaceMapper;
        this.access = access;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> list(
            PageQuery query, Long spaceId, Long nodeId, String pageType, String tag, String status, String keyword
    ) {
        List<Long> spaceIds;
        if (spaceId != null) {
            access.requireSpaceRead(spaceId);
            spaceIds = List.of(spaceId);
        } else {
            spaceIds = access.filterReadableSpaces(spaceMapper.selectSpaces(null, null)).stream()
                    .map(space -> numberAsLong(space.get("id")))
                    .filter(id -> id != null)
                    .toList();
        }
        Page page = pageOf(query);
        if (spaceIds.isEmpty()) {
            return PageResult.of(List.of(), 0, page.pageNum(), page.pageSize());
        }
        List<Map<String, Object>> candidates = pageMapper.selectPages(
                spaceIds, nodeId, trimToNull(pageType), trimToNull(tag), trimToNull(status), trimToNull(keyword), LIST_LIMIT);
        List<Map<String, Object>> visible = access.filterReadablePages(candidates);
        int from = Math.min(page.offset(), visible.size());
        int to = Math.min(from + page.pageSize(), visible.size());
        return PageResult.of(new ArrayList<>(visible.subList(from, to)), visible.size(), page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long pageId) {
        access.requirePageRead(pageId);
        Map<String, Object> page = access.requirePageRow(pageId);
        page.put("access", access.describePageAccess(page));
        return page;
    }

    public List<Map<String, Object>> versions(Long pageId) {
        access.requirePageRead(pageId);
        access.requirePageRow(pageId);
        return versionMapper.selectVersions(pageId);
    }

    public Map<String, Object> versionDetail(Long pageId, Integer versionNo) {
        access.requirePageRead(pageId);
        Map<String, Object> version = versionMapper.selectVersion(pageId, versionNo);
        if (version == null) {
            throw new BusinessException("页面版本不存在：" + versionNo);
        }
        return version;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    @Transactional
    public KnowledgeIdResponse create(KnowledgePageWriteRequest request) {
        Long userId = requireCurrentUserId();
        Long spaceId = request == null ? null : request.getSpaceId();
        if (spaceId == null) {
            throw new BusinessException("所属空间不能为空");
        }
        access.requireSpaceWrite(spaceId);
        Long nodeId = request.getNodeId() == null ? 0L : request.getNodeId();
        if (nodeId > 0) {
            Map<String, Object> node = access.requireNodeRow(nodeId);
            if (!spaceId.equals(numberAsLong(node.get("spaceId")))) {
                throw new BusinessException("所属目录不在该空间内");
            }
            access.requireNodeWrite(nodeId);
        }
        String title = required(request.getPageTitle(), "页面标题不能为空");
        String content = required(request.getContent(), "页面正文不能为空");
        validateContentLength(content);
        String pageType = validatePageType(defaultString(request.getPageType(), "wiki"));
        String contentFormat = defaultString(request.getContentFormat(), FORMAT_MARKDOWN);
        requireMarkdown(contentFormat);
        String visibility = validateVisibility(defaultString(request.getVisibility(), "inherit"));
        String status = validateStatus(defaultString(request.getStatus(), "published"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceId", spaceId);
        row.put("nodeId", nodeId);
        row.put("pageTitle", title);
        row.put("pageType", pageType);
        row.put("tags", trimToNull(request.getTags()));
        row.put("summary", trimToNull(request.getSummary()));
        row.put("content", content);
        row.put("contentFormat", contentFormat);
        row.put("visibility", visibility);
        row.put("status", status);
        row.put("currentVersion", 1);
        row.put("ownerId", userId);
        row.put("sourceModule", null);
        row.put("sourceBizType", null);
        row.put("sourceBizId", null);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        pageMapper.insertPage(row, currentUsername());
        Long pageId = numberAsLong(row.get("id"));
        insertVersion(pageId, 1, title, content, contentFormat, row.get("tags"),
                defaultString(request.getChangeSummary(), "创建页面"), userId);
        access.grantOwner("page", pageId, userId);
        return new KnowledgeIdResponse(pageId);
    }

    @Transactional
    public Map<String, Object> update(Long pageId, KnowledgePageWriteRequest request) {
        Map<String, Object> existing = access.requirePageRow(pageId);
        access.requirePageWrite(pageId);
        Long userId = currentUserIdOrNull();
        Long spaceId = numberAsLong(existing.get("spaceId"));
        Long nodeId = request == null || request.getNodeId() == null
                ? numberAsLong(existing.get("nodeId")) : request.getNodeId();
        if (nodeId != null && nodeId > 0 && !nodeId.equals(numberAsLong(existing.get("nodeId")))) {
            Map<String, Object> node = access.requireNodeRow(nodeId);
            if (!spaceId.equals(numberAsLong(node.get("spaceId")))) {
                throw new BusinessException("目标目录不在页面所属空间内");
            }
            access.requireNodeWrite(nodeId);
        }
        String title = request == null || request.getPageTitle() == null
                ? String.valueOf(existing.get("pageTitle"))
                : required(request.getPageTitle(), "页面标题不能为空");
        String content = request == null || request.getContent() == null
                ? String.valueOf(existing.get("content")) : request.getContent();
        validateContentLength(content);
        String pageType = request == null || request.getPageType() == null
                ? String.valueOf(existing.get("pageType")) : validatePageType(request.getPageType());
        String contentFormat = request == null || request.getContentFormat() == null
                ? String.valueOf(existing.get("contentFormat")) : request.getContentFormat();
        requireMarkdown(contentFormat);
        String visibility = request == null || request.getVisibility() == null
                ? String.valueOf(existing.get("visibility")) : validateVisibility(request.getVisibility());
        String status = request == null || request.getStatus() == null
                ? String.valueOf(existing.get("status")) : validateStatus(request.getStatus());
        String tags = request == null || request.getTags() == null
                ? (existing.get("tags") == null ? null : String.valueOf(existing.get("tags")))
                : trimToNull(request.getTags());
        int newVersion = intOf(existing.get("currentVersion")) + 1;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", nodeId == null ? 0L : nodeId);
        row.put("pageTitle", title);
        row.put("pageType", pageType);
        row.put("tags", tags);
        row.put("summary", request == null || request.getSummary() == null
                ? existing.get("summary") : trimToNull(request.getSummary()));
        row.put("content", content);
        row.put("contentFormat", FORMAT_MARKDOWN);
        row.put("visibility", visibility);
        row.put("status", status);
        row.put("currentVersion", newVersion);
        row.put("sort", request == null || request.getSort() == null
                ? intOf(existing.get("sort")) : request.getSort());
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        pageMapper.updatePage(pageId, row, currentUsername());
        insertVersion(pageId, newVersion, title, content, FORMAT_MARKDOWN, tags,
                request == null ? null : request.getChangeSummary(), userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pageId);
        result.put("versionNo", newVersion);
        return result;
    }

    @Transactional
    public Map<String, Object> rollback(Long pageId, Integer versionNo, KnowledgeVersionRollbackRequest request) {
        Map<String, Object> existing = access.requirePageRow(pageId);
        access.requirePageWrite(pageId);
        Map<String, Object> target = versionMapper.selectVersion(pageId, versionNo);
        if (target == null) {
            throw new BusinessException("页面版本不存在：" + versionNo);
        }
        int newVersion = intOf(existing.get("currentVersion")) + 1;
        Long userId = currentUserIdOrNull();
        String changeSummary = request == null || request.getChangeSummary() == null
                ? "回滚到版本 v" + versionNo : request.getChangeSummary();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", intOf(existing.get("nodeId")));
        row.put("pageTitle", target.get("pageTitle"));
        row.put("pageType", existing.get("pageType"));
        row.put("tags", target.get("tags"));
        row.put("summary", existing.get("summary"));
        row.put("content", target.get("content"));
        row.put("contentFormat", target.get("contentFormat"));
        row.put("visibility", existing.get("visibility"));
        row.put("status", "draft");
        row.put("currentVersion", newVersion);
        row.put("sort", intOf(existing.get("sort")));
        row.put("remark", existing.get("remark"));
        pageMapper.updatePage(pageId, row, currentUsername());
        insertVersion(pageId, newVersion, String.valueOf(target.get("pageTitle")),
                String.valueOf(target.get("content")), String.valueOf(target.get("contentFormat")),
                target.get("tags"), changeSummary, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pageId);
        result.put("versionNo", newVersion);
        result.put("rolledBackFrom", versionNo);
        return result;
    }

    @Transactional
    public void delete(Long pageId) {
        access.requirePageManage(pageId);
        if (fileMapper.countByPage(pageId) > 0) {
            throw new BusinessException("页面存在附件引用，请先移除附件再删除页面");
        }
        pageMapper.softDeletePage(pageId, currentUsername());
        access.clearAcl("page", pageId);
    }

    // ------------------------------------------------------------------
    // 正文切片（M11 RAG 只读端口）
    // ------------------------------------------------------------------

    @Override
    public PageContentSlice sliceContent(Long pageId, Integer offset, Integer limit) {
        Map<String, Object> page = pageMapper.selectPageById(pageId);
        if (page == null) {
            throw new BusinessException("知识页面不存在");
        }
        String content = String.valueOf(page.get("content") == null ? "" : page.get("content"));
        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        int safeLimit = limit == null ? SLICE_DEFAULT_LIMIT : Math.max(1, Math.min(limit, SLICE_MAX_LIMIT));
        int total = content.codePointCount(0, content.length());
        int begin = Math.min(safeOffset, total);
        int end = Math.min(begin + safeLimit, total);
        int beginIndex = content.offsetByCodePoints(0, begin);
        int endIndex = content.offsetByCodePoints(0, end);
        String slice = content.substring(beginIndex, endIndex);
        return new PageContentSlice(
                numberAsLong(page.get("id")),
                String.valueOf(page.get("pageTitle")),
                intOf(page.get("currentVersion")),
                String.valueOf(page.get("contentFormat")),
                safeOffset,
                safeLimit,
                total,
                slice,
                end < total,
                page.get("updateTime") == null ? null : String.valueOf(page.get("updateTime"))
        );
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void insertVersion(Long pageId, int versionNo, String title, String content, String contentFormat,
                               Object tags, String changeSummary, Long userId) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("pageId", pageId);
        version.put("versionNo", versionNo);
        version.put("pageTitle", title);
        version.put("content", content);
        version.put("contentFormat", contentFormat);
        version.put("tags", tags);
        version.put("changeSummary", changeSummary);
        version.put("editorId", userId);
        version.put("editorName", currentUsername());
        versionMapper.insertVersion(version, currentUsername());
    }

    private void validateContentLength(String content) {
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException("页面正文不能超过 " + CONTENT_MAX_LENGTH + " 个字符");
        }
    }

    private String validatePageType(String pageType) {
        String type = required(pageType, "页面类型不能为空");
        if (!PAGE_TYPES.contains(type)) {
            throw new BusinessException("页面类型只能为 wiki、sop、decision、minutes 或 retro");
        }
        return type;
    }

    private String validateVisibility(String visibility) {
        String value = required(visibility, "可见性不能为空");
        if (!VISIBILITIES.contains(value)) {
            throw new BusinessException("页面可见性只能为 inherit、internal 或 restricted");
        }
        return value;
    }

    private String validateStatus(String status) {
        String value = required(status, "页面状态不能为空");
        if (!STATUSES.contains(value)) {
            throw new BusinessException("页面状态只能为 draft、published 或 archived");
        }
        return value;
    }

    private void requireMarkdown(String contentFormat) {
        if (!FORMAT_MARKDOWN.equals(contentFormat)) {
            throw new BusinessException("页面正文格式统一为 markdown（渲染在前端完成）");
        }
    }
}
