package com.teamflow.knowledge.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.domain.dto.KnowledgeAclWriteRequest;
import com.teamflow.knowledge.mapper.KnowledgeAclMapper;
import com.teamflow.knowledge.mapper.KnowledgeNodeMapper;
import com.teamflow.knowledge.mapper.KnowledgePageMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.knowledge.mapper.KnowledgeSpaceMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M07 访问控制服务（R2 权限继承可解释）。
 *
 * <p><b>权限链</b>：空间 → 目录（可多层）→ 页面，逐级继承；<b>子级授权可覆盖父级</b>：</p>
 * <ul>
 *   <li>某一级上存在当前用户的授权行时，该级结论直接覆盖此前累计结论
 *       （deny → 无权限；read/write/manage → 对应级别）；</li>
 *   <li>该级没有用户授权时按可见性：{@code internal} 至少只读、{@code restricted} 收回为无权限、
 *       {@code inherit} 保持上级结论；</li>
 *   <li>空间/页面所有者在该级视为 manage；</li>
 *   <li>页面最终权限可用于回答「为什么这个人能看到」，页面详情接口返回权限级别与判定原因。</li>
 * </ul>
 *
 * <p><b>数据边界</b>：判定只读 {@code knowledge_*} 与平台用户表；不调用数据范围（知识库按授权链
 * 而非部门数据范围授权，避免与 R2 双口径冲突）。</p>
 *
 * <p><b>本期限制</b>：授权对象仅支持用户（角色级授权属后续扩展）；平台超级管理员不做业务层绕过，
 * 避免越权读取受限制空间——需要访问时应被显式授权或作为空间所有者。</p>
 */
@Service
public class KnowledgeAccessService extends KnowledgeServiceSupport {

    public static final int NONE = 0;
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int MANAGE = 3;

    private static final String TYPE_SPACE = "space";
    private static final String TYPE_NODE = "node";
    private static final String TYPE_PAGE = "page";
    private static final String SUBJECT_USER = "user";
    private static final String PERM_DENY = "deny";
    private static final Set<String> RESOURCE_TYPES = Set.of(TYPE_SPACE, TYPE_NODE, TYPE_PAGE);
    private static final Set<String> PERMISSIONS = Set.of("read", "write", "manage", PERM_DENY);

    /** 当前用户全部授权行的内存视图（一次查询构建，避免逐资源 N+1）。 */
    public record AclView(
            Long userId,
            Map<Long, String> spaceAcl,
            Map<Long, String> nodeAcl,
            Map<Long, String> pageAcl
    ) {
        public static AclView empty() {
            return new AclView(null, Map.of(), Map.of(), Map.of());
        }
    }

    /** 权限判定结果：级别 + 可读文案 + 判定原因（R2：可解释）。 */
    public record Permission(int level, String label, String reason) {
        public boolean canRead() { return level >= READ; }
        public boolean canWrite() { return level >= WRITE; }
        public boolean canManage() { return level == MANAGE; }
    }

    /** 权限链中的一级。 */
    private record Level(String type, Long id, String visibility, Long ownerId) {
    }

    private final KnowledgeAclMapper aclMapper;
    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgePageMapper pageMapper;

    public KnowledgeAccessService(
            KnowledgePlatformMapper platformMapper,
            KnowledgeAclMapper aclMapper,
            KnowledgeSpaceMapper spaceMapper,
            KnowledgeNodeMapper nodeMapper,
            KnowledgePageMapper pageMapper
    ) {
        super(platformMapper);
        this.aclMapper = aclMapper;
        this.spaceMapper = spaceMapper;
        this.nodeMapper = nodeMapper;
        this.pageMapper = pageMapper;
    }

    // ------------------------------------------------------------------
    // 授权视图与权限计算
    // ------------------------------------------------------------------

    public AclView loadView() {
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            return AclView.empty();
        }
        Map<Long, String> spaces = new HashMap<>();
        Map<Long, String> nodes = new HashMap<>();
        Map<Long, String> pages = new HashMap<>();
        for (Map<String, Object> row : aclMapper.selectBySubject(SUBJECT_USER, userId)) {
            String type = String.valueOf(row.get("resourceType"));
            Long resourceId = numberAsLong(row.get("resourceId"));
            String permission = String.valueOf(row.get("permission"));
            if (resourceId == null) {
                continue;
            }
            if (TYPE_SPACE.equals(type)) {
                spaces.put(resourceId, permission);
            } else if (TYPE_NODE.equals(type)) {
                nodes.put(resourceId, permission);
            } else if (TYPE_PAGE.equals(type)) {
                pages.put(resourceId, permission);
            }
        }
        return new AclView(userId, spaces, nodes, pages);
    }

    public Map<String, Object> requireSpaceRow(Long spaceId) {
        if (spaceId == null) {
            throw new BusinessException("知识空间 ID 不能为空");
        }
        Map<String, Object> space = spaceMapper.selectSpaceById(spaceId);
        if (space == null) {
            throw new BusinessException("知识空间不存在");
        }
        return space;
    }

    public Map<String, Object> requireNodeRow(Long nodeId) {
        if (nodeId == null) {
            throw new BusinessException("目录 ID 不能为空");
        }
        Map<String, Object> node = nodeMapper.selectNodeById(nodeId);
        if (node == null) {
            throw new BusinessException("知识目录不存在");
        }
        return node;
    }

    public Map<String, Object> requirePageRow(Long pageId) {
        if (pageId == null) {
            throw new BusinessException("页面 ID 不能为空");
        }
        Map<String, Object> page = pageMapper.selectPageById(pageId);
        if (page == null) {
            throw new BusinessException("知识页面不存在");
        }
        return page;
    }

    /** 批量装载目录及其全部祖先（用于列表/检索的授权链计算）。 */
    public Map<Long, Map<String, Object>> loadNodeChainMaps(Set<Long> seedNodeIds) {
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        if (seedNodeIds == null || seedNodeIds.isEmpty()) {
            return nodes;
        }
        Set<Long> pending = new LinkedHashSet<>();
        seedNodeIds.stream().filter(id -> id != null && id > 0).forEach(pending::add);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 20) {
            List<Map<String, Object>> rows = nodeMapper.selectNodesByIds(new ArrayList<>(pending));
            pending = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                Long id = numberAsLong(row.get("id"));
                if (id != null && !nodes.containsKey(id)) {
                    nodes.put(id, row);
                }
                Long parentId = numberAsLong(row.get("parentId"));
                if (parentId != null && parentId > 0 && !nodes.containsKey(parentId)) {
                    pending.add(parentId);
                }
            }
            rows.stream().map(row -> numberAsLong(row.get("parentId")))
                    .filter(id -> id != null && id > 0)
                    .forEach(pending::add);
        }
        return nodes;
    }

    /** 空间级权限（只查授权视图 + 空间行）。 */
    public Permission spacePermission(Map<String, Object> space, AclView view) {
        return compute(space, List.of(), null, view);
    }

    /** 目录级权限（nodes 需包含该目录及其祖先）。 */
    public Permission nodePermission(Map<String, Object> node, Map<Long, Map<String, Object>> nodes,
                                     Map<String, Object> space, AclView view) {
        return compute(space, chainOf(node, nodes), null, view);
    }

    /** 页面级权限（nodes 需包含其目录链）。 */
    public Permission pagePermission(Map<String, Object> page, Map<Long, Map<String, Object>> nodes,
                                     Map<String, Object> space, AclView view) {
        return compute(space, chainOf(page, nodes), page, view);
    }

    private List<Map<String, Object>> chainOf(Map<String, Object> target, Map<Long, Map<String, Object>> nodes) {
        List<Map<String, Object>> chain = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            return chain;
        }
        Long nodeId = numberAsLong(target.get("nodeId"));
        int guard = 0;
        while (nodeId != null && nodeId > 0 && guard++ < 100) {
            Map<String, Object> node = nodes.get(nodeId);
            if (node == null) {
                break;
            }
            chain.add(0, node);
            nodeId = numberAsLong(node.get("parentId"));
        }
        return chain;
    }

    private Permission compute(Map<String, Object> space, List<Map<String, Object>> nodeChain,
                               Map<String, Object> page, AclView view) {
        List<Level> levels = new ArrayList<>();
        levels.add(new Level(TYPE_SPACE, numberAsLong(space.get("id")), str(space.get("visibility")),
                numberAsLong(space.get("ownerId"))));
        for (Map<String, Object> node : nodeChain) {
            levels.add(new Level(TYPE_NODE, numberAsLong(node.get("id")), str(node.get("visibility")), null));
        }
        if (page != null) {
            levels.add(new Level(TYPE_PAGE, numberAsLong(page.get("id")), str(page.get("visibility")),
                    numberAsLong(page.get("ownerId"))));
        }

        int level = NONE;
        String reason = "无任何授权，默认不可见（知识库按授权链判定）";
        for (Level item : levels) {
            String acl = aclOf(view, item);
            if (item.ownerId() != null && item.ownerId().equals(view.userId())) {
                level = MANAGE;
                reason = typeLabel(item.type()) + "所有者（manage）";
                continue;
            }
            if (acl != null) {
                if (PERM_DENY.equals(acl)) {
                    level = NONE;
                    reason = typeLabel(item.type()) + "显式拒绝（deny），覆盖上级授权";
                } else {
                    level = levelOf(acl);
                    reason = typeLabel(item.type()) + "显式授权（" + acl + "），覆盖上级授权";
                }
                continue;
            }
            String visibility = item.visibility();
            if ("internal".equals(visibility)) {
                if (level < READ) {
                    level = READ;
                    reason = typeLabel(item.type()) + "默认可见（internal，已登录可读）";
                }
            } else if ("restricted".equals(visibility)) {
                level = NONE;
                reason = typeLabel(item.type()) + "仅授权可见（restricted）";
            }
        }
        return new Permission(level, permissionLabel(level), reason);
    }

    private String aclOf(AclView view, Level level) {
        if (view.userId() == null || level.id() == null) {
            return null;
        }
        return switch (level.type()) {
            case TYPE_SPACE -> view.spaceAcl().get(level.id());
            case TYPE_NODE -> view.nodeAcl().get(level.id());
            case TYPE_PAGE -> view.pageAcl().get(level.id());
            default -> null;
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int levelOf(String permission) {
        return switch (permission) {
            case "read" -> READ;
            case "write" -> WRITE;
            case "manage" -> MANAGE;
            default -> NONE;
        };
    }

    private static String permissionLabel(int level) {
        return switch (level) {
            case READ -> "只读";
            case WRITE -> "可编辑";
            case MANAGE -> "可管理";
            default -> "无权限";
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case TYPE_SPACE -> "空间";
            case TYPE_NODE -> "目录";
            case TYPE_PAGE -> "页面";
            default -> type;
        };
    }

    // ------------------------------------------------------------------
    // 调用点：单资源校验收敛在这里，业务 Service 只调用 requireXxx
    // ------------------------------------------------------------------

    public void requireSpaceRead(Long spaceId) {
        Map<String, Object> space = requireSpaceRow(spaceId);
        Permission permission = spacePermission(space, loadView());
        if (!permission.canRead()) {
            throw denied("知识空间", permission);
        }
    }

    public void requireSpaceWrite(Long spaceId) {
        Map<String, Object> space = requireSpaceRow(spaceId);
        Permission permission = spacePermission(space, loadView());
        if (!permission.canWrite()) {
            throw denied("知识空间", permission);
        }
    }

    public void requireSpaceManage(Long spaceId) {
        Map<String, Object> space = requireSpaceRow(spaceId);
        Permission permission = spacePermission(space, loadView());
        if (!permission.canManage()) {
            throw denied("知识空间", permission);
        }
    }

    public void requireNodeRead(Long nodeId) {
        Permission permission = nodePermissionOf(nodeId, loadView());
        if (!permission.canRead()) {
            throw denied("知识目录", permission);
        }
    }

    public void requireNodeWrite(Long nodeId) {
        Permission permission = nodePermissionOf(nodeId, loadView());
        if (!permission.canWrite()) {
            throw denied("知识目录", permission);
        }
    }

    public void requireNodeManage(Long nodeId) {
        Permission permission = nodePermissionOf(nodeId, loadView());
        if (!permission.canManage()) {
            throw denied("知识目录", permission);
        }
    }

    public void requirePageRead(Long pageId) {
        Permission permission = pagePermissionOf(pageId, loadView());
        if (!permission.canRead()) {
            throw denied("知识页面", permission);
        }
    }

    public void requirePageWrite(Long pageId) {
        Permission permission = pagePermissionOf(pageId, loadView());
        if (!permission.canWrite()) {
            throw denied("知识页面", permission);
        }
    }

    public void requirePageManage(Long pageId) {
        Permission permission = pagePermissionOf(pageId, loadView());
        if (!permission.canManage()) {
            throw denied("知识页面", permission);
        }
    }

    /** 权限说明（页面详情返回，回答「为什么能看到/能编辑」）。 */
    public Map<String, Object> describePageAccess(Map<String, Object> page) {
        AclView view = loadView();
        Map<String, Object> space = requireSpaceRow(numberAsLong(page.get("spaceId")));
        Map<Long, Map<String, Object>> nodes = loadNodeChainMaps(
                page.get("nodeId") == null ? Set.of() : Set.of(numberAsLong(page.get("nodeId"))));
        Permission permission = pagePermission(page, nodes, space, view);
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("level", permission.level());
        access.put("levelLabel", permission.label());
        access.put("reason", permission.reason());
        access.put("canRead", permission.canRead());
        access.put("canWrite", permission.canWrite());
        access.put("canManage", permission.canManage());
        return access;
    }

    /** 批量过滤：返回当前用户可读的空间行（保留原顺序）。 */
    public List<Map<String, Object>> filterReadableSpaces(List<Map<String, Object>> spaces) {
        AclView view = loadView();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> space : spaces) {
            if (spacePermission(space, view).canRead()) {
                visible.add(space);
            }
        }
        return visible;
    }

    /**
     * 批量过滤：返回当前用户可读的页面行（草稿仅对可编辑者可读）。
     *
     * <p>一次构建授权视图、一次批量装载目录链，避免逐页查询。</p>
     */
    public List<Map<String, Object>> filterReadablePages(List<Map<String, Object>> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        AclView view = loadView();
        Set<Long> spaceIds = new LinkedHashSet<>();
        Set<Long> nodeIds = new LinkedHashSet<>();
        for (Map<String, Object> page : pages) {
            Long spaceId = numberAsLong(page.get("spaceId"));
            if (spaceId != null) {
                spaceIds.add(spaceId);
            }
            Long nodeId = numberAsLong(page.get("nodeId"));
            if (nodeId != null && nodeId > 0) {
                nodeIds.add(nodeId);
            }
        }
        Map<Long, Map<String, Object>> spaces = new LinkedHashMap<>();
        for (Long spaceId : spaceIds) {
            Map<String, Object> space = spaceMapper.selectSpaceById(spaceId);
            if (space != null) {
                spaces.put(spaceId, space);
            }
        }
        Map<Long, Map<String, Object>> nodes = loadNodeChainMaps(nodeIds);
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            Map<String, Object> space = spaces.get(numberAsLong(page.get("spaceId")));
            if (space == null) {
                continue;
            }
            Permission permission = pagePermission(page, nodes, space, view);
            boolean draft = "draft".equals(str(page.get("status")));
            if (permission.canRead() && (!draft || permission.canWrite())) {
                visible.add(page);
            }
        }
        return visible;
    }

    /** 批量过滤目录：返回当前用户可读的目录行。 */
    public List<Map<String, Object>> filterReadableNodes(List<Map<String, Object>> nodes, Map<String, Object> space) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        AclView view = loadView();
        Map<Long, Map<String, Object>> allNodes = new LinkedHashMap<>();
        nodes.forEach(node -> allNodes.put(numberAsLong(node.get("id")), node));
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (nodePermission(node, allNodes, space, view).canRead()) {
                visible.add(node);
            }
        }
        return visible;
    }

    private Permission nodePermissionOf(Long nodeId, AclView view) {
        Map<String, Object> node = requireNodeRow(nodeId);
        Long spaceId = numberAsLong(node.get("spaceId"));
        Map<String, Object> space = requireSpaceRow(spaceId);
        Map<Long, Map<String, Object>> nodes = loadNodeChainMaps(Set.of(nodeId));
        return nodePermission(node, nodes, space, view);
    }

    private Permission pagePermissionOf(Long pageId, AclView view) {
        Map<String, Object> page = requirePageRow(pageId);
        Long spaceId = numberAsLong(page.get("spaceId"));
        Map<String, Object> space = requireSpaceRow(spaceId);
        Long nodeId = numberAsLong(page.get("nodeId"));
        Map<Long, Map<String, Object>> nodes = loadNodeChainMaps(nodeId == null ? Set.of() : Set.of(nodeId));
        return pagePermission(page, nodes, space, view);
    }

    private AccessDeniedException denied(String target, Permission permission) {
        return new AccessDeniedException("无权访问" + target + "（当前权限：" + permission.label()
                + "；判定依据：" + permission.reason() + "）");
    }

    // ------------------------------------------------------------------
    // 授权管理（需要资源 manage）
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listAcl(String resourceType, Long resourceId) {
        String type = requireResourceType(resourceType);
        requireManage(type, resourceId);
        return aclMapper.selectByResource(type, resourceId);
    }

    @Transactional
    public Map<String, Object> grant(KnowledgeAclWriteRequest request) {
        String type = requireResourceType(request == null ? null : request.getResourceType());
        Long resourceId = request.getResourceId();
        if (resourceId == null) {
            throw new BusinessException("授权资源 ID 不能为空");
        }
        requireManage(type, resourceId);
        String subjectType = defaultString(request.getSubjectType(), SUBJECT_USER);
        if (!SUBJECT_USER.equals(subjectType)) {
            throw new BusinessException("本期仅支持按用户授权（subjectType=user）");
        }
        Long subjectId = request.getSubjectId();
        if (subjectId == null) {
            throw new BusinessException("被授权用户 ID 不能为空");
        }
        if (platformMapper.countActiveUser(subjectId) == 0) {
            throw new BusinessException("被授权用户不存在或已停用");
        }
        String permission = required(request.getPermission(), "授权级别不能为空（read/write/manage/deny）");
        if (!PERMISSIONS.contains(permission)) {
            throw new BusinessException("授权级别只能为 read、write、manage 或 deny");
        }
        String remark = trimToNull(request.getRemark());
        Map<String, Object> existing = aclMapper.selectOne(type, resourceId, subjectType, subjectId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing != null) {
            Long aclId = numberAsLong(existing.get("id"));
            aclMapper.updateAcl(aclId, permission, remark, currentUsername());
            result.put("id", aclId);
            result.put("updated", true);
        } else {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resourceType", type);
            row.put("resourceId", resourceId);
            row.put("subjectType", subjectType);
            row.put("subjectId", subjectId);
            row.put("permission", permission);
            row.put("remark", remark);
            aclMapper.insertAcl(row, currentUsername());
            result.put("id", numberAsLong(row.get("id")));
            result.put("updated", false);
        }
        return result;
    }

    @Transactional
    public void revoke(Long aclId) {
        Map<String, Object> acl = aclMapper.selectAclById(aclId);
        if (acl == null) {
            throw new BusinessException("授权不存在或已取消");
        }
        requireManage(String.valueOf(acl.get("resourceType")), numberAsLong(acl.get("resourceId")));
        aclMapper.softDeleteAcl(aclId, currentUsername());
    }

    /** 资源创建时给所有者写入 manage 授权（让创建者无需额外授权即可继续维护）。 */
    public void grantOwner(String resourceType, Long resourceId, Long userId) {
        if (resourceId == null || userId == null) {
            return;
        }
        Map<String, Object> existing = aclMapper.selectOne(resourceType, resourceId, SUBJECT_USER, userId);
        if (existing != null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resourceType", resourceType);
        row.put("resourceId", resourceId);
        row.put("subjectType", SUBJECT_USER);
        row.put("subjectId", userId);
        row.put("permission", "manage");
        row.put("remark", "资源创建者默认管理授权");
        aclMapper.insertAcl(row, currentUsername());
    }

    /** 资源删除时清理授权行。 */
    public void clearAcl(String resourceType, Long resourceId) {
        aclMapper.softDeleteByResource(resourceType, resourceId, currentUsername());
    }

    private String requireResourceType(String resourceType) {
        String type = required(resourceType, "资源类型不能为空（space/node/page）");
        if (!RESOURCE_TYPES.contains(type)) {
            throw new BusinessException("资源类型只能为 space、node 或 page");
        }
        return type;
    }

    private void requireManage(String type, Long resourceId) {
        switch (type) {
            case TYPE_SPACE -> requireSpaceManage(resourceId);
            case TYPE_NODE -> requireNodeManage(resourceId);
            case TYPE_PAGE -> requirePageManage(resourceId);
            default -> throw new BusinessException("资源类型只能为 space、node 或 page");
        }
    }
}
