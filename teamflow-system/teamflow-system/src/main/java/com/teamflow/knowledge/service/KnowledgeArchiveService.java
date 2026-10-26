package com.teamflow.knowledge.service;

import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;

/**
 * 跨模块归档 Service（M06 会议纪要、M14 项目复盘调用）。
 *
 * <p>契约（L0 §9.1 D-2026-10-26-11 结论三）：业务正文只存知识页面，业务模块表里只保存返回的 pageId；
 * 同一来源三要素（sourceModule + sourceBizType + sourceBizId）重复调用幂等返回同一页面。</p>
 */
public interface KnowledgeArchiveService {

    /**
     * 归档一个业务正文为知识页面。
     *
     * @param request 归档请求（标题 + Markdown 正文 + 归属空间/目录 + 来源三要素）
     * @return 归档结果；M06/M14 只需持久化 {@code result.pageId()}
     * @throws com.teamflow.common.exception.BusinessException 参数不合法、归属空间/目录不存在
     */
    KnowledgeArchiveResult archive(KnowledgeArchiveRequest request);

    /**
     * 查询某业务单据已归档的页面 ID（用于业务模块回读与幂等判断）。
     *
     * @return 页面 ID；未归档返回空
     */
    java.util.Optional<Long> findArchivedPageId(String sourceModule, String sourceBizType, Long sourceBizId);
}
