package com.teamflow.recruit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * M08 对平台底座（M00）的只读引用访问层。
 *
 * <p>L0 §4.3 允许业务模块只读引用 {@code sys_user}/{@code sys_dept}；本类只做「用户存在性校验」
 * 与「用户展示信息读取」，**不写平台表**（入职建用户走 M00 的 Service，见 {@code RecruitOfferService}）。</p>
 */
@Mapper
public interface RecruitPlatformMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND deleted = 0 AND status = 1")
    long countActiveUser(@Param("id") Long id);

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

    @Select("SELECT COUNT(*) FROM sys_dept WHERE id = #{id} AND deleted = 0")
    long countDept(@Param("id") Long id);
}
