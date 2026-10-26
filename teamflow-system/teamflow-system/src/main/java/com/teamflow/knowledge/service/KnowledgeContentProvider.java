package com.teamflow.knowledge.service;

import com.teamflow.knowledge.domain.vo.PageContentSlice;

/**
 * 正文切片只读端口（供 M11 RAG 按页面 ID 取正文片段）。
 *
 * <p>边界（D-11 结论六）：M07 只保证正文可稳定读取；切片、embedding、召回、排序均在 M11 实现，
 * 本端口不做向量化、不做检索排序。</p>
 */
public interface KnowledgeContentProvider {

    /**
     * 按页面 ID 取正文切片。
     *
     * @param pageId 页面 ID
     * @param offset 起始字符偏移（从 0 开始；空按 0）
     * @param limit  本次最大字符数（空按 2000，最大 8000）
     * @return 切片结果；页面不存在时抛业务异常
     */
    PageContentSlice sliceContent(Long pageId, Integer offset, Integer limit);
}
