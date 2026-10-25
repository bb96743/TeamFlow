package com.teamflow.perf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M05 对平台底座（M00）的只读引用访问层。
 *
 * <p>L0 §4.3 明确允许业务模块只读引用 {@code sys_user} / {@code sys_dept} / {@code sys_dict_*}；
 * 本类只做用户解析（操作人、被评人、上级评分有效性校验），不写平台表。</p>
 */
@Mapper
public interface PerfPlatformMapper {

    /** 启用中的用户清单（归集预览未指定员工时的默认口径）。 */
    @Select("""
            SELECT id,
                   username,
                   nickname,
                   dept_id AS deptId
            FROM sys_user
            WHERE deleted = 0 AND status = 1
            ORDER BY id
            """)
    List<Map<String, Object>> selectActiveUsers();

    @Select("""
            SELECT id,
                   username,
                   nickname,
                   dept_id AS deptId
            FROM sys_user
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectUserById(@Param("id") Long id);

    @Select("SELECT id FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    Long selectUserIdByUsername(@Param("username") String username);

    @Select("SELECT username FROM sys_user WHERE id = #{id} AND deleted = 0 LIMIT 1")
    String selectUsernameById(@Param("id") Long id);

    @Select("SELECT nickname FROM sys_user WHERE id = #{id} AND deleted = 0 LIMIT 1")
    String selectNicknameById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND deleted = 0 AND status = 1")
    long countActiveUser(@Param("id") Long id);
}
