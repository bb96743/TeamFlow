package com.teamflow.project.domain.dto;

/**
 * 项目-任务直连关联请求（M2-1 遗留）。
 *
 * <p>关联由本模块持有（project_id + task_id + 关联类型），但任务存在性经任务模块 Service 校验；
 * 任务侧不新增任何字段（D-05「任务↔KR 权威关联在任务模块」不变，项目关联是另一维度）。</p>
 *
 * @param taskId   任务 ID（只存引用）
 * @param linkType 关联类型：plan 计划承接，execution 执行任务（可空，默认 execution）
 * @param remark   关联说明（可空）
 */
public record ProjectTaskLinkRequest(Long taskId, String linkType, String remark) {
}
