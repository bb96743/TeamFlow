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

    /** G-3：事件消费时把事件里的 operatorId 还原为账号名，用于进度留痕（只读引用 sys_user）。 */
    @Select("""
            SELECT username
            FROM sys_user
            WHERE id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    String selectUsernameById(@Param("userId") Long userId);
}
