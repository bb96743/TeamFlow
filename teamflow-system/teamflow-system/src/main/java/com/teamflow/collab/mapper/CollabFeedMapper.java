package com.teamflow.collab.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M09 动态流投影表访问层。
 *
 * <p>动态流**只由领域事件投影生成**（D-2026-10-26-11 结论五）：本表没有对外的写接口，
 * 只有事件订阅端调用 {@link #insertFeed(Map, String)}；业务模块不得直写。</p>
 */
@Mapper
public interface CollabFeedMapper {

    @Insert("""
            INSERT INTO collab_feed (event_id, event_type, actor_id, actor_name, action, biz_type, biz_id,
                                     summary, occurred_at, create_by, update_by)
            VALUES (#{row.eventId}, #{row.eventType}, #{row.actorId}, #{row.actorName}, #{row.action},
                    #{row.bizType}, #{row.bizId}, #{row.summary}, #{row.occurredAt}, 'event', 'event')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertFeed(@Param("row") Map<String, Object> row);

    @Select("""
            <script>
            SELECT id,
                   event_id AS eventId,
                   event_type AS eventType,
                   actor_id AS actorId,
                   actor_name AS actorName,
                   action,
                   biz_type AS bizType,
                   biz_id AS bizId,
                   summary,
                   DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s') AS occurredAt
            FROM collab_feed
            WHERE deleted = 0
              <if test="actorId != null"> AND actor_id = #{actorId} </if>
              <if test="action != null and action != ''"> AND action = #{action} </if>
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectFeeds(
            @Param("actorId") Long actorId,
            @Param("action") String action,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM collab_feed
            WHERE deleted = 0
              <if test="actorId != null"> AND actor_id = #{actorId} </if>
              <if test="action != null and action != ''"> AND action = #{action} </if>
            </script>
            """)
    long countFeeds(@Param("actorId") Long actorId, @Param("action") String action);

    @Select("SELECT COUNT(*) FROM collab_feed WHERE event_id = #{eventId}")
    int countByEventId(@Param("eventId") String eventId);
}
