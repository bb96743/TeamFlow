package com.teamflow.project.domain.dto;

import java.util.List;

/**
 * 项目复盘归档请求（需求 2.5.4）。
 *
 * <p><b>正文只进知识模块</b>（L0 §9.1 D-2026-10-26-11 结论三）：本模块调用知识模块归档 Service，
 * 表内只保存返回的 {@code retro_page_id}，**不复制正文**。重复归档时由归档 Service 生成新版本。</p>
 *
 * @param title   复盘页面标题（可空，默认「项目复盘：项目名称」）
 * @param content 复盘正文（Markdown，必填）
 * @param tags    标签（可空）
 * @param summary 结项结论摘要（可空；仅作为知识页面的摘要字段，不落本模块表）
 */
public record ProjectRetroArchiveRequest(String title, String content, List<String> tags, String summary) {
}
