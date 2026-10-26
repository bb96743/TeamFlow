package com.teamflow.collab.controller;

import com.teamflow.collab.domain.dto.CommentWriteRequest;
import com.teamflow.collab.domain.vo.CollabIdResponse;
import com.teamflow.collab.service.CollabCommentService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M09 统一评论接口（{@code /api/collab/comments}，需求 2.2.2）。
 *
 * <p>同一张评论表按 {@code bizType + bizId} 多态挂载任意业务对象；业务模块只传标识，
 * 不得自建评论表（D-2026-10-25-02）。@提及解析与通知由本模块统一承担。</p>
 */
@RestController
@RequestMapping("/api/collab/comments")
public class CollabCommentController {

    private final CollabCommentService service;

    public CollabCommentController(CollabCommentService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("collab:comment:list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam String bizType,
            @RequestParam Long bizId,
            PageQuery query
    ) {
        return R.success(service.list(bizType, bizId, query));
    }

    @PostMapping
    @RequiresPermission("collab:comment:add")
    @OperLog(module = "统一评论", action = "发表评论")
    public R<CollabIdResponse> create(@RequestBody CommentWriteRequest request) {
        return R.success(service.create(request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("collab:comment:remove")
    @OperLog(module = "统一评论", action = "删除评论")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }
}
