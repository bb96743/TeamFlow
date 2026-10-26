package com.teamflow.collab.service;

import com.teamflow.collab.domain.dto.AnnouncementWriteRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.mapper.CollabAnnouncementMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M09 公告与广播服务（需求 2.2.4）。
 *
 * <p><b>可见范围与平台数据权限口径一致</b>：全员公告对所有已登录用户可见；部门公告只在
 * 「数据范围为全部」或「当前用户的数据范围包含该部门」时可见，判断依据是平台统一的数据范围上下文，
 * 不在本模块自建部门规则。草稿与已归档公告只对发布人/全量数据范围可见。</p>
 */
@Service
public class CollabAnnouncementService extends CollabServiceSupport {

    private static final Set<String> SCOPE_TYPES = Set.of("all", "dept");
    private static final Set<String> STATUSES = Set.of("draft", "published", "archived");

    private final CollabAnnouncementMapper announcementMapper;

    public CollabAnnouncementService(
            CollabPlatformMapper platformMapper, CollabAnnouncementMapper announcementMapper) {
        super(platformMapper);
        this.announcementMapper = announcementMapper;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String status) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        String statusFilter = trimToNull(status);
        long total = announcementMapper.countAnnouncements(statusFilter, userId, DataScopeContext.current());
        List<Map<String, Object>> rows = announcementMapper.selectAnnouncements(
                statusFilter, userId, DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long announcementId) {
        Long userId = currentUserId();
        Map<String, Object> row = announcementMapper.selectVisibleAnnouncement(
                announcementId, userId, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("公告不存在或无权查看");
        }
        decorate(row);
        return row;
    }

    @Transactional
    public CollabIdResponse create(AnnouncementWriteRequest request) {
        Map<String, Object> row = buildRow(request, null);
        row.put("publisherId", currentUserId());
        announcementMapper.insertAnnouncement(row, currentUsername());
        return new CollabIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long announcementId, AnnouncementWriteRequest request) {
        Map<String, Object> existing = announcementMapper.selectAnnouncementById(announcementId);
        if (existing == null) {
            throw new BusinessException("公告不存在或已删除");
        }
        Map<String, Object> row = buildRow(request, existing);
        announcementMapper.updateAnnouncement(announcementId, row, currentUsername());
    }

    @Transactional
    public void delete(Long announcementId) {
        if (announcementMapper.selectAnnouncementById(announcementId) == null) {
            throw new BusinessException("公告不存在或已删除");
        }
        announcementMapper.softDeleteAnnouncement(announcementId, currentUsername());
    }

    /** 标记已读（仅对当前可见公告生效；重复标记幂等）。 */
    @DataScope
    @Transactional
    public void markRead(Long announcementId) {
        Long userId = currentUserId();
        Map<String, Object> visible = announcementMapper.selectVisibleAnnouncement(
                announcementId, userId, DataScopeContext.current());
        if (visible == null) {
            throw new BusinessException("公告不存在或无权查看");
        }
        announcementMapper.upsertRead(announcementId, userId, LocalDateTime.now(), currentUsername());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildRow(AnnouncementWriteRequest request, Map<String, Object> existing) {
        String title = request != null && request.title() != null
                ? required(request.title(), "公告标题不能为空")
                : (existing == null ? null : String.valueOf(existing.get("title")));
        String content = request != null && request.content() != null
                ? required(request.content(), "公告正文不能为空")
                : (existing == null ? null : String.valueOf(existing.get("content")));
        String scopeType = request != null && request.scopeType() != null
                ? request.scopeType()
                : (existing == null ? "all" : String.valueOf(existing.get("scopeType")));
        if (!SCOPE_TYPES.contains(scopeType)) {
            throw new BusinessException("公告可见范围只能为 all 或 dept");
        }
        Long deptId = request != null && request.deptId() != null
                ? request.deptId()
                : (existing == null ? null : numberAsLong(existing.get("deptId")));
        if ("dept".equals(scopeType)) {
            if (deptId == null) {
                throw new BusinessException("部门公告必须指定部门");
            }
            if (platformMapper.countDept(deptId) == 0) {
                throw new BusinessException("指定部门不存在");
            }
        } else {
            deptId = null;
        }
        String status = request != null && request.status() != null
                ? request.status()
                : (existing == null ? "draft" : String.valueOf(existing.get("status")));
        if (!STATUSES.contains(status)) {
            throw new BusinessException("公告状态只能为 draft、published 或 archived");
        }
        LocalDateTime publishTime = existing == null ? null : toLocalDateTime(existing.get("publishTime"));
        if ("published".equals(status) && publishTime == null) {
            publishTime = LocalDateTime.now();
        }
        if (title == null) {
            throw new BusinessException("公告标题不能为空");
        }
        if (content == null) {
            throw new BusinessException("公告正文不能为空");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("content", content);
        row.put("scopeType", scopeType);
        row.put("deptId", deptId);
        row.put("status", status);
        row.put("publishTime", publishTime);
        return row;
    }

    private void decorate(Map<String, Object> row) {
        String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
        row.put("statusLabel", switch (status == null ? "" : status) {
            case "draft" -> "草稿";
            case "published" -> "已发布";
            case "archived" -> "已归档";
            default -> status;
        });
        String scopeType = row.get("scopeType") == null ? null : String.valueOf(row.get("scopeType"));
        row.put("scopeLabel", "all".equals(scopeType) ? "全员" : "部门");
        row.put("read", row.get("readTime") != null);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        String text = String.valueOf(value).replace(' ', 'T');
        return LocalDateTime.parse(text.length() == 16 ? text + ":00" : text);
    }
}
