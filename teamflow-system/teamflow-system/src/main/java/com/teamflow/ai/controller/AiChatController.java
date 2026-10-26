package com.teamflow.ai.controller;

import com.teamflow.ai.domain.dto.AiChatRequest;
import com.teamflow.ai.service.AiConversationService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M11 对话助手接口（1.3.1 / 1.3.2）。
 *
 * <p>对话会自动创建/续用会话；工具以当前登录用户身份执行。
 * 本轮未做 SSE 流式输出（同步返回完整回答），流式属后续增强。</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final AiConversationService conversationService;

    public AiChatController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/chat")
    @RequiresPermission("ai:chat:use")
    public R<Map<String, Object>> chat(@RequestBody AiChatRequest request) {
        return R.success(conversationService.chat(request));
    }

    @GetMapping("/sessions")
    @RequiresPermission("ai:chat:use")
    public R<PageResult<Map<String, Object>>> sessions(PageQuery query) {
        return R.success(conversationService.listSessions(query));
    }

    @PostMapping("/sessions")
    @RequiresPermission("ai:chat:use")
    public R<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null || body.get("title") == null ? null : String.valueOf(body.get("title"));
        return R.success(conversationService.createSession(title));
    }

    @GetMapping("/sessions/{id}/messages")
    @RequiresPermission("ai:chat:use")
    public R<List<Map<String, Object>>> messages(@PathVariable Long id) {
        return R.success(conversationService.messages(id));
    }
}
