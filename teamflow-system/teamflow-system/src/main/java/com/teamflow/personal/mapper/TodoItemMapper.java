package com.teamflow.personal.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M10 统一待办索引表（{@code todo_item}）访问层。
 *
 * <p><b>幂等与防复活（R2/R4）</b>：唯一性由 V15 的生成列唯一索引 {@code uk_todo_item_active(active_uk)} 保证——
 * <b>只有未处理（{@code status='open' AND deleted=0}）的行参与唯一</b>；已处理/已忽略行的 {@code active_uk} 为 NULL，
 * 因此历史记录可共存、互不冲突（MySQL 唯一索引允许多个 NULL）。</p>
 * <ul>
 *   <li>重复投递**未处理**事项 → 命中唯一键，走 {@code ON DUPLICATE KEY UPDATE}，只刷新展示字段（R2 不变）；</li>
 *   <li>已处理后再投递 → 视为新的业务事件，<b>新建</b>一条 open 记录，旧记录保持冻结（R4 正确语义）；</li>
 *   <li>已处理行本身不可被修改/重开。</li>
 * </ul>
 */
@Mapper
public interface TodoItemMapper {

    /**
     * 投递（幂等 upsert）：若同一业务键已有 **未处理** 行则刷新展示字段；否则新建一条 open 行。
     *
     * <p>V15 起唯一键只约束 open 行，因此 {@code ON DUPLICATE KEY UPDATE} 只会在 open 行上触发；
     * 保留 {@code IF(status='open', ...)} 作为并发/异常路径的防御（历史行永不被改写）。</p>
     */
    @Insert("""
            INSERT INTO todo_item (biz_type, biz_id, user_id, action_type, title, action_url, due_time, priority,
                                   status, source_event_id, create_by, update_by)
            VALUES (#{row.bizType}, #{row.bizId}, #{row.userId}, #{row.actionType}, #{row.title}, #{row.actionUrl},
                    #{row.dueTime}, #{row.priority}, 'open', #{row.sourceEventId}, #{operator}, #{operator})
            ON DUPLICATE KEY UPDATE
                title = IF(status = 'open', VALUES(title), title),
                action_url = IF(status = 'open', VALUES(action_url), action_url),
                due_time = IF(status = 'open', VALUES(due_time), due_time),
                priority = IF(status = 'open', VALUES(priority), priority),
                source_event_id = IF(status = 'open', VALUES(source_event_id), source_event_id),
                update_by = IF(status = 'open', VALUES(update_by), update_by)
            """)
    int upsertItem(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, biz_type AS bizType, biz_id AS bizId, user_id AS userId, action_type AS actionType,
                   title, action_url AS actionUrl, due_time AS dueTime, priority, status,
                   handle_time AS handleTime, handle_by AS handleBy, handle_result AS handleResult,
                   source_event_id AS sourceEventId, remark,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM todo_item
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectById(@Param("id") Long id);

    /**
     * 取**当前未处理**行（投递返回值/幂等验证用）：同一业务键在 V15 后可能有 0..n 条历史行，
     * 只能按 {@code status='open'} 精确定位，不能任意 LIMIT 1。
     */
    @Select("""
            SELECT id, biz_type AS bizType, biz_id AS bizId, user_id AS userId, action_type AS actionType,
                   title, status
            FROM todo_item
            WHERE biz_type = #{bizType} AND biz_id = #{bizId} AND user_id = #{userId}
              AND action_type = #{actionType} AND status = 'open' AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Map<String, Object> selectOpenByBizKey(
            @Param("bizType") String bizType,
            @Param("bizId") Long bizId,
            @Param("userId") Long userId,
            @Param("actionType") String actionType
    );

    /** 追溯查询：按业务键取最新一行（含已处理，排障/测试用）。 */
    @Select("""
            SELECT id, biz_type AS bizType, biz_id AS bizId, user_id AS userId, action_type AS actionType,
                   title, status
            FROM todo_item
            WHERE biz_type = #{bizType} AND biz_id = #{bizId} AND user_id = #{userId}
              AND action_type = #{actionType}
            ORDER BY id DESC
            LIMIT 1
            """)
    Map<String, Object> selectByBizKey(
            @Param("bizType") String bizType,
            @Param("bizId") Long bizId,
            @Param("userId") Long userId,
            @Param("actionType") String actionType
    );

    @Select("""
            <script>
            SELECT COUNT(*) FROM todo_item
            WHERE user_id = #{userId} AND deleted = 0
              <if test="bizType != null and bizType != ''"> AND biz_type = #{bizType} </if>
              <if test="bizId != null"> AND biz_id = #{bizId} </if>
              <if test="status != null and status != ''"> AND status = #{status} </if>
              <if test="priority != null and priority != ''"> AND priority = #{priority} </if>
              <if test="dueBefore != null"> AND due_time IS NOT NULL AND due_time &lt;= #{dueBefore} </if>
            </script>
            """)
    long countItems(
            @Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("bizId") Long bizId,
            @Param("status") String status,
            @Param("priority") String priority,
            @Param("dueBefore") LocalDateTime dueBefore
    );

    @Select("""
            <script>
            SELECT id, biz_type AS bizType, biz_id AS bizId, action_type AS actionType,
                   title, action_url AS actionUrl, due_time AS dueTime, priority, status,
                   handle_time AS handleTime, handle_result AS handleResult,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM todo_item
            WHERE user_id = #{userId} AND deleted = 0
              <if test="bizType != null and bizType != ''"> AND biz_type = #{bizType} </if>
              <if test="bizId != null"> AND biz_id = #{bizId} </if>
              <if test="status != null and status != ''"> AND status = #{status} </if>
              <if test="priority != null and priority != ''"> AND priority = #{priority} </if>
              <if test="dueBefore != null"> AND due_time IS NOT NULL AND due_time &lt;= #{dueBefore} </if>
            ORDER BY
                CASE status WHEN 'open' THEN 0 ELSE 1 END,
                CASE priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END,
                COALESCE(due_time, '9999-12-31 00:00:00'),
                id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectItems(
            @Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("bizId") Long bizId,
            @Param("status") String status,
            @Param("priority") String priority,
            @Param("dueBefore") LocalDateTime dueBefore,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    /**
     * 关闭待办（仅允许从 open 流转，条件更新天然防并发重复处理）。
     */
    @Update("""
            UPDATE todo_item
            SET status = #{status},
                handle_time = #{handleTime},
                handle_by = #{handleBy},
                handle_result = 'closed',
                update_by = #{operator}
            WHERE id = #{id} AND status = 'open' AND deleted = 0
            """)
    int closeItem(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("handleTime") LocalDateTime handleTime,
            @Param("handleBy") Long handleBy,
            @Param("operator") String operator
    );

    /** 效能画像（R7）：本人待办统计（实时聚合，不落画像表）。 */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status = 'open' THEN 1 ELSE 0 END) AS openCount,
                   SUM(CASE WHEN status = 'handled' THEN 1 ELSE 0 END) AS handledCount,
                   SUM(CASE WHEN status = 'ignored' THEN 1 ELSE 0 END) AS ignoredCount,
                   ROUND(AVG(CASE WHEN status = 'handled' AND handle_time IS NOT NULL
                                  THEN TIMESTAMPDIFF(MINUTE, create_time, handle_time) END), 1) AS avgHandleMinutes
            FROM todo_item
            WHERE user_id = #{userId} AND deleted = 0
            """)
    Map<String, Object> selectStatByUser(@Param("userId") Long userId);

    /** 效能画像：按 bizType 的收到/已处理分布（任务吞吐口径：任务指派待办的处理量）。 */
    @Select("""
            SELECT biz_type AS bizType,
                   COUNT(*) AS received,
                   SUM(CASE WHEN status = 'handled' THEN 1 ELSE 0 END) AS handled,
                   ROUND(AVG(CASE WHEN status = 'handled' AND handle_time IS NOT NULL
                                  THEN TIMESTAMPDIFF(MINUTE, create_time, handle_time) END), 1) AS avgHandleMinutes
            FROM todo_item
            WHERE user_id = #{userId} AND deleted = 0
            GROUP BY biz_type
            ORDER BY biz_type
            """)
    List<Map<String, Object>> selectStatByBizType(@Param("userId") Long userId);
}
