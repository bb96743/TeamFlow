package com.teamflow.personal.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * M10 对平台底座（{@code sys_*}）的只读引用：把登录账号解析为用户 ID。
 *
 * <p>L0 §4.3 允许业务模块只读 {@code sys_user}/{@code sys_dept}/{@code sys_dict_*}；
 * 本 Mapper 只做账号 → ID 解析与用户存在性校验，不写任何平台表。</p>
 */
@Mapper
public interface PersonalPlatformMapper {

    @Select("SELECT id FROM sys_user WHERE username = #{username} LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{userId} LIMIT 1")
    String selectUsernameById(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1")
    int countActiveUser(@Param("userId") Long userId);
}
