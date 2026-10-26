package com.teamflow.project.domain.dto;

/**
 * 项目风险状态流转请求（2.5.3）：目标状态 + 处理意见（写入风险状态留痕）。
 *
 * @param status 目标状态：assessed / responding / closed / cancelled
 * @param note   处理意见/说明（可空，但关闭/取消建议填写，便于复盘追溯）
 */
public record ProjectRiskStatusRequest(String status, String note) {
}
