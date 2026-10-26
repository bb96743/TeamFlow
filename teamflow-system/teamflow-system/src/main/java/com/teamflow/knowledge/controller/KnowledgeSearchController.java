package com.teamflow.knowledge.controller;

import com.teamflow.common.domain.R;
import com.teamflow.knowledge.service.KnowledgeSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M07 全文检索接口（1.2.6）。
 *
 * <p>按 M07 RULE §五，检索入口对已登录用户开放（不再叠加按钮权限）；结果只返回当前用户可读页面，
 * 返回体带检索引擎与耗时，便于核对检索边界。</p>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeSearchController {

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public R<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String pageType,
            @RequestParam(required = false) Integer limit
    ) {
        return R.success(searchService.search(keyword, spaceId, pageType, limit));
    }
}
