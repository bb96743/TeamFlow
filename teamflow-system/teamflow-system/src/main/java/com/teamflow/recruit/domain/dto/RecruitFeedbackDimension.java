package com.teamflow.recruit.domain.dto;

/**
 * 单个面试评价维度（R4：每维度独立评分 + 评语，禁止只填总分）。
 */
public class RecruitFeedbackDimension {

    private String dimensionCode;
    private String dimensionName;
    private java.math.BigDecimal score;
    private String comment;

    public String getDimensionCode() { return dimensionCode; }
    public void setDimensionCode(String dimensionCode) { this.dimensionCode = dimensionCode; }
    public String getDimensionName() { return dimensionName; }
    public void setDimensionName(String dimensionName) { this.dimensionName = dimensionName; }
    public java.math.BigDecimal getScore() { return score; }
    public void setScore(java.math.BigDecimal score) { this.score = score; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
