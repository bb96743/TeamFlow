package com.teamflow.insight.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M13 对平台底座（M00）的只读引用访问层。
 *
 * <p>L0 §4.3 允许业务模块只读引用 {@code sys_user} / {@code sys_dept} / {@code sys_dict_*}；
 * 本类只做两类事：① 按当前用户数据范围取可见成员（R3 数据范围强制生效的落地）；
 * ② 读取成员展示信息（昵称/部门名，读时翻译，不落库）。**不写平台表，也不读任何其他业务表**。</p>
 */
@Mapper
public interface InsightPlatformMapper {

    /**
     * 当前数据范围内可见的启用用户（统计口径的成员全集）。
     *
     * <p>数据范围语义与 M02/M03 列表一致：ALL 全量；否则「本人 + 本部门（按角色范围解析的部门集合）」。</p>
     */
    @Select("""
            <script>
            SELECT u.id,
                   u.username,
                   u.nickname,
                   u.dept_id AS deptId,
                   d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.deleted = 0 AND u.status = 1
              AND (
                  #{scope.allScope} = TRUE
                  OR u.id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR u.dept_id IN
                  <foreach collection="scope.deptIds" item="deptId" open="(" separator="," close=")">
                      #{deptId}
                  </foreach>
                  </if>
              )
            ORDER BY u.dept_id, u.id
            </script>
            """)
    List<Map<String, Object>> selectVisibleUsers(@Param("scope") DataScopeCriteria scope);

    /** 按 ID 取用户展示信息（昵称/部门名，读时翻译，不缓存落地）。 */
    @Select("""
            <script>
            SELECT u.id,
                   u.username,
                   u.nickname,
                   u.dept_id AS deptId,
                   d.dept_name AS deptName
            FROM sys_user u
            LEFT JOIN sys_dept d ON d.id = u.dept_id
            WHERE u.deleted = 0
              AND u.id IN
              <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                  #{userId}
              </foreach>
            ORDER BY u.id
            </script>
            """)
    List<Map<String, Object>> selectUserBriefs(@Param("userIds") List<Long> userIds);

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
}
