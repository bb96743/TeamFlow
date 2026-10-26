package com.teamflow.collab.service;

import com.teamflow.collab.domain.dto.ConversationCreateRequest;
import com.teamflow.collab.domain.dto.ConversationReadRequest;
import com.teamflow.collab.domain.dto.MessageSendRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.event.CollabMentionEvent;
import com.teamflow.collab.mapper.CollabConversationMapper;
import com.teamflow.collab.mapper.CollabMessageMapper;
import com.teamflow.collab.mapper.CollabNotificationMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M09 站内信服务：单聊 / 群组会话、消息收发、已读游标（需求 2.2.1）。
 *
 * <p>本模块是**全站唯一通知出口**：消息只在本模块存储。@提及经事务提交后的投递端处理，
 * 需要进入个人待办收件箱时**只经 M10 统一投递入口**，不直写待办数据（D-2026-10-26-11 结论四）。</p>
 */
@Service
public class CollabConversationService extends CollabServiceSupport {

    private static final int MESSAGE_MAX_LENGTH = 2000;

    private final CollabConversationMapper conversationMapper;
    private final CollabMessageMapper messageMapper;
    private final CollabNotificationMapper notificationMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CollabConversationService(
            CollabPlatformMapper platformMapper,
            CollabConversationMapper conversationMapper,
            CollabMessageMapper messageMapper,
            CollabNotificationMapper notificationMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        super(platformMapper);
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.notificationMapper = notificationMapper;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** 我参与的会话列表（单聊展示对方昵称，附未读数）。 */
    public List<Map<String, Object>> listConversations() {
        Long userId = currentUserId();
        List<Map<String, Object>> rows = conversationMapper.selectMyConversations(userId);
        List<Long> conversationIds = rows.stream().map(row -> numberAsLong(row.get("id"))).toList();
        Map<Long, List<Long>> otherMembers = new LinkedHashMap<>();
        if (!conversationIds.isEmpty()) {
            for (Map<String, Object> row : conversationMapper.selectOtherMembers(conversationIds, userId)) {
                Long conversationId = numberAsLong(row.get("conversationId"));
                otherMembers.computeIfAbsent(conversationId, key -> new ArrayList<>())
                        .add(numberAsLong(row.get("userId")));
            }
        }
        List<Long> allOtherIds = otherMembers.values().stream().flatMap(List::stream).toList();
        Map<Long, String> names = userNames(allOtherIds);
        for (Map<String, Object> row : rows) {
            Long conversationId = numberAsLong(row.get("id"));
            String type = String.valueOf(row.get("conversationType"));
            String title = row.get("title") == null ? null : String.valueOf(row.get("title"));
            if ("direct".equals(type)) {
                List<Long> others = otherMembers.getOrDefault(conversationId, List.of());
                row.put("title", others.isEmpty() ? "单聊" : names.getOrDefault(others.get(0), "用户 #" + others.get(0)));
            } else if (title == null || title.isBlank()) {
                row.put("title", "群组会话");
            }
            row.put("conversationTypeLabel", "direct".equals(type) ? "单聊" : "群组");
        }
        return rows;
    }

    @Transactional
    public CollabIdResponse createConversation(ConversationCreateRequest request) {
        Long creatorId = currentUserId();
        String type = defaultString(request == null ? null : request.conversationType(), "direct");
        if (!"direct".equals(type) && !"group".equals(type)) {
            throw new BusinessException("会话类型只能为 direct 或 group");
        }
        Set<Long> memberIds = new LinkedHashSet<>();
        if (request != null && request.memberIds() != null) {
            request.memberIds().stream().filter(java.util.Objects::nonNull).forEach(memberIds::add);
        }
        memberIds.remove(creatorId);
        if (memberIds.isEmpty()) {
            throw new BusinessException("请选择会话参与人");
        }
        if ("direct".equals(type) && memberIds.size() != 1) {
            throw new BusinessException("单聊只能有一个对方参与人");
        }
        for (Long userId : memberIds) {
            if (platformMapper.countActiveUser(userId) == 0) {
                throw new BusinessException("参与人不存在或已停用：" + userId);
            }
        }
        if ("direct".equals(type)) {
            Long otherUserId = memberIds.iterator().next();
            Map<String, Object> existing = conversationMapper.selectDirectConversation(creatorId, otherUserId);
            if (existing != null) {
                return new CollabIdResponse(numberAsLong(existing.get("id")));
            }
        }
        String operator = currentUsername();
        List<Long> allMembers = new ArrayList<>();
        allMembers.add(creatorId);
        allMembers.addAll(memberIds);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationType", type);
        row.put("title", trimToNull(request == null ? null : request.title()));
        row.put("creatorId", creatorId);
        row.put("memberCount", allMembers.size());
        conversationMapper.insertConversation(row, operator);
        Long conversationId = numberAsLong(row.get("id"));
        for (Long userId : allMembers) {
            conversationMapper.insertMember(conversationId, userId,
                    userId.equals(creatorId) ? "owner" : "member", operator);
        }
        return new CollabIdResponse(conversationId);
    }

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> listMessages(Long conversationId, PageQuery query) {
        Long userId = currentUserId();
        requireActiveMember(conversationId, userId);
        Page page = pageOf(query);
        long total = messageMapper.countMessages(conversationId);
        List<Map<String, Object>> rows = messageMapper.selectMessages(conversationId, page.offset(), page.pageSize());
        rows.forEach(row -> row.put("mentionedUserIds", splitIds((String) row.get("mentionedUserIds"))));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /**
     * 发送站内信。@提及会在事务提交后由投递端写入站内通知，并经 M10 统一投递入口进入待办收件箱。
     */
    @Transactional
    public Map<String, Object> sendMessage(Long conversationId, MessageSendRequest request) {
        Long senderId = currentUserId();
        requireActiveMember(conversationId, senderId);
        String content = required(request == null ? null : request.content(), "消息内容不能为空");
        if (content.length() > MESSAGE_MAX_LENGTH) {
            throw new BusinessException("消息内容不能超过 " + MESSAGE_MAX_LENGTH + " 个字符");
        }
        List<Long> mentioned = resolveMentionUserIds(content,
                request == null ? null : request.mentionedUserIds());
        mentioned.remove(senderId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", conversationId);
        row.put("senderId", senderId);
        row.put("contentType", "text");
        row.put("content", content);
        row.put("actionUrl", request == null ? null : trimToNull(request.actionUrl()));
        row.put("bizType", request == null ? null : trimToNull(request.bizType()));
        row.put("bizId", request == null ? null : request.bizId());
        row.put("mentionedUserIds", joinIds(mentioned));
        messageMapper.insertMessage(row, currentUsername());
        Long messageId = numberAsLong(row.get("id"));
        conversationMapper.updateLastMessage(conversationId, messageId, LocalDateTime.now(), currentUsername());

        if (!mentioned.isEmpty()) {
            for (Long userId : mentioned) {
                Map<String, Object> mention = new LinkedHashMap<>();
                mention.put("sourceType", "message");
                mention.put("sourceId", messageId);
                mention.put("conversationId", conversationId);
                mention.put("mentionedUserId", userId);
                mention.put("bizType", row.get("bizType"));
                mention.put("bizId", row.get("bizId"));
                mention.put("actionUrl", row.get("actionUrl"));
                mention.put("idempotencyKey", "mention:message:" + messageId + ":" + userId);
                notificationMapper.insertMention(mention, currentUsername());
            }
            eventPublisher.publishEvent(new CollabMentionEvent(
                    "message", messageId, conversationId, mentioned,
                    row.get("bizType") == null ? null : String.valueOf(row.get("bizType")),
                    row.get("bizId") == null ? null : numberAsLong(row.get("bizId")),
                    row.get("actionUrl") == null ? null : String.valueOf(row.get("actionUrl")),
                    senderId, userNames(List.of(senderId)).getOrDefault(senderId, "用户 #" + senderId), content));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", messageId);
        result.put("conversationId", conversationId);
        result.put("mentionedUserIds", mentioned);
        return result;
    }

    /** 标记已读：把已读游标推进到指定消息（不能倒退）。 */
    @Transactional
    public Map<String, Object> markRead(Long conversationId, ConversationReadRequest request) {
        Long userId = currentUserId();
        requireActiveMember(conversationId, userId);
        Long lastReadMessageId = requiredLong(request == null ? null : request.lastReadMessageId(),
                "已读消息 ID 不能为空");
        Map<String, Object> message = messageMapper.selectMessageById(lastReadMessageId);
        if (message == null || !conversationId.equals(numberAsLong(message.get("conversationId")))) {
            throw new BusinessException("已读消息不属于该会话");
        }
        conversationMapper.upsertRead(conversationId, userId, lastReadMessageId, currentUsername());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("lastReadMessageId", lastReadMessageId);
        result.put("unreadCount", conversationMapper.countUnreadMessages(userId));
        return result;
    }

    /** 我在全部会话里的未读消息总数（顶栏提醒用）。 */
    public Map<String, Object> unreadCount() {
        Long userId = currentUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCount", conversationMapper.countUnreadMessages(userId));
        result.put("unreadNotifications", notificationMapper.countUnreadNotifications(userId));
        return result;
    }

    // ------------------------------------------------------------------

    /** 必须是会话在册成员才能读写（移出成员不能继续访问历史会话）。 */
    private void requireActiveMember(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new BusinessException("会话 ID 不能为空");
        }
        Map<String, Object> conversation = conversationMapper.selectConversationById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在或已删除");
        }
        Map<String, Object> member = conversationMapper.selectMember(conversationId, userId);
        if (member == null || member.get("leaveTime") != null) {
            throw new BusinessException("你不是该会话的在册成员，无权访问");
        }
    }
}
