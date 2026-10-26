package com.teamflow.knowledge.domain.dto;

/**
 * 文档中心附件引用请求：只登记平台已上传文件的 ID，不复制文件本体。
 */
public class KnowledgeFileWriteRequest {

    private Long spaceId;
    private Long pageId;
    private Long fileId;
    private String fileTitle;
    /** attachment / sop_doc / image / other */
    private String category;
    private Integer sort;
    private String remark;

    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getPageId() { return pageId; }
    public void setPageId(Long pageId) { this.pageId = pageId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getFileTitle() { return fileTitle; }
    public void setFileTitle(String fileTitle) { this.fileTitle = fileTitle; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
