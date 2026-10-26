package com.teamflow.knowledge.domain.dto;

/**
 * 页面版本回滚请求：把历史版本内容恢复为当前正文，并生成一条新版本快照（历史不覆盖）。
 */
public class KnowledgeVersionRollbackRequest {

    private String changeSummary;

    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
}
