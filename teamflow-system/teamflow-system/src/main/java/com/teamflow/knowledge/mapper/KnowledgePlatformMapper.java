package com.teamflow.knowledge.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * M07 对平台底座用户数据的只读引用（存在性校验与展示翻译）。
 *
 * <p>L0 §4.3：业务模块只读平台用户数据、不复制昵称/部门名；本 Mapper 只读，不写平台表。</p>
 */
@Mapper
public interface KnowledgePlatformMapper {

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.username = #{username} AND u.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectUserByUsername(@Param("username") String username);

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id = #{userId} AND u.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectUserBrief(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1 AND deleted = 0")
    long countActiveUser(@Param("userId") Long userId);
}
