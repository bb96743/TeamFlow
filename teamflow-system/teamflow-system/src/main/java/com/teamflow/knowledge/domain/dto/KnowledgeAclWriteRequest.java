package com.teamflow.knowledge.domain.dto;

/**
 * 知识访问授权请求（子级授权覆盖父级；deny 用于显式拒绝）。
 *
 * <p>本期仅支持用户级授权；角色级授权属后续扩展。</p>
 */
public class KnowledgeAclWriteRequest {

    /** space / node / page */
    private String resourceType;
    private Long resourceId;
    /** 本期固定 user */
    private String subjectType;
    private Long subjectId;
    /** read / write / manage / deny */
    private String permission;
    private String remark;

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
