package com.teamflow.meeting.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M06 对平台底座用户/部门数据的只读引用（存在性校验与展示翻译）。
 *
 * <p>L0 §4.3：业务模块只读平台用户/部门数据，不复制昵称/部门名；本 Mapper 只读，不写平台表。</p>
 */
@Mapper
public interface MeetingPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1 AND deleted = 0")
    long countActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

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
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    Map<String, Object> selectUserBrief(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.deleted = 0 AND u.id IN
            <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                #{userId}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectUserBriefs(@Param("userIds") List<Long> userIds);

    @Select("SELECT dept_id FROM sys_user WHERE id = #{userId} AND deleted = 0 LIMIT 1")
    Long selectDeptId(@Param("userId") Long userId);
}
