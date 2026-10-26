package com.teamflow.collab.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M09 对平台底座的只读引用（用户/部门）：存在性校验与展示翻译。
 *
 * <p>L0 §4.3 允许业务模块只读平台用户/部门数据；本 Mapper 只读、不写平台表，
 * 也不在本模块缓存昵称/部门名（展示时实时关联）。</p>
 */
@Mapper
public interface CollabPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1")
    int countActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM sys_user WHERE username = #{username} LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{userId} LIMIT 1")
    String selectUsernameById(@Param("userId") Long userId);

    @Select("SELECT dept_id FROM sys_user WHERE id = #{userId} LIMIT 1")
    Long selectDeptIdByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM sys_dept WHERE id = #{deptId}")
    int countDept(@Param("deptId") Long deptId);

    @Select("SELECT dept_name FROM sys_dept WHERE id = #{deptId} LIMIT 1")
    String selectDeptName(@Param("deptId") Long deptId);

    /** 按账号批量解析用户（用于评论/消息正文里的 @账号 提及）。 */
    @Select("""
            <script>
            SELECT id AS userId, username, nickname
            FROM sys_user
            WHERE status = 1 AND username IN
            <foreach collection="usernames" item="username" open="(" separator="," close=")">
                #{username}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectUsersByUsernames(@Param("usernames") List<String> usernames);

    /** 按 ID 集合批量取展示资料（昵称/账号/部门名，读取时翻译、不落库）。 */
    @Select("""
            <script>
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id IN
            <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                #{userId}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectUserBriefs(@Param("userIds") List<Long> userIds);

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    Map<String, Object> selectUserBrief(@Param("userId") Long userId);
}
