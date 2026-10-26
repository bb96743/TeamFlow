package com.teamflow.knowledge.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.domain.dto.KnowledgeNodeWriteRequest;
import com.teamflow.knowledge.domain.dto.KnowledgeSpaceWriteRequest;
import com.teamflow.knowledge.domain.vo.KnowledgeIdResponse;
import com.teamflow.knowledge.mapper.KnowledgeNodeMapper;
import com.teamflow.knowledge.mapper.KnowledgePageMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.knowledge.mapper.KnowledgeSpaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M07 知识空间与目录服务：空间 → 目录两层结构的 CRUD 与树查询。
 *
 * <p>权限继承与覆盖统一由 {@link KnowledgeAccessService} 判定；本服务只做结构与校验。</p>
 */
@Service
public class KnowledgeCatalogService extends KnowledgeServiceSupport {

    private static final Set<String> VISIBILITIES = Set.of("internal", "restricted");
    private static final Set<String> SPACE_STATUSES = Set.of("active", "archived");
    private static final Pattern SPACE_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{1,63}$");
    private static final int TREE_PAGE_LIMIT = 2000;

    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgePageMapper pageMapper;
    private final KnowledgeAccessService access;

    public KnowledgeCatalogService(
            KnowledgePlatformMapper platformMapper,
            KnowledgeSpaceMapper spaceMapper,
            KnowledgeNodeMapper nodeMapper,
            KnowledgePageMapper pageMapper,
            KnowledgeAccessService access
    ) {
        super(platformMapper);
        this.spaceMapper = spaceMapper;
        this.nodeMapper = nodeMapper;
        this.pageMapper = pageMapper;
        this.access = access;
    }

    // ------------------------------------------------------------------
    // 空间
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> listSpaces(PageQuery query, String keyword, String status) {
        List<Map<String, Object>> visible = access.filterReadableSpaces(
                spaceMapper.selectSpaces(trimToNull(keyword), trimToNull(status)));
        Page page = pageOf(query);
        int from = Math.min(page.offset(), visible.size());
        int to = Math.min(from + page.pageSize(), visible.size());
        return PageResult.of(new ArrayList<>(visible.subList(from, to)), visible.size(), page.pageNum(), page.pageSize());
    }

    public Map<String, Object> spaceDetail(Long spaceId) {
        access.requireSpaceRead(spaceId);
        Map<String, Object> space = access.requireSpaceRow(spaceId);
        KnowledgeAccessService.Permission permission = access.spacePermission(space, access.loadView());
        space.put("access", accessView(permission));
        return space;
    }

    @Transactional
    public KnowledgeIdResponse createSpace(KnowledgeSpaceWriteRequest request) {
        Long userId = requireCurrentUserId();
        String name = required(request == null ? null : request.getSpaceName(), "空间名称不能为空");
        String code = required(request.getSpaceCode(), "空间编码不能为空");
        if (!SPACE_CODE.matcher(code).matches()) {
            throw new BusinessException("空间编码须以字母开头，仅含字母、数字、下划线、短横线（2–64 位）");
        }
        if (spaceMapper.countSpaceCode(code, null) > 0) {
            throw new BusinessException("空间编码已存在");
        }
        String visibility = defaultString(request.getVisibility(), "internal");
        if (!VISIBILITIES.contains(visibility)) {
            throw new BusinessException("空间可见性只能为 internal 或 restricted");
        }
        String status = defaultString(request.getStatus(), "active");
        if (!SPACE_STATUSES.contains(status)) {
            throw new BusinessException("空间状态只能为 active 或 archived");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceName", name);
        row.put("spaceCode", code);
        row.put("spaceDesc", trimToNull(request.getSpaceDesc()));
        row.put("visibility", visibility);
        row.put("ownerId", userId);
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        spaceMapper.insertSpace(row, currentUsername());
        Long spaceId = numberAsLong(row.get("id"));
        access.grantOwner("space", spaceId, userId);
        return new KnowledgeIdResponse(spaceId);
    }

    @Transactional
    public void updateSpace(Long spaceId, KnowledgeSpaceWriteRequest request) {
        access.requireSpaceManage(spaceId);
        Map<String, Object> existing = access.requireSpaceRow(spaceId);
        String name = request == null || request.getSpaceName() == null
                ? String.valueOf(existing.get("spaceName"))
                : required(request.getSpaceName(), "空间名称不能为空");
        String visibility = request == null || request.getVisibility() == null
                ? String.valueOf(existing.get("visibility"))
                : defaultString(request.getVisibility(), "internal");
        if (!VISIBILITIES.contains(visibility)) {
            throw new BusinessException("空间可见性只能为 internal 或 restricted");
        }
        String status = request == null || request.getStatus() == null
                ? String.valueOf(existing.get("status"))
                : defaultString(request.getStatus(), "active");
        if (!SPACE_STATUSES.contains(status)) {
            throw new BusinessException("空间状态只能为 active 或 archived");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceName", name);
        row.put("spaceDesc", request == null || request.getSpaceDesc() == null
                ? existing.get("spaceDesc") : trimToNull(request.getSpaceDesc()));
        row.put("visibility", visibility);
        row.put("status", status);
        row.put("sort", request == null || request.getSort() == null
                ? intOf(existing.get("sort")) : request.getSort());
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        spaceMapper.updateSpace(spaceId, row, currentUsername());
    }

    @Transactional
    public void deleteSpace(Long spaceId) {
        access.requireSpaceManage(spaceId);
        if (spaceMapper.countNodes(spaceId) > 0 || spaceMapper.countPages(spaceId) > 0) {
            throw new BusinessException("空间下仍有目录或页面，不能删除（可先归档空间）");
        }
        spaceMapper.softDeleteSpace(spaceId, currentUsername());
        access.clearAcl("space", spaceId);
    }

    /** 空间树：目录树 + 每个目录下的页面摘要（页面按授权过滤，草稿仅可编辑者可见）。 */
    public Map<String, Object> tree(Long spaceId) {
        access.requireSpaceRead(spaceId);
        Map<String, Object> space = access.requireSpaceRow(spaceId);
        List<Map<String, Object>> nodes = access.filterReadableNodes(nodeMapper.selectNodesBySpace(spaceId), space);
        List<Map<String, Object>> pages = access.filterReadablePages(
                pageMapper.selectPages(List.of(spaceId), null, null, null, null, null, TREE_PAGE_LIMIT));

        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        List<Map<String, Object>> rootPages = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> copy = new LinkedHashMap<>(node);
            copy.put("children", new ArrayList<Map<String, Object>>());
            copy.put("pages", new ArrayList<Map<String, Object>>());
            byId.put(numberAsLong(copy.get("id")), copy);
        }
        for (Map<String, Object> node : byId.values()) {
            Long parentId = numberAsLong(node.get("parentId"));
            Map<String, Object> parent = parentId == null ? null : byId.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        }
        for (Map<String, Object> page : pages) {
            Long nodeId = numberAsLong(page.get("nodeId"));
            Map<String, Object> node = nodeId == null ? null : byId.get(nodeId);
            if (node == null) {
                rootPages.add(page);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodePages = (List<Map<String, Object>>) node.get("pages");
                nodePages.add(page);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("space", space);
        result.put("nodeCount", byId.size());
        result.put("pageCount", pages.size());
        result.put("nodes", roots);
        result.put("pages", rootPages);
        return result;
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listNodes(Long spaceId) {
        access.requireSpaceRead(spaceId);
        Map<String, Object> space = access.requireSpaceRow(spaceId);
        return access.filterReadableNodes(nodeMapper.selectNodesBySpace(spaceId), space);
    }

    public Map<String, Object> nodeDetail(Long nodeId) {
        access.requireNodeRead(nodeId);
        Map<String, Object> node = access.requireNodeRow(nodeId);
        Map<String, Object> space = access.requireSpaceRow(numberAsLong(node.get("spaceId")));
        Map<Long, Map<String, Object>> nodes = access.loadNodeChainMaps(Set.of(nodeId));
        KnowledgeAccessService.Permission permission = access.nodePermission(node, nodes, space, access.loadView());
        node.put("access", accessView(permission));
        return node;
    }

    @Transactional
    public KnowledgeIdResponse createNode(KnowledgeNodeWriteRequest request) {
        Long spaceId = request == null ? null : request.getSpaceId();
        if (spaceId == null) {
            throw new BusinessException("所属空间不能为空");
        }
        access.requireSpaceWrite(spaceId);
        String name = required(request.getNodeName(), "目录名称不能为空");
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        if (parentId > 0) {
            Map<String, Object> parent = access.requireNodeRow(parentId);
            if (!spaceId.equals(numberAsLong(parent.get("spaceId")))) {
                throw new BusinessException("上级目录不属于该空间");
            }
            access.requireNodeWrite(parentId);
        }
        String visibility = defaultString(request.getVisibility(), "inherit");
        if (!VISIBILITIES.contains(visibility) && !"inherit".equals(visibility)) {
            throw new BusinessException("目录可见性只能为 inherit、internal 或 restricted");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spaceId", spaceId);
        row.put("parentId", parentId);
        row.put("nodeName", name);
        row.put("nodeDesc", trimToNull(request.getNodeDesc()));
        row.put("visibility", visibility);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        nodeMapper.insertNode(row, currentUsername());
        Long nodeId = numberAsLong(row.get("id"));
        access.grantOwner("node", nodeId, currentUserIdOrNull());
        return new KnowledgeIdResponse(nodeId);
    }

    @Transactional
    public void updateNode(Long nodeId, KnowledgeNodeWriteRequest request) {
        access.requireNodeWrite(nodeId);
        Map<String, Object> existing = access.requireNodeRow(nodeId);
        String name = request == null || request.getNodeName() == null
                ? String.valueOf(existing.get("nodeName"))
                : required(request.getNodeName(), "目录名称不能为空");
        String visibility = request == null || request.getVisibility() == null
                ? String.valueOf(existing.get("visibility"))
                : defaultString(request.getVisibility(), "inherit");
        if (!VISIBILITIES.contains(visibility) && !"inherit".equals(visibility)) {
            throw new BusinessException("目录可见性只能为 inherit、internal 或 restricted");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeName", name);
        row.put("nodeDesc", request == null || request.getNodeDesc() == null
                ? existing.get("nodeDesc") : trimToNull(request.getNodeDesc()));
        row.put("visibility", visibility);
        row.put("sort", request == null || request.getSort() == null
                ? intOf(existing.get("sort")) : request.getSort());
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        nodeMapper.updateNode(nodeId, row, currentUsername());
    }

    @Transactional
    public void deleteNode(Long nodeId) {
        access.requireNodeManage(nodeId);
        if (nodeMapper.countChildren(nodeId) > 0) {
            throw new BusinessException("目录下仍有子目录，不能删除");
        }
        if (nodeMapper.countPages(nodeId) > 0) {
            throw new BusinessException("目录下仍有页面，不能删除");
        }
        nodeMapper.softDeleteNode(nodeId, currentUsername());
        access.clearAcl("node", nodeId);
    }

    private Map<String, Object> accessView(KnowledgeAccessService.Permission permission) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("level", permission.level());
        view.put("levelLabel", permission.label());
        view.put("reason", permission.reason());
        view.put("canRead", permission.canRead());
        view.put("canWrite", permission.canWrite());
        view.put("canManage", permission.canManage());
        return view;
    }
}
