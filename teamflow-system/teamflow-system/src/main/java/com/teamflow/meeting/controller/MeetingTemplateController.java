package com.teamflow.meeting.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.meeting.domain.dto.MeetingTemplateWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.service.MeetingTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M06 会议模板接口（{@code /api/meeting/templates}）：常规会议、目标校准会、复盘会模板。
 */
@RestController
@RequestMapping("/api/meeting/templates")
public class MeetingTemplateController {

    private final MeetingTemplateService service;

    public MeetingTemplateController(MeetingTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("meeting:template:list")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String templateType,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(keyword, templateType, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("meeting:template:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("meeting:template:add")
    @OperLog(module = "会议模板", action = "新增模板")
    public R<MeetingIdResponse> create(@RequestBody MeetingTemplateWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("meeting:template:edit")
    @OperLog(module = "会议模板", action = "修改模板")
    public R<Void> update(@PathVariable Long id, @RequestBody MeetingTemplateWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("meeting:template:remove")
    @OperLog(module = "会议模板", action = "删除模板")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
