package com.teamflow.personal.mapper;

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
 * M10 专注会话表（{@code focus_session}）访问层：番茄钟后端留痕。
 *
 * <p>本期只做「开始 / 结束 / 列表 / 统计」，免打扰与前端计时交互不在范围（task-13 明确不做）。</p>
 */
@Mapper
public interface FocusSessionMapper {

    @Insert("""
            INSERT INTO focus_session (user_id, task_id, start_time, status, remark, create_by, update_by)
            VALUES (#{row.userId}, #{row.taskId}, #{row.startTime}, 'running', #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertSession(
            @Param("row") Map<String, Object> row,
            @Param("operator") String operator
    );

    @Select("""
            SELECT id, user_id AS userId, task_id AS taskId, start_time AS startTime, end_time AS endTime,
                   duration_minutes AS durationMinutes, interrupt_count AS interruptCount, status, remark
            FROM focus_session
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectById(@Param("id") Long id);

    @Update("""
            UPDATE focus_session
            SET end_time = #{endTime},
                duration_minutes = #{durationMinutes},
                interrupt_count = #{interruptCount},
                status = #{status},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int finishSession(
            @Param("id") Long id,
            @Param("endTime") LocalDateTime endTime,
            @Param("durationMinutes") int durationMinutes,
            @Param("interruptCount") int interruptCount,
            @Param("status") String status,
            @Param("operator") String operator
    );

    @Select("""
            SELECT id, task_id AS taskId,
                   DATE_FORMAT(start_time, '%Y-%m-%d %H:%i') AS startTime,
                   DATE_FORMAT(end_time, '%Y-%m-%d %H:%i') AS endTime,
                   duration_minutes AS durationMinutes, interrupt_count AS interruptCount, status
            FROM focus_session
            WHERE user_id = #{userId} AND deleted = 0
            ORDER BY start_time DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Map<String, Object>> selectSessions(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("SELECT COUNT(*) FROM focus_session WHERE user_id = #{userId} AND deleted = 0")
    long countSessions(@Param("userId") Long userId);

    /** 效能画像：专注会话数与总分钟数（实时聚合）。 */
    @Select("""
            SELECT COUNT(*) AS sessionCount,
                   COALESCE(SUM(duration_minutes), 0) AS totalMinutes
            FROM focus_session
            WHERE user_id = #{userId} AND deleted = 0
            """)
    Map<String, Object> selectStatByUser(@Param("userId") Long userId);
}
