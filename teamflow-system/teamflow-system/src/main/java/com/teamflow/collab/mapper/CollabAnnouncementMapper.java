package com.teamflow.collab.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M09 公告与公告已读访问层。
 *
 * <p><b>可见范围（与平台数据权限口径一致）</b>：全员公告对所有已登录用户可见；部门公告只在
 * 「数据范围为全部」或「当前用户部门落在其数据范围部门集合内」时可见。部门判断只读平台组织数据。
 * 草稿与已归档公告只对发布人/当前数据范围可见，避免未发布内容外泄。</p>
 */
@Mapper
public interface CollabAnnouncementMapper {

    String VISIBILITY = """
              AND (
                  #{scope.allScope} = TRUE
                  OR a.scope_type = 'all'
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR (a.scope_type = 'dept' AND a.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  )
                  </if>
              )
            """;

    String COLUMNS = """
            SELECT a.id,
                   a.title,
                   a.content,
                   a.scope_type AS scopeType,
                   a.dept_id AS deptId,
                   d.dept_name AS deptName,
                   a.publisher_id AS publisherId,
                   u.nickname AS publisherName,
                   DATE_FORMAT(a.publish_time, '%Y-%m-%d %H:%i') AS publishTime,
                   a.status,
                   DATE_FORMAT(a.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(r.read_time, '%Y-%m-%d %H:%i') AS readTime
            FROM msg_announcement a
            LEFT JOIN sys_dept d ON d.id = a.dept_id
            LEFT JOIN sys_user u ON u.id = a.publisher_id
            LEFT JOIN msg_announcement_read r
              ON r.announcement_id = a.id AND r.user_id = #{userId} AND r.deleted = 0
            """;

    @Insert("""
            INSERT INTO msg_announcement (title, content, scope_type, dept_id, publisher_id, publish_time,
                                          status, create_by, update_by)
            VALUES (#{row.title}, #{row.content}, #{row.scopeType}, #{row.deptId}, #{row.publisherId},
                    #{row.publishTime}, #{row.status}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertAnnouncement(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE msg_announcement
            SET title = #{row.title},
                content = #{row.content},
                scope_type = #{row.scopeType},
                dept_id = #{row.deptId},
                status = #{row.status},
                publish_time = #{row.publishTime},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateAnnouncement(@Param("id") Long id, @Param("row") Map<String, Object> row,
                           @Param("operator") String operator);

    @Update("UPDATE msg_announcement SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteAnnouncement(@Param("id") Long id, @Param("operator") String operator);

    /** 不受数据范围限制的内部读取（写操作/发布人校验用）。 */
    @Select("""
            SELECT id, title, content, scope_type AS scopeType, dept_id AS deptId, publisher_id AS publisherId,
                   publish_time AS publishTime, status
            FROM msg_announcement
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectAnnouncementById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT COUNT(*) FROM msg_announcement a
            WHERE a.deleted = 0
              <if test="status != null and status != ''"> AND a.status = #{status} </if>
              AND (a.status = 'published' OR a.publisher_id = #{userId})
            """ + VISIBILITY + """
            </script>
            """)
    long countAnnouncements(
            @Param("status") String status,
            @Param("userId") Long userId,
            @Param("scope") DataScopeCriteria scope);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE a.deleted = 0
              <if test="status != null and status != ''"> AND a.status = #{status} </if>
              AND (a.status = 'published' OR a.publisher_id = #{userId})
            """ + VISIBILITY + """
            ORDER BY COALESCE(a.publish_time, a.create_time) DESC, a.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectAnnouncements(
            @Param("status") String status,
            @Param("userId") Long userId,
            @Param("scope") DataScopeCriteria scope,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE a.deleted = 0 AND a.id = #{id}
              AND (a.status = 'published' OR a.publisher_id = #{userId})
            """ + VISIBILITY + """
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectVisibleAnnouncement(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("scope") DataScopeCriteria scope);

    @Insert("""
            INSERT INTO msg_announcement_read (announcement_id, user_id, read_time, create_by, update_by)
            VALUES (#{announcementId}, #{userId}, #{readTime}, #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE read_time = #{readTime}, deleted = 0, update_by = VALUES(update_by)
            """)
    int upsertRead(
            @Param("announcementId") Long announcementId,
            @Param("userId") Long userId,
            @Param("readTime") LocalDateTime readTime,
            @Param("operator") String operator);
}
