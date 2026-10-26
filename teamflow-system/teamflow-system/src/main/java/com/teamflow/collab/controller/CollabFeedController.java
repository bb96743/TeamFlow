package com.teamflow.collab.controller;

import com.teamflow.collab.service.CollabFeedService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M09 动态流接口（{@code /api/collab/feeds}，需求 2.2.3）。
 *
 * <p>只读：动态条目全部由领域事件投影生成，业务模块与调用方都不能直写动态流。</p>
 */
@RestController
@RequestMapping("/api/collab/feeds")
public class CollabFeedController {

    private final CollabFeedService service;

    public CollabFeedController(CollabFeedService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("collab:feed:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action
    ) {
        return R.success(service.list(query, actorId, action));
    }
}
