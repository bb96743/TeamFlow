package com.teamflow.collab.service;

import com.teamflow.collab.domain.dto.CommentWriteRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.event.CollabMentionEvent;
import com.teamflow.collab.mapper.CollabCommentMapper;
import com.teamflow.collab.mapper.CollabNotificationMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M09 统一评论服务（需求 2.2.2，D-2026-10-25-02 / D-2026-10-26-11 结论二）。
 *
 * <p>本表是**全站唯一评论存储**：按 {@code biz_type + biz_id} 多态挂载任意业务对象
 * （任务/文档/会议…），其他模块不得自建评论表。@提及解析与通知只在这里实现一次，
 * 避免各模块漏发；提及通知经事务提交后由投递端处理，待办投递只走 M10 统一入口。</p>
 *
 * <p><b>权限说明</b>：评论的读写鉴权由评论权限码控制；业务对象本身的可见性 / 数据范围由归属模块负责，
 * 本模块无法替代（已在交付 CR 的已知问题中登记）。</p>
 */
@Service
public class CollabCommentService extends CollabServiceSupport {

    private static final int CONTENT_MAX_LENGTH = 2000;

    private final CollabCommentMapper commentMapper;
    private final CollabNotificationMapper notificationMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CollabCommentService(
            CollabPlatformMapper platformMapper,
            CollabCommentMapper commentMapper,
            CollabNotificationMapper notificationMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        super(platformMapper);
        this.commentMapper = commentMapper;
        this.notificationMapper = notificationMapper;
        this.eventPublisher = eventPublisher;
    }

    public PageResult<Map<String, Object>> list(String bizType, Long bizId, PageQuery query) {
        String type = required(bizType, "业务类型不能为空");
        if (bizId == null) {
            throw new BusinessException("业务单据 ID 不能为空");
        }
        Page page = pageOf(query);
        long total = commentMapper.countComments(type, bizId);
        List<Map<String, Object>> rows = commentMapper.selectComments(type, bizId, page.offset(), page.pageSize());
        rows.forEach(row -> row.put("mentionedUserIds", splitIds((String) row.get("mentionedUserIds"))));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /**
     * 发表评论：正文中的 @账号 与显式提及都会解析为提及对象，事务提交后统一通知。
     */
    @Transactional
    public CollabIdResponse create(CommentWriteRequest request) {
        String bizType = required(request == null ? null : request.bizType(), "业务类型不能为空");
        Long bizId = request == null ? null : request.bizId();
        if (bizId == null) {
            throw new BusinessException("业务单据 ID 不能为空");
        }
        String content = required(request.content(), "评论内容不能为空");
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException("评论内容不能超过 " + CONTENT_MAX_LENGTH + " 个字符");
        }
        long parentId = request.parentId() == null ? 0L : request.parentId();
        if (parentId > 0) {
            Map<String, Object> parent = commentMapper.selectCommentById(parentId);
            if (parent == null) {
                throw new BusinessException("父评论不存在或已删除");
            }
            if (!bizType.equals(String.valueOf(parent.get("bizType")))
                    || !bizId.equals(numberAsLong(parent.get("bizId")))) {
                throw new BusinessException("父评论不属于该业务对象");
            }
        }
        Long authorId = currentUserId();
        List<Long> mentioned = resolveMentionUserIds(content, request.mentionedUserIds());
        mentioned.remove(authorId);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("parentId", parentId);
        row.put("content", content);
        row.put("authorId", authorId);
        row.put("mentionedUserIds", joinIds(mentioned));
        commentMapper.insertComment(row, currentUsername());
        Long commentId = numberAsLong(row.get("id"));

        if (!mentioned.isEmpty()) {
            String actionUrl = "/personal/todo?commentId=" + commentId
                    + "&bizType=" + bizType + "&bizId=" + bizId;
            for (Long userId : mentioned) {
                Map<String, Object> mention = new LinkedHashMap<>();
                mention.put("sourceType", "comment");
                mention.put("sourceId", commentId);
                mention.put("conversationId", null);
                mention.put("mentionedUserId", userId);
                mention.put("bizType", bizType);
                mention.put("bizId", bizId);
                mention.put("actionUrl", actionUrl);
                mention.put("idempotencyKey", "mention:comment:" + commentId + ":" + userId);
                notificationMapper.insertMention(mention, currentUsername());
            }
            eventPublisher.publishEvent(new CollabMentionEvent(
                    "comment", commentId, null, mentioned, bizType, bizId, actionUrl, authorId,
                    userNames(List.of(authorId)).getOrDefault(authorId, "用户 #" + authorId), content));
        }
        return new CollabIdResponse(commentId);
    }

    /** 删除评论：仅允许作者本人删除（逻辑删除，保留审计与历史）。 */
    @Transactional
    public void delete(Long commentId) {
        Map<String, Object> comment = commentMapper.selectCommentById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在或已删除");
        }
        if (!currentUserId().equals(numberAsLong(comment.get("authorId")))) {
            throw new BusinessException("只能删除本人发表的评论");
        }
        commentMapper.softDeleteComment(commentId, currentUsername());
    }
}
