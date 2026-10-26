package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M11 对平台主数据的只读引用：员工目录（姓名槽位解析）与用户简报。
 * 只读 sys_user，不写任何平台表。
 */
@Mapper
public interface AiPlatformMapper {

    @Select("""
            SELECT id AS userId, username, nickname, dept_id AS deptId
            FROM sys_user
            WHERE deleted = 0 AND status = 1
            ORDER BY id
            """)
    List<Map<String, Object>> selectUserDirectory();

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.dept_id AS deptId, d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.id = #{userId} AND u.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectUserById(@Param("userId") Long userId);
}
