package com.teamflow.knowledge.domain;

/**
 * 跨模块归档结果：业务模块只需保存 {@code pageId}，其余字段用于联调与幂等判断。
 *
 * @param pageId   知识页面 ID（业务模块表里保存的唯一引用）
 * @param spaceId  归属空间 ID
 * @param nodeId   归属目录 ID（0 表示空间根）
 * @param versionNo 当前版本号
 * @param pageType 页面类型（minutes 会议纪要 / retro 项目复盘 / wiki 其他）
 * @param created  本次是否新建（false 表示命中已有归档页面）
 */
public record KnowledgeArchiveResult(
        Long pageId,
        Long spaceId,
        Long nodeId,
        Integer versionNo,
        String pageType,
        boolean created
) {
}
