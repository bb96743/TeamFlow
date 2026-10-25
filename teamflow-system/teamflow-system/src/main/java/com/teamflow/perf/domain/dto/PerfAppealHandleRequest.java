package com.teamflow.perf.domain.dto;

import java.math.BigDecimal;

/**
 * 申诉处理请求。
 *
 * <p>{@code approved=true} 时可携带 {@code afterScore}，通过后由系统定向更新结果总分并留痕；
 * 驳回则不改变结果。</p>
 */
public class PerfAppealHandleRequest {

    private Boolean approved;
    private String comment;
    private BigDecimal afterScore;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public BigDecimal getAfterScore() { return afterScore; }
    public void setAfterScore(BigDecimal afterScore) { this.afterScore = afterScore; }
}
