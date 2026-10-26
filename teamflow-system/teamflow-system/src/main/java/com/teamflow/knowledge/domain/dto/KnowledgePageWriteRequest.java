package com.teamflow.knowledge.domain.dto;

/**
 * 知识页面新增/修改请求。
 *
 * <p>正文统一 Markdown（R4），保存即生成版本快照（R1）；pageType 为 sop / decision 即
 * 用于「流程说明库」「决策记录」，不另建表（页面 + 标签表达）。</p>
 */
public class KnowledgePageWriteRequest {

    private Long spaceId;
    private Long nodeId;
    private String pageTitle;
    /** wiki / sop / decision / minutes / retro */
    private String pageType;
    /** 逗号分隔标签 */
    private String tags;
    private String summary;
    private String content;
    private String contentFormat;
    /** inherit 继承上级 / internal 已登录可读 / restricted 仅授权可读 */
    private String visibility;
    /** draft / published / archived */
    private String status;
    private Integer sort;
    private String remark;
    /** 本次变更摘要（写入版本快照） */
    private String changeSummary;

    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }
    public String getPageType() { return pageType; }
    public void setPageType(String pageType) { this.pageType = pageType; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentFormat() { return contentFormat; }
    public void setContentFormat(String contentFormat) { this.contentFormat = contentFormat; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
}
