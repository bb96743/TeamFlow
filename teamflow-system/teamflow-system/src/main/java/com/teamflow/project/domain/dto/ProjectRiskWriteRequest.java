package com.teamflow.project.domain.dto;

/**
 * 项目风险登记 / 编辑请求（需求 2.5.3）。
 *
 * @param riskTitle   风险标题（必填）
 * @param riskDesc    风险描述（可空）
 * @param riskLevel   风险等级：low/medium/high/critical（可空，默认 medium）
 * @param riskType    风险类别：schedule/cost/quality/resource/other（可空，默认 schedule）
 * @param ownerId     责任人用户 ID（可空）
 * @param responsePlan 应对措施（可空）
 * @param dueDate     计划应对完成日期 yyyy-MM-dd（可空）
 * @param remark      备注（可空）
 */
public record ProjectRiskWriteRequest(
        String riskTitle,
        String riskDesc,
        String riskLevel,
        String riskType,
        Long ownerId,
        String responsePlan,
        String dueDate,
        String remark
) {
}
