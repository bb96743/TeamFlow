package com.teamflow.team.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M04 团队表（{@code team_team}）访问层。
 *
 * <p><b>可见性（G-2 默认拒绝）</b>：团队列表/详情按「全部可见 / 本人是负责人 / 部门在数据范围内 /
 * 本人是团队在册成员（{@code leave_time IS NULL}）」过滤；写操作前的存在性校验走
 * {@link #selectTeamById(Long)}（不受数据范围限制）。</p>
 */
@Mapper
public interface TeamMapper {

    String COLUMNS = """
            SELECT t.id,
                   t.team_name AS teamName,
                   t.team_code AS teamCode,
                   t.team_desc AS teamDesc,
                   t.dept_id AS deptId,
                   d.dept_name AS deptName,
                   t.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   t.status,
                   t.sort,
                   t.remark,
                   (SELECT COUNT(*) FROM team_member m
                     WHERE m.team_id = t.id AND m.leave_time IS NULL AND m.deleted = 0) AS memberCount,
                   DATE_FORMAT(t.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(t.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM team_team t
            LEFT JOIN sys_dept d ON d.id = t.dept_id
            LEFT JOIN sys_user u ON u.id = t.owner_id
            """;

    String SCOPE_FILTER = """
              AND (
                  #{scope.allScope} = TRUE
                  OR t.owner_id = #{scope.userId}
                  OR EXISTS (
                      SELECT 1 FROM team_member sm
                      WHERE sm.team_id = t.id AND sm.user_id = #{scope.userId}
                        AND sm.leave_time IS NULL AND sm.deleted = 0
                  )
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR t.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM team_team t
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (t.team_name LIKE CONCAT('%', #{keyword}, '%')
                   OR t.team_code LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''"> AND t.status = #{status} </if>
              <if test="mine"> AND EXISTS (
                  SELECT 1 FROM team_member m
                  WHERE m.team_id = t.id AND m.user_id = #{mineUserId}
                    AND m.leave_time IS NULL AND m.deleted = 0
              ) </if>
            """ + SCOPE_FILTER + """
            </script>
            """)
    long countTeams(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("mine") boolean mine,
            @Param("mineUserId") Long mineUserId,
            @Param("scope") DataScopeCriteria scope
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE t.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (t.team_name LIKE CONCAT('%', #{keyword}, '%')
                   OR t.team_code LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''"> AND t.status = #{status} </if>
              <if test="mine"> AND EXISTS (
                  SELECT 1 FROM team_member m
                  WHERE m.team_id = t.id AND m.user_id = #{mineUserId}
                    AND m.leave_time IS NULL AND m.deleted = 0
              ) </if>
            """ + SCOPE_FILTER + """
            ORDER BY t.sort, t.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectTeams(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("mine") boolean mine,
            @Param("mineUserId") Long mineUserId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE t.deleted = 0 AND t.id = #{id}
            """ + SCOPE_FILTER + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectTeam(@Param("id") Long id, @Param("scope") DataScopeCriteria scope);

    /** 内部校验用：不受数据范围限制（资产归属校验/写操作前）。 */
    @Select("""
            """ + COLUMNS + """
            WHERE t.deleted = 0 AND t.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectTeamById(@Param("id") Long id);

    /** 团队下拉选项（供其他模块引用；只返回启用团队的最小字段）。 */
    @Select("""
            SELECT id, team_name AS teamName, team_code AS teamCode
            FROM team_team
            WHERE deleted = 0 AND status = 'active'
            ORDER BY sort, id
            """)
    List<Map<String, Object>> selectOptions();

    @Select("""
            SELECT COUNT(*) FROM team_team
            WHERE team_code = #{teamCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countByCode(@Param("teamCode") String teamCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO team_team (team_name, team_code, team_desc, dept_id, owner_id, status, sort, remark,
                                   create_by, update_by)
            VALUES (#{row.teamName}, #{row.teamCode}, #{row.teamDesc}, #{row.deptId}, #{row.ownerId},
                    #{row.status}, #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTeam(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE team_team
            SET team_name = #{row.teamName},
                team_code = #{row.teamCode},
                team_desc = #{row.teamDesc},
                dept_id = #{row.deptId},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateTeam(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE team_team
            SET owner_id = #{ownerId}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateOwner(@Param("id") Long id, @Param("ownerId") Long ownerId, @Param("operator") String operator);

    /** 解散团队：逻辑删除 + 状态标记（R4 保留成员历史与数据）。 */
    @Update("""
            UPDATE team_team
            SET deleted = 1, status = 'dissolved', update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int dissolveTeam(@Param("id") Long id, @Param("operator") String operator);
}
