package com.teamflow.personal.domain.dto;

/**
 * 收件箱处理请求（{@code POST /api/personal/todos/{id}/handle}）。
 *
 * <p>{@code action} 取值 {@code complete}（完成）或 {@code ignore}（忽略）；
 * 是否允许关闭由原模块的 {@code TodoBizHandler} 决定（R3）。</p>
 */
public class TodoHandleRequest {

    private String action;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
