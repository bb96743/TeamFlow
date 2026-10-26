package com.teamflow.recruit.domain.dto;

import java.util.List;

/**
 * 面试结构化评价提交请求（2.1.3 / R4）：一次提交多个维度，每个维度都要评分与评语。
 */
public class RecruitFeedbackWriteRequest {

    private List<RecruitFeedbackDimension> dimensions;

    public List<RecruitFeedbackDimension> getDimensions() { return dimensions; }
    public void setDimensions(List<RecruitFeedbackDimension> dimensions) { this.dimensions = dimensions; }
}
