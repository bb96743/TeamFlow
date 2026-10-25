package com.teamflow.personal.domain.dto;

/**
 * 开始专注会话请求（{@code POST /api/personal/focus-sessions}）。
 *
 * <p>本期只做后端留痕；免打扰与前端计时交互不在范围。</p>
 */
public class FocusSessionStartRequest {

    private Long taskId;
    private String remark;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
