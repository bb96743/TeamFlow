package com.teamflow.knowledge.domain;

import java.util.List;

/**
 * 跨模块归档请求（M06 会议纪要、M14 项目复盘等业务模块调用 M07 的归档 Service）。
 *
 * <p><b>契约（L0 §9.1 D-2026-10-26-11 结论三）</b>：业务正文只存 M07 的 knowledge_page，
 * 业务模块表里只保存返回的 pageId；不得把正文复制回自己的表。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code sourceModule}/{@code sourceBizType}/{@code sourceBizId}：归档来源三要素，
 *       用于幂等（同一业务单据重复归档返回同一 pageId）与来源追溯，必填；</li>
 *   <li>{@code spaceId}/{@code nodeId}：归属空间与目录；spaceId 为空时落入预置归档空间，
 *       nodeId 为空表示挂在空间根；</li>
 *   <li>{@code title}/{@code content}：页面标题与 Markdown 正文；</li>
 *   <li>{@code tags}/{@code summary}：可选标签与摘要（如结项结论）；</li>
 *   <li>{@code operatorUserId}/{@code operatorName}：操作人；为空时取当前登录用户；</li>
 *   <li>{@code overwriteExisting}：重复归档且内容有更新时，是否生成新版本覆盖正文（默认否）。</li>
 * </ul>
 *
 * @param sourceModule      来源模块，如 M06 / M14
 * @param sourceBizType     来源业务类型，如 meeting_minutes / project_retro
 * @param sourceBizId       来源业务单据 ID（会议 ID / 项目 ID）
 * @param spaceId           归属空间 ID（可空：默认归档空间）
 * @param nodeId            归属目录 ID（可空：空间根）
 * @param title             页面标题
 * @param content           Markdown 正文
 * @param tags              标签列表（可空）
 * @param summary           摘要（可空）
 * @param operatorUserId    操作人用户 ID（可空）
 * @param operatorName      操作人账号（可空）
 * @param overwriteExisting 已存在归档页面时是否用本次正文生成新版本
 */
public record KnowledgeArchiveRequest(
        String sourceModule,
        String sourceBizType,
        Long sourceBizId,
        Long spaceId,
        Long nodeId,
        String title,
        String content,
        List<String> tags,
        String summary,
        Long operatorUserId,
        String operatorName,
        boolean overwriteExisting
) {
}
