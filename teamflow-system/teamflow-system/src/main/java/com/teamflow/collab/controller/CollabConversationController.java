package com.teamflow.collab.controller;

import com.teamflow.collab.domain.dto.ConversationCreateRequest;
import com.teamflow.collab.domain.dto.ConversationReadRequest;
import com.teamflow.collab.domain.dto.MessageSendRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.service.CollabConversationService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M09 站内信接口（{@code /api/collab/conversations}，需求 2.2.1）。
 *
 * <p>本模块是全站唯一通知出口：会话与消息只在这里存储；@提及需要通过待办提醒时，
 * 只经个人待办收件箱的统一投递入口，不在此处直接写待办数据。</p>
 */
@RestController
@RequestMapping("/api/collab/conversations")
public class CollabConversationController {

    private final CollabConversationService service;

    public CollabConversationController(CollabConversationService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("collab:msg:list")
    public R<List<Map<String, Object>>> list() {
        return R.success(service.listConversations());
    }

    @PostMapping
    @RequiresPermission("collab:msg:send")
    @OperLog(module = "站内信", action = "创建会话")
    public R<CollabIdResponse> create(@RequestBody ConversationCreateRequest request) {
        return R.success(service.createConversation(request));
    }

    @GetMapping("/{id}/messages")
    @RequiresPermission("collab:msg:list")
    public R<PageResult<Map<String, Object>>> messages(@PathVariable Long id, PageQuery query) {
        return R.success(service.listMessages(id, query));
    }

    @PostMapping("/{id}/messages")
    @RequiresPermission("collab:msg:send")
    @OperLog(module = "站内信", action = "发送消息")
    public R<Map<String, Object>> send(@PathVariable Long id, @RequestBody MessageSendRequest request) {
        return R.success(service.sendMessage(id, request));
    }

    @PutMapping("/{id}/read")
    @RequiresPermission("collab:msg:list")
    @OperLog(module = "站内信", action = "标记已读")
    public R<Map<String, Object>> read(@PathVariable Long id, @RequestBody ConversationReadRequest request) {
        return R.success(service.markRead(id, request));
    }

    @GetMapping("/unread-count")
    @RequiresPermission("collab:msg:list")
    public R<Map<String, Object>> unreadCount() {
        return R.success(service.unreadCount());
    }
}
