package com.teamflow.personal.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.personal.domain.dto.FocusSessionEndRequest;
import com.teamflow.personal.domain.dto.FocusSessionStartRequest;
import com.teamflow.personal.domain.vo.PersonalIdResponse;
import com.teamflow.personal.service.FocusSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M10 专注会话接口（{@code /api/personal/focus-sessions}）。
 *
 * <p>本期只做后端留痕（开始/结束/列表），免打扰时段与前端计时交互不在范围（task-13 明确不做）。</p>
 */
@RestController
@RequestMapping("/api/personal/focus-sessions")
public class FocusSessionController {

    private final FocusSessionService focusSessionService;

    public FocusSessionController(FocusSessionService focusSessionService) {
        this.focusSessionService = focusSessionService;
    }

    @GetMapping
    @RequiresPermission("personal:focus:list")
    public R<PageResult<Map<String, Object>>> list(PageQuery query) {
        return R.success(focusSessionService.list(query));
    }

    @PostMapping
    @RequiresPermission("personal:focus:add")
    @OperLog(module = "专注计时", action = "开始专注")
    public R<PersonalIdResponse> start(@RequestBody(required = false) FocusSessionStartRequest request) {
        return R.success(new PersonalIdResponse(focusSessionService.start(request)));
    }

    @PutMapping("/{id}/end")
    @RequiresPermission("personal:focus:add")
    @OperLog(module = "专注计时", action = "结束专注")
    public R<Map<String, Object>> end(
            @PathVariable Long id, @RequestBody(required = false) FocusSessionEndRequest request) {
        return R.success(focusSessionService.end(id, request));
    }
}
