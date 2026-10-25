package com.teamflow.personal.domain.dto;

/**
 * 复盘新增 / 编辑请求（{@code /api/personal/reviews}）。
 *
 * <p>复盘默认私有（{@code visibility='private'}）存本模块；公开归档 M07 属后续阶段（本期只预留字段，不写入）。</p>
 */
public class ReviewLogWriteRequest {

    private String reviewType;
    private String reviewTitle;
    private String periodStart;
    private String periodEnd;
    private String content;
    private String tags;

    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getReviewTitle() { return reviewTitle; }
    public void setReviewTitle(String reviewTitle) { this.reviewTitle = reviewTitle; }
    public String getPeriodStart() { return periodStart; }
    public void setPeriodStart(String periodStart) { this.periodStart = periodStart; }
    public String getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(String periodEnd) { this.periodEnd = periodEnd; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
