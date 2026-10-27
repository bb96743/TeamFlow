package com.teamflow.ai.controller;

import com.teamflow.ai.service.AiSmartReminderService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 智能提醒接口（1.3.6）：只做「何时提醒谁」的判定，实际触达由通知模块执行。
 */
@RestController
@RequestMapping("/api/ai/reminders")
public class AiReminderController {

    private final AiSmartReminderService service;

    public AiReminderController(AiSmartReminderService service) {
        this.service = service;
    }

    /** 扫描当前用户可见任务并判定提醒；命中者经 M09 统一通知入口触达。 */
    @PostMapping("/scan")
    @RequiresPermission("ai:reminder:scan")
    @OperLog(module = "AI 助手", action = "扫描智能提醒")
    public R<Map<String, Object>> scan(
            @RequestParam(required = false) Integer withinDays,
            @RequestParam(required = false) Integer limit
    ) {
        return R.success(service.scan(withinDays, limit));
    }

    @GetMapping
    @RequiresPermission("ai:reminder:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, status));
    }
}
