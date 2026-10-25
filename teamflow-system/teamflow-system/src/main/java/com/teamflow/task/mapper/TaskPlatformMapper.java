package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * M03 对平台底座（{@code sys_*}）的只读引用。
 *
 * <p>L0 §4.3 允许业务模块只读 {@code sys_user}/{@code sys_dept}/{@code sys_dict_*}；
 * 本 Mapper 只做用户/部门存在性校验与冗余字段解析，<b>不写任何平台表</b>，也不持有用户主数据。</p>
 */
@Mapper
public interface TaskPlatformMapper {

    /** 用户是否存在且启用（指派前校验）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1")
    int countActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM sys_user WHERE username = #{username} LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{userId} LIMIT 1")
    String selectUsernameById(@Param("userId") Long userId);

    @Select("SELECT nickname FROM sys_user WHERE id = #{userId} LIMIT 1")
    String selectNicknameById(@Param("userId") Long userId);

    @Select("SELECT dept_id FROM sys_user WHERE id = #{userId} LIMIT 1")
    Long selectDeptIdByUserId(@Param("userId") Long userId);
}
