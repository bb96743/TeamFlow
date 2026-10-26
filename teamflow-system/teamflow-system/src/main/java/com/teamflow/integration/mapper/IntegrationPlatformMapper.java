package com.teamflow.integration.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * M12 对平台底座（M00）的只读引用：API Key 绑定用户的校验与展示。
 *
 * <p>只读用户表与部门表，不写任何平台数据。</p>
 */
@Mapper
public interface IntegrationPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND deleted = 0 AND status = 1")
    long countActiveUser(@Param("id") Long id);

    @Select("SELECT id, username, nickname, status FROM sys_user WHERE id = #{id} AND deleted = 0 LIMIT 1")
    Map<String, Object> selectUserBrief(@Param("id") Long id);

    @Select("SELECT id FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{id} AND deleted = 0 LIMIT 1")
    String selectUsernameById(@Param("id") Long id);
}
