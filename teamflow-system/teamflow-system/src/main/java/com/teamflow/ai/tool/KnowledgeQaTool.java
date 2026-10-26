package com.teamflow.ai.tool;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.domain.AiCaller;
import com.teamflow.knowledge.domain.vo.PageContentSlice;
import com.teamflow.knowledge.service.KnowledgePageService;
import com.teamflow.knowledge.service.KnowledgeSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：知识库问答（knowledge.qa）——RAG 不引向量库（L0 D-2026-10-27-12 结论三）。
 *
 * <p>检索使用 M07 的 ngram 全文检索（含单字回落 LIKE），结果本身已按当前用户页面 ACL 过滤；
 * 本工具再把片段与页面 ID、标题、命中偏移整理成引用溯源，回答只依据这些片段。</p>
 */
@Component
public class KnowledgeQaTool implements AiTool {

    private final KnowledgeSearchService searchService;
    private final KnowledgePageService pageService;
    private final AiToolSupport support;
    private final AiProperties properties;

    public KnowledgeQaTool(KnowledgeSearchService searchService, KnowledgePageService pageService,
                           AiToolSupport support, AiProperties properties) {
        this.searchService = searchService;
        this.pageService = pageService;
        this.support = support;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "knowledge.qa";
    }

    @Override
    public String label() {
        return "知识库检索问答";
    }

    @Override
    public String permissionCode() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        String keyword = support.text(params, "keyword", "");
        if (keyword.isBlank()) {
            return ToolOutcome.error(name(), params, permissionCode(), "请提供要检索的知识关键词或问题");
        }
        Long spaceId = support.longValue(params, "spaceId");
        Integer limit = null;
        Object limitValue = params.get("limit");
        if (limitValue instanceof Number number) {
            limit = number.intValue();
        }
        int size = limit == null ? Math.max(1, properties.getMaxCitations()) : Math.max(1, Math.min(limit, 20));

        Map<String, Object> searched = searchService.search(keyword, spaceId, null, size);
        List<Map<String, Object>> hits = support.rowsOf(searched.get("results"));
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("pageId", hit.get("pageId"));
            citation.put("pageTitle", hit.get("pageTitle"));
            citation.put("pageType", hit.get("pageType"));
            citation.put("spaceName", hit.get("spaceName"));
            citation.put("snippet", hit.get("snippet"));
            citation.put("score", hit.get("score"));
            citation.put("offset", offsetOf(hit, keyword));
            citations.add(citation);
        }

        String engine = String.valueOf(searched.get("engine"));
        Object elapsedMs = searched.get("elapsedMs");
        StringBuilder summary = new StringBuilder();
        if (citations.isEmpty()) {
            summary.append("未找到依据：知识库中没有与「").append(keyword)
                    .append("」匹配的可读页面（引擎：").append(engine).append("，耗时 ").append(elapsedMs)
                    .append(" ms）。建议换一个关键词，或先请文档负责人归档相关资料。");
        } else {
            summary.append("知识库命中 ").append(citations.size()).append(" 条可读页面（引擎：")
                    .append(engine).append("，耗时 ").append(elapsedMs).append(" ms）：");
            int index = 1;
            for (Map<String, Object> citation : citations) {
                summary.append("\n[").append(index++).append("] ")
                        .append(citation.get("pageTitle")).append("：")
                        .append(citation.get("snippet"));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("engine", engine);
        data.put("elapsedMs", elapsedMs);
        data.put("total", citations.size());
        data.put("citations", citations);
        return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data, citations);
    }

    /** 片段在页面正文中的字符偏移（用于溯源定位；取不到正文时退化为 0）。 */
    private int offsetOf(Map<String, Object> hit, String keyword) {
        Object pageIdValue = hit.get("pageId");
        if (!(pageIdValue instanceof Number number)) {
            return 0;
        }
        try {
            PageContentSlice slice = pageService.sliceContent(number.longValue(), 0,
                    Math.max(2000, properties.getMaxContextChars()));
            if (slice == null || slice.content() == null) {
                return 0;
            }
            String content = slice.content();
            int index = content.indexOf(keyword);
            if (index >= 0) {
                return index;
            }
            String snippet = String.valueOf(hit.get("snippet"));
            String probe = snippet.length() > 12 ? snippet.substring(0, 12) : snippet;
            return Math.max(0, content.indexOf(probe));
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
