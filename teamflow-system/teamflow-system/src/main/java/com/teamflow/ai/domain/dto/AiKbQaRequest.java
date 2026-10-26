package com.teamflow.ai.domain.dto;

/**
 * 知识库问答请求（{@code POST /api/ai/kb/qa}）。
 */
public class AiKbQaRequest {

    private String question;
    private Long spaceId;
    private Integer limit;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
