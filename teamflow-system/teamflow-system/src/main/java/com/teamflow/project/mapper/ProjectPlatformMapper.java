package com.teamflow.project.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * M14 对平台底座（M00）的只读引用访问层。
 *
 * <p>只做负责人/成员的用户存在性校验与展示信息读取，**不写平台表**；用户与部门维护归 M00。</p>
 */
@Mapper
public interface ProjectPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND deleted = 0 AND status = 1")
    long countActiveUser(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM sys_dept WHERE id = #{id} AND deleted = 0")
    long countDept(@Param("id") Long id);

    @Select("""
            SELECT u.id,
                   u.username,
                   u.nickname,
                   u.dept_id AS deptId,
                   d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.deleted = 0 AND u.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectUserBrief(@Param("id") Long id);

    @Select("SELECT id FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);
}
