package com.teamflow.collab.service;

import com.teamflow.collab.domain.dto.InternalNotifyRequest;
import com.teamflow.collab.event.CollabTodoPushEvent;
import com.teamflow.collab.mapper.CollabNotificationMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.todo.TodoBizTypes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M09 统一通知服务（需求 2.2.1「全站唯一通知出口」的存储侧 + 内部投递入口）。
 *
 * <p><b>通知只投递不代劳（D-2026-10-26-11 结论四）</b>：业务模块给「接收人 + 模板编码 + 参数 + 业务链接」，
 * 本服务渲染模板并落通知。若该通知需要用户行动，只发布内部投递事件，由投递端**经 M10 统一入口**
 * 进入个人待办收件箱；本模块不写待办数据、也不聚合待办。</p>
 */
@Service
public class CollabNotificationService extends CollabServiceSupport {

    private final CollabNotificationMapper notificationMapper;
    private final CollabTemplateRegistry templateRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public CollabNotificationService(
            CollabPlatformMapper platformMapper,
            CollabNotificationMapper notificationMapper,
            CollabTemplateRegistry templateRegistry,
            ApplicationEventPublisher eventPublisher
    ) {
        super(platformMapper);
        this.notificationMapper = notificationMapper;
        this.templateRegistry = templateRegistry;
        this.eventPublisher = eventPublisher;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, boolean unreadOnly) {
        Page page = pageOf(query);
        Long receiverId = currentUserId();
        long total = unreadOnly
                ? notificationMapper.countUnreadNotifications(receiverId)
                : notificationMapper.countNotifications(receiverId);
        List<Map<String, Object>> rows = notificationMapper.selectNotifications(
                receiverId, unreadOnly, page.offset(), page.pageSize());
        rows.forEach(row -> row.put("unread", row.get("readTime") == null));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> unreadCount() {
        Long receiverId = currentUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCount", notificationMapper.countUnreadNotifications(receiverId));
        return result;
    }

    @Transactional
    public void markRead(Long notificationId) {
        Long receiverId = currentUserId();
        int updated = notificationMapper.markNotificationRead(
                notificationId, receiverId, java.time.LocalDateTime.now(), currentUsername());
        if (updated == 0) {
            throw new BusinessException("通知不存在或不属于当前用户");
        }
    }

    @Transactional
    public void markAllRead() {
        notificationMapper.markAllRead(currentUserId(), java.time.LocalDateTime.now(), currentUsername());
    }

    /**
     * 统一通知投递入口（业务模块唯一入口）。
     *
     * <p>入参不允许裸 HTML；正文由模板渲染。需要进入个人待办收件箱时，只对已登记的
     * {@code collab_mention} 业务类型发布内部投递事件，由投递端经 M10 统一入口投递。</p>
     */
    @Transactional
    public Map<String, Object> notifyInternal(InternalNotifyRequest request) {
        if (request == null || request.receiverId() == null) {
            throw new BusinessException("通知接收人不能为空");
        }
        if (platformMapper.countActiveUser(request.receiverId()) == 0) {
            throw new BusinessException("通知接收人不存在或已停用");
        }
        String templateCode = required(request.templateCode(), "通知模板编码不能为空");
        CollabTemplateRegistry.Rendered rendered = templateRegistry.render(templateCode, request.params());
        String idempotencyKey = required(request.idempotencyKey(), "通知幂等键不能为空（同一来源同一接收人只入一条）");
        Map<String, Object> existing = notificationMapper.selectByIdempotencyKey(idempotencyKey);
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing != null) {
            result.put("id", numberAsLong(existing.get("id")));
            result.put("duplicated", true);
            result.put("todoQueued", false);
            return result;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("receiverId", request.receiverId());
        row.put("notifyType", templateCode);
        row.put("title", rendered.title());
        row.put("content", rendered.content());
        row.put("bizType", trimToNull(request.bizType()));
        row.put("bizId", request.bizId());
        row.put("actionUrl", trimToNull(request.actionUrl()));
        row.put("idempotencyKey", idempotencyKey);
        notificationMapper.insertNotification(row, currentUsername());
        Long notificationId = numberAsLong(row.get("id"));

        boolean todoQueued = false;
        boolean requireTodo = Boolean.TRUE.equals(request.requireTodo());
        if (requireTodo) {
            if (TodoBizTypes.COLLAB_MENTION.equals(request.bizType()) && request.bizId() != null) {
                eventPublisher.publishEvent(new CollabTodoPushEvent(
                        request.receiverId(), TodoBizTypes.COLLAB_MENTION, request.bizId(),
                        rendered.title(), trimToNull(request.actionUrl()), idempotencyKey));
                todoQueued = true;
            } else {
                logSkippedTodo(request, idempotencyKey);
            }
        }
        result.put("id", notificationId);
        result.put("duplicated", false);
        result.put("todoQueued", todoQueued);
        return result;
    }

    // ------------------------------------------------------------------

    private void logSkippedTodo(InternalNotifyRequest request, String idempotencyKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", "todo");
        row.put("targetUserId", request.receiverId());
        row.put("bizType", trimToNull(request.bizType()));
        row.put("bizId", request.bizId());
        row.put("idempotencyKey", idempotencyKey);
        row.put("status", "skipped");
        row.put("reason", "需要待办投递时仅支持已登记的 collab_mention 业务类型（D-2026-10-26-11 结论四）");
        notificationMapper.insertChannelLog(row);
    }
}
