package com.teamflow.collab.domain.dto;

/**
 * 公告新增 / 编辑请求。
 *
 * @param title     公告标题（必填）
 * @param content   公告正文（必填）
 * @param scopeType 可见范围：all 全员，dept 指定部门（与平台数据权限口径一致）
 * @param deptId    指定部门 ID（scopeType=dept 时必填）
 * @param status    公告状态：draft 草稿，published 已发布，archived 已归档（可空，默认草稿）
 */
public record AnnouncementWriteRequest(
        String title,
        String content,
        String scopeType,
        Long deptId,
        String status
) {
}
