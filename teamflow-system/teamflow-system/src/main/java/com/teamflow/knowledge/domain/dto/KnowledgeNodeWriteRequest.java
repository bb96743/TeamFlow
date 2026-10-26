package com.teamflow.knowledge.domain.dto;

/**
 * 知识目录新增/修改请求（空间内树形）。parentId 为 0 表示挂在空间根。
 */
public class KnowledgeNodeWriteRequest {

    private Long spaceId;
    private Long parentId;
    private String nodeName;
    private String nodeDesc;
    /** inherit 继承上级 / internal 已登录可读 / restricted 仅授权可读 */
    private String visibility;
    private Integer sort;
    private String remark;

    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getNodeDesc() { return nodeDesc; }
    public void setNodeDesc(String nodeDesc) { this.nodeDesc = nodeDesc; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
