package com.teamflow.recruit.domain.vo;

/**
 * 候选人入库响应。
 *
 * <p>R1 查重：同一（归一化手机号 + 归一化邮箱）再次入库时**返回已有记录**而不是重复创建，
 * {@code duplicated=true} 便于调用方提示"该候选人已在库中"。</p>
 *
 * @param id         候选人 ID（新建或已存在的记录）
 * @param duplicated 是否命中查重（true=已存在，未新建）
 */
public record RecruitCandidateCreateResponse(Long id, boolean duplicated) {
}
