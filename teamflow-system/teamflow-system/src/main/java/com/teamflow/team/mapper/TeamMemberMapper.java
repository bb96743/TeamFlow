package com.teamflow.team.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M04 团队成员关系表（{@code team_member}）访问层。
 *
 * <p><b>R1/R3/R5 落地</b>：只存 {@code user_id} + 团队内属性；移出只写 {@code leave_time}（不删行）；
 * 唯一键 {@code (team_id, user_id)} 保证同团队内不重复，跨团队可复用（一人可属多团队）；
 * 「我参与的团队」与在册成员判定统一为 {@code leave_time IS NULL}。</p>
 */
@Mapper
public interface TeamMemberMapper {

    String COLUMNS = """
            SELECT m.id,
                   m.team_id AS teamId,
                   m.user_id AS userId,
                   m.team_role AS teamRole,
                   DATE_FORMAT(m.join_time, '%Y-%m-%d %H:%i') AS joinTime,
                   DATE_FORMAT(m.leave_time, '%Y-%m-%d %H:%i') AS leaveTime,
                   m.remark
            FROM team_member m
            WHERE m.team_id = #{teamId} AND m.deleted = 0
            """;

    @Select("""
            <script>
            """ + COLUMNS + """
              <if test="includeLeft == false"> AND m.leave_time IS NULL </if>
            ORDER BY (m.leave_time IS NOT NULL), m.id
            </script>
            """)
    List<Map<String, Object>> selectMembers(@Param("teamId") Long teamId, @Param("includeLeft") boolean includeLeft);

    @Select("""
            <script>
            SELECT COUNT(*) FROM team_member m
            WHERE m.team_id = #{teamId} AND m.deleted = 0
              <if test="includeLeft == false"> AND m.leave_time IS NULL </if>
            </script>
            """)
    long countMembers(@Param("teamId") Long teamId, @Param("includeLeft") boolean includeLeft);

    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, team_role AS teamRole,
                   join_time AS joinTime, leave_time AS leaveTime, remark
            FROM team_member
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectMemberById(@Param("id") Long id);

    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, team_role AS teamRole,
                   join_time AS joinTime, leave_time AS leaveTime
            FROM team_member
            WHERE team_id = #{teamId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /** 团队当前在册 OWNER（用于解散/负责人校验与展示）。 */
    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, team_role AS teamRole, leave_time AS leaveTime
            FROM team_member
            WHERE team_id = #{teamId} AND team_role = 'OWNER' AND leave_time IS NULL AND deleted = 0
            ORDER BY id
            LIMIT 1
            """)
    Map<String, Object> selectActiveOwner(@Param("teamId") Long teamId);

    /** 在册成员的用户 ID 集合（成员负载批量取数用）。 */
    @Select("""
            SELECT user_id FROM team_member
            WHERE team_id = #{teamId} AND leave_time IS NULL AND deleted = 0
            ORDER BY id
            """)
    List<Long> selectActiveMemberUserIds(@Param("teamId") Long teamId);

    @Insert("""
            INSERT INTO team_member (team_id, user_id, team_role, join_time, leave_time, remark,
                                     create_by, update_by)
            VALUES (#{row.teamId}, #{row.userId}, #{row.teamRole}, NOW(), NULL, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMember(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    /** 重新加入：重置 join_time、清空 leave_time、更新角色与备注。 */
    @Update("""
            UPDATE team_member
            SET team_role = #{teamRole},
                join_time = NOW(),
                leave_time = NULL,
                remark = #{remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int reviveMember(
            @Param("id") Long id,
            @Param("teamRole") String teamRole,
            @Param("remark") String remark,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE team_member
            SET team_role = #{teamRole}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMemberRole(
            @Param("id") Long id,
            @Param("teamRole") String teamRole,
            @Param("operator") String operator
    );

    /** 移出成员：只写 leave_time，不删行（R3）。 */
    @Update("""
            UPDATE team_member
            SET leave_time = NOW(), update_by = #{operator}
            WHERE id = #{id} AND leave_time IS NULL AND deleted = 0
            """)
    int leaveMember(@Param("id") Long id, @Param("operator") String operator);

    /** 负责人移交：把团队内其他在册 OWNER 降为 MEMBER（团队只有一个 OWNER）。 */
    @Update("""
            UPDATE team_member
            SET team_role = 'MEMBER', update_by = #{operator}
            WHERE team_id = #{teamId} AND team_role = 'OWNER'
              AND leave_time IS NULL AND deleted = 0
              AND (#{exceptMemberId} IS NULL OR id <> #{exceptMemberId})
            """)
    int demoteOtherOwners(
            @Param("teamId") Long teamId,
            @Param("exceptMemberId") Long exceptMemberId,
            @Param("operator") String operator
    );

    @Select("""
            SELECT COUNT(*) FROM team_member
            WHERE user_id = #{userId} AND leave_time IS NULL AND deleted = 0
            """)
    int countActiveTeamsOfUser(@Param("userId") Long userId);
}
