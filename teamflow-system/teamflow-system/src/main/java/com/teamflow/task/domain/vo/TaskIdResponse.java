package com.teamflow.task.domain.vo;

/**
 * 新增类接口的统一 ID 响应（与 M02 的 {@code OkrIdResponse} 同形，前端无需区分模块）。
 *
 * @param id 新增记录主键
 */
public record TaskIdResponse(Long id) {
}
