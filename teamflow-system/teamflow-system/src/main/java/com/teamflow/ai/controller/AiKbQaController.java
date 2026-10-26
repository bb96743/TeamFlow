package com.teamflow.ai.controller;

import com.teamflow.ai.domain.dto.AiKbQaRequest;
import com.teamflow.ai.service.AiConversationService;
import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M11 知识库问答接口（1.3.3）：用 M07 全文检索取片段，回答返回引用溯源（pageId + 标题 + 片段 + 偏移）。
 */
@RestController
@RequestMapping("/api/ai/kb")
public class AiKbQaController {

    private final AiConversationService conversationService;

    public AiKbQaController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/qa")
    @RequiresPermission("ai:kb:ask")
    public R<Map<String, Object>> qa(@RequestBody AiKbQaRequest request) {
        return R.success(conversationService.qa(request));
    }
}
