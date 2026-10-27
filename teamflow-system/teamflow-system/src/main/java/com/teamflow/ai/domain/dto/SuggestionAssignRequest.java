package com.teamflow.ai.domain.dto;

/**
 * AI 任务分配建议请求（1.3.5）。
 *
 * @param taskId 目标任务 ID（建议基于该任务与候选人的当前负载）
 */
public record SuggestionAssignRequest(Long taskId) {
}
