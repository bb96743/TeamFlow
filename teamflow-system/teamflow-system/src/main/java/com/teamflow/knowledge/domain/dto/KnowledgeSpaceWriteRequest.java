package com.teamflow.knowledge.domain.dto;

/**
 * 知识空间新增/修改请求。
 */
public class KnowledgeSpaceWriteRequest {

    private String spaceName;
    private String spaceCode;
    private String spaceDesc;
    /** internal 已登录可读 / restricted 仅授权可读 */
    private String visibility;
    private String status;
    private Integer sort;
    private String remark;

    public String getSpaceName() { return spaceName; }
    public void setSpaceName(String spaceName) { this.spaceName = spaceName; }
    public String getSpaceCode() { return spaceCode; }
    public void setSpaceCode(String spaceCode) { this.spaceCode = spaceCode; }
    public String getSpaceDesc() { return spaceDesc; }
    public void setSpaceDesc(String spaceDesc) { this.spaceDesc = spaceDesc; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
