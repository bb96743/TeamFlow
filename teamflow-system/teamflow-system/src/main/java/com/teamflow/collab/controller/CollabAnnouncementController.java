package com.teamflow.collab.controller;

import com.teamflow.collab.domain.dto.AnnouncementWriteRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.service.CollabAnnouncementService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M09 公告接口（{@code /api/collab/announcements}，需求 2.2.4）。
 *
 * <p>全员公告对所有已登录用户可见；部门公告按平台数据权限口径判定可见性。</p>
 */
@RestController
@RequestMapping("/api/collab/announcements")
public class CollabAnnouncementController {

    private final CollabAnnouncementService service;

    public CollabAnnouncementController(CollabAnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("collab:notice:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String status
    ) {
        return R.success(service.list(query, status));
    }

    @GetMapping("/{id}")
    @RequiresPermission("collab:notice:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @PostMapping
    @RequiresPermission("collab:notice:add")
    @OperLog(module = "公告", action = "发布公告")
    public R<CollabIdResponse> create(@RequestBody AnnouncementWriteRequest request) {
        return R.success(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("collab:notice:edit")
    @OperLog(module = "公告", action = "编辑公告")
    public R<Void> update(@PathVariable Long id, @RequestBody AnnouncementWriteRequest request) {
        service.update(id, request);
        return R.success(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("collab:notice:remove")
    @OperLog(module = "公告", action = "删除公告")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    @PutMapping("/{id}/read")
    @RequiresPermission("collab:notice:list")
    @OperLog(module = "公告", action = "标记已读")
    public R<Void> read(@PathVariable Long id) {
        service.markRead(id);
        return R.success(null);
    }
}
