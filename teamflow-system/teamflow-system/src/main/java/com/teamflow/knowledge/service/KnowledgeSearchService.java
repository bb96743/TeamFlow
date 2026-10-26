package com.teamflow.knowledge.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.mapper.KnowledgePageMapper;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import com.teamflow.knowledge.mapper.KnowledgeSpaceMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M07 全文检索服务（1.2.6）。
 *
 * <p><b>能力边界（L0 §9.1 D-2026-10-26-11 结论六）</b>：本期用 MySQL InnoDB FULLTEXT（ngram 分词，
 * 服务器 ngram_token_size=2）做中文检索；单字、索引不可用或未命中时回落 LIKE。不引入外部搜索引擎。
 * 检索只在知识页面内进行；跨模块聚合检索（任务/消息等）属后续迭代，不在本期直连其他模块业务表。</p>
 *
 * <p>结果按授权链过滤（只返回当前用户可读页面），并按命中度/更新时间排序；返回检索引擎与耗时，
 * 便于在验收和运维中核对「为什么快/慢、走了哪个引擎」。</p>
 */
@Service
public class KnowledgeSearchService extends KnowledgeServiceSupport {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int SNIPPET_MAX_LENGTH = 200;
    private static final int NGRAM_TOKEN_SIZE = 2;

    private final KnowledgePageMapper pageMapper;
    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeAccessService access;

    public KnowledgeSearchService(
            KnowledgePlatformMapper platformMapper,
            KnowledgePageMapper pageMapper,
            KnowledgeSpaceMapper spaceMapper,
            KnowledgeAccessService access
    ) {
        super(platformMapper);
        this.pageMapper = pageMapper;
        this.spaceMapper = spaceMapper;
        this.access = access;
    }

    /**
     * 检索知识页面。
     *
     * @param keyword  关键词（必填；1 个字符走 LIKE，2 个及以上优先 FULLTEXT）
     * @param spaceId  可选：限定空间（会先校验该空间读权限）
     * @param pageType 可选：wiki/sop/decision/minutes/retro
     * @param limit    可选：返回条数（默认 20，最大 50）
     */
    public Map<String, Object> search(String keyword, Long spaceId, String pageType, Integer limit) {
        String key = required(keyword, "检索关键词不能为空");
        if (spaceId != null) {
            access.requireSpaceRead(spaceId);
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String type = trimToNull(pageType);
        long began = System.nanoTime();
        String engine;
        List<Map<String, Object>> rows;
        if (key.codePointCount(0, key.length()) < NGRAM_TOKEN_SIZE) {
            rows = pageMapper.searchLike(key, spaceId, type, size);
            engine = "like-short-keyword";
        } else {
            try {
                rows = pageMapper.searchFulltext(key, spaceId, type, size);
                engine = "fulltext-ngram";
                if (rows.isEmpty()) {
                    rows = pageMapper.searchLike(key, spaceId, type, size);
                    engine = "fulltext-miss-like-fallback";
                }
            } catch (RuntimeException exception) {
                rows = pageMapper.searchLike(key, spaceId, type, size);
                engine = "like-fallback";
            }
        }
        List<Map<String, Object>> visible = access.filterReadablePages(rows);
        Map<Long, String> spaceNames = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : visible) {
            Long resultSpaceId = numberAsLong(row.get("spaceId"));
            String spaceName = spaceNames.computeIfAbsent(resultSpaceId, id -> {
                Map<String, Object> space = id == null ? null : spaceMapper.selectSpaceById(id);
                return space == null ? null : String.valueOf(space.get("spaceName"));
            });
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pageId", numberAsLong(row.get("id")));
            item.put("pageTitle", row.get("pageTitle"));
            item.put("pageType", row.get("pageType"));
            item.put("spaceId", resultSpaceId);
            item.put("spaceName", spaceName);
            item.put("nodeId", numberAsLong(row.get("nodeId")));
            item.put("tags", row.get("tags"));
            item.put("updateTime", row.get("updateTime"));
            item.put("score", roundScore(row.get("score")));
            item.put("snippet", snippet(row.get("rawSnippet"), key));
            results.add(item);
        }
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", key);
        result.put("spaceId", spaceId);
        result.put("pageType", type);
        result.put("engine", engine);
        result.put("elapsedMs", elapsedMs);
        result.put("total", results.size());
        result.put("results", results);
        result.put("note", "仅检索当前用户可读页面；本期只覆盖知识页面，跨模块聚合检索属后续迭代（D-11 结论六）");
        return result;
    }

    private Object roundScore(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(4, java.math.RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(4, java.math.RoundingMode.HALF_UP);
        }
        return value;
    }

    private String snippet(Object raw, String keyword) {
        String text = raw == null ? "" : String.valueOf(raw)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll(" {2,}", " ")
                .trim();
        if (text.isEmpty()) {
            return "命中标题或标签：「" + keyword + "」";
        }
        if (text.length() > SNIPPET_MAX_LENGTH) {
            text = text.substring(0, SNIPPET_MAX_LENGTH) + "…";
        }
        return "…" + text + "…";
    }
}
