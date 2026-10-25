package com.teamflow.team.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M04 对平台底座（{@code sys_*}）的只读引用：用户/部门存在性校验与展示翻译。
 *
 * <p>L0 §4.3 允许业务模块只读 {@code sys_user}/{@code sys_dept}：本 Mapper 只做引用校验与"读时翻译"，
 * **不写任何平台表**，也**不在 team_member 缓存昵称/工号/部门名**（D-2026-10-26-08 结论三）。</p>
 */
@Mapper
public interface TeamPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1")
    int countActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM sys_user WHERE username = #{username} LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{userId} LIMIT 1")
    String selectUsernameById(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM sys_dept WHERE id = #{deptId}")
    int countDept(@Param("deptId") Long deptId);

    @Select("SELECT dept_name FROM sys_dept WHERE id = #{deptId} LIMIT 1")
    String selectDeptName(@Param("deptId") Long deptId);

    /** 成员/负责人展示翻译：按 ID 集合批量取用户与部门名（只读，不落库）。 */
    @Select("""
            <script>
            SELECT u.id AS userId,
                   u.username,
                   u.nickname,
                   u.dept_id AS deptId,
                   d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id IN
            <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                #{userId}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectUserBriefs(@Param("userIds") List<Long> userIds);

    /** 单用户展示翻译。 */
    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    Map<String, Object> selectUserBrief(@Param("userId") Long userId);
}
