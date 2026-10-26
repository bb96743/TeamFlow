package com.teamflow.project.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M14 项目成员表（{@code proj_member}）访问层。
 *
 * <p>移出成员只写 {@code leave_time} 不删行（历史可追溯）；再次加入时复活同一行（唯一键
 * {@code (project_id, user_id)}）。成员展示信息读时关联平台用户/部门，不在本表缓存（L0 §4.3）。</p>
 */
@Mapper
public interface ProjectMemberMapper {

    String COLUMNS = """
            SELECT m.id,
                   m.project_id AS projectId,
                   m.user_id AS userId,
                   u.username,
                   u.nickname,
                   d.dept_name AS deptName,
                   m.member_role AS memberRole,
                   DATE_FORMAT(m.join_time, '%Y-%m-%d %H:%i') AS joinTime,
                   DATE_FORMAT(m.leave_time, '%Y-%m-%d %H:%i') AS leaveTime,
                   m.remark
            FROM proj_member m
            LEFT JOIN sys_user u ON u.id = m.user_id
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            """;

    /** 在项目内的成员（leave_time 为空）。 */
    @Select(COLUMNS + """
             WHERE m.project_id = #{projectId} AND m.deleted = 0 AND m.leave_time IS NULL
            ORDER BY (CASE m.member_role WHEN 'owner' THEN 0 WHEN 'member' THEN 1 ELSE 2 END), m.id
            """)
    List<Map<String, Object>> selectByProject(@Param("projectId") Long projectId);

    /** 查任意状态的成员行（含已移出，用于再次加入时复活同一行）。 */
    @Select(COLUMNS + " WHERE m.project_id = #{projectId} AND m.user_id = #{userId} LIMIT 1")
    Map<String, Object> selectAnyByPair(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM proj_member WHERE project_id = #{projectId} AND deleted = 0 AND leave_time IS NULL")
    long countActive(@Param("projectId") Long projectId);

    @Insert("""
            INSERT INTO proj_member (project_id, user_id, member_role, join_time, remark, create_by, update_by)
            VALUES (#{row.projectId}, #{row.userId}, #{row.memberRole}, NOW(), #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMember(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    /** 再次加入：复活既有行（清 leave_time、重置加入时间与角色）。 */
    @Update("""
            UPDATE proj_member
            SET member_role = #{memberRole},
                join_time = NOW(),
                leave_time = NULL,
                remark = #{remark},
                deleted = 0,
                update_by = #{operator}
            WHERE id = #{id}
            """)
    int reviveMember(
            @Param("id") Long id,
            @Param("memberRole") String memberRole,
            @Param("remark") String remark,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE proj_member
            SET member_role = #{memberRole}, update_by = #{operator}
            WHERE id = #{id}
            """)
    int updateRole(@Param("id") Long id, @Param("memberRole") String memberRole, @Param("operator") String operator);

    /** 移出：只写 leave_time，不删行。 */
    @Update("""
            UPDATE proj_member
            SET leave_time = NOW(), update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int removeMember(@Param("id") Long id, @Param("operator") String operator);

    @Update("UPDATE proj_member SET deleted = 1, update_by = #{operator} WHERE project_id = #{projectId} AND deleted = 0")
    int softDeleteByProject(@Param("projectId") Long projectId, @Param("operator") String operator);
}
