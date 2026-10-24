package com.teamflow.okr.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * M02 对平台表（{@code sys_user} / {@code sys_dept}）的只读引用。
 *
 * <p>只读、不复制、不持有平台数据（L0 §4.3）。</p>
 */
@Mapper
public interface OkrPlatformMapper {

    @Select("""
            SELECT COUNT(*)
            FROM sys_user
            WHERE id = #{userId} AND deleted = 0 AND status = 1
            """)
    long countActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM sys_dept
            WHERE id = #{deptId} AND deleted = 0
            """)
    long countDept(@Param("deptId") Long deptId);

    @Select("""
            SELECT id
            FROM sys_user
            WHERE username = #{username} AND deleted = 0
            LIMIT 1
            """)
    Long selectUserIdByUsername(@Param("username") String username);
}
