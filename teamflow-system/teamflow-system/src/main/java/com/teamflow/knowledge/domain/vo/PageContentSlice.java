package com.teamflow.knowledge.domain.vo;

/**
 * 正文切片（供 M11 RAG 按页面 ID 取正文片段；只读、不含向量与排序逻辑）。
 *
 * @param pageId       页面 ID
 * @param pageTitle    页面标题
 * @param pageVersion  页面当前版本号
 * @param contentFormat 正文格式（统一 markdown）
 * @param offset       起始字符偏移（从 0 开始）
 * @param limit        本次请求的最大字符数
 * @param totalLength  正文总字符数
 * @param content      切片正文
 * @param truncated    是否还有未返回的后续内容
 * @param updatedAt    页面最近更新时间（yyyy-MM-dd HH:mm）
 */
public record PageContentSlice(
        Long pageId,
        String pageTitle,
        Integer pageVersion,
        String contentFormat,
        int offset,
        int limit,
        int totalLength,
        String content,
        boolean truncated,
        String updatedAt
) {
}
