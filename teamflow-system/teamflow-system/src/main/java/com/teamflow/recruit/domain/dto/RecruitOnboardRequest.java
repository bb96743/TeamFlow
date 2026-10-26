package com.teamflow.recruit.domain.dto;

import java.util.List;

/**
 * 入职转用户请求（2.1.4 / R7）：**不建用户表**，由服务端调用 M00 的用户创建能力写入 {@code sys_user}。
 *
 * <p>所有字段可空：{@code username} 缺省时按候选人编码生成；{@code deptId} 缺省取职位归属部门；
 * 入职幂等——同一候选人已有 {@code hired_user_id} 时直接返回该用户，不重复创建。</p>
 */
public class RecruitOnboardRequest {

    private String username;
    private Long deptId;
    private List<Long> roleIds;
    private Long postId;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public List<Long> getRoleIds() { return roleIds; }
    public void setRoleIds(List<Long> roleIds) { this.roleIds = roleIds; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
}
