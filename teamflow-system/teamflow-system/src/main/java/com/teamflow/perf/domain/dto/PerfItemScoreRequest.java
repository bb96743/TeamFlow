package com.teamflow.perf.domain.dto;

import java.math.BigDecimal;

/**
 * 单个指标项的评分请求。
 */
public class PerfItemScoreRequest {

    private Long itemId;
    private BigDecimal score;
    private String comment;

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
