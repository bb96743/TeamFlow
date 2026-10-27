package com.teamflow.ai.controller;

import com.teamflow.ai.domain.dto.SuggestionAssignRequest;
import com.teamflow.ai.service.AiWorkloadSuggestionService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 待办建议接口（1.3.5）。
 *
 * <p><b>只建议、不派发</b>：接口只返回建议与可解释理由，不提供"一键执行"动作；
 * 如需调整任务负责人，必须由用户显式调用任务模块的指派接口。</p>
 */
@RestController
@RequestMapping("/api/ai/suggestions")
public class AiSuggestionController {

    private final AiWorkloadSuggestionService service;

    public AiSuggestionController(AiWorkloadSuggestionService service) {
        this.service = service;
    }

    /** 基于负载均衡规则为目标任务生成分配建议（只写入建议表，不改任务）。 */
    @PostMapping("/assign")
    @RequiresPermission("ai:suggest:generate")
    @OperLog(module = "AI 助手", action = "生成任务分配建议")
    public R<Map<String, Object>> generate(@RequestBody SuggestionAssignRequest request) {
        return R.success(service.generate(request));
    }

    @GetMapping
    @RequiresPermission("ai:suggest:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("ai:suggest:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }
}
