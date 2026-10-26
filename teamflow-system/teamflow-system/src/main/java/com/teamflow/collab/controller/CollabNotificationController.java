package com.teamflow.collab.controller;

import com.teamflow.collab.domain.dto.InternalNotifyRequest;
import com.teamflow.collab.service.CollabNotificationService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M09 通知接口：我的通知 + **统一通知投递入口**（业务模块唯一入口，需求 2.2.1）。
 *
 * <p>内部投递入口只接收「接收人 + 模板编码 + 参数 + 业务链接」，不接收裸 HTML；
 * 需要进入个人待办收件箱时，本模块只经 M10 统一投递入口，不写待办数据。</p>
 */
@RestController
@RequestMapping("/api/collab")
public class CollabNotificationController {

    private final CollabNotificationService service;

    public CollabNotificationController(CollabNotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    @RequiresPermission("collab:msg:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly
    ) {
        return R.success(service.list(query, unreadOnly));
    }

    @GetMapping("/notifications/unread-count")
    @RequiresPermission("collab:msg:list")
    public R<Map<String, Object>> unreadCount() {
        return R.success(service.unreadCount());
    }

    @PutMapping("/notifications/{id}/read")
    @RequiresPermission("collab:msg:list")
    public R<Void> markRead(@PathVariable Long id) {
        service.markRead(id);
        return R.success(null);
    }

    @PutMapping("/notifications/read-all")
    @RequiresPermission("collab:msg:list")
    public R<Void> markAllRead() {
        service.markAllRead();
        return R.success(null);
    }

    /** 统一通知投递入口（内部鉴权：权限码不授予一般角色）。 */
    @PostMapping("/internal/notify")
    @RequiresPermission("collab:internal:notify")
    @OperLog(module = "统一通知", action = "内部投递")
    public R<Map<String, Object>> notifyInternal(@RequestBody InternalNotifyRequest request) {
        return R.success(service.notifyInternal(request));
    }
}
