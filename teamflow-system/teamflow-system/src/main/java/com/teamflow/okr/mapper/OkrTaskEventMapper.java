package com.teamflow.okr.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M02 任务事件派生投影与幂等日志访问层（G-3）。
 *
 * <p>只操作 M02 自有表 {@code okr_kr_task_link} / {@code okr_task_event_log}，
 * **不读写 M03 的 {@code task_*} 表**（L0 §4.3 禁止跨模块直连业务表）。</p>
 */
@Mapper
public interface OkrTaskEventMapper {

    /**
     * 写入事件幂等日志；{@code event_id} 唯一键冲突即表示重复投递（由调用方捕获并跳过）。
     */
    @Insert("""
            INSERT INTO okr_task_event_log (event_id, event_type, task_id, kr_id, root_task_id, is_root,
                                            from_status, to_status, operator_id, occurred_at, process_result, reason)
            VALUES (#{row.eventId}, #{row.eventType}, #{row.taskId}, #{row.krId}, #{row.rootTaskId}, #{row.isRoot},
                    #{row.fromStatus}, #{row.toStatus}, #{row.operatorId}, #{row.occurredAt},
                    #{row.processResult}, #{row.reason})
            """)
    int insertEventLog(@Param("row") Map<String, Object> row);

    /**
     * 回写事件处理结果（跳过/失败原因、处理后进度）。
     */
    @Update("""
            UPDATE okr_task_event_log
            SET process_result = #{processResult},
                progress_after = #{progressAfter},
                reason = #{reason}
            WHERE event_id = #{eventId}
            """)
    int markResult(
            @Param("eventId") String eventId,
            @Param("processResult") String processResult,
            @Param("progressAfter") BigDecimal progressAfter,
            @Param("reason") String reason
    );

    /**
     * 投影写入（业务键 {@code (kr_id, task_id)} 唯一）：同一任务对同一 KR 只有一行，
     * 状态与完成时间按最新事件覆盖——这是第二层幂等（业务键去重）。
     */
    @Insert("""
            INSERT INTO okr_kr_task_link (kr_id, task_id, is_root, task_status, completed_at, create_by, update_by)
            VALUES (#{krId}, #{taskId}, #{isRoot}, #{taskStatus}, #{completedAt}, 'event', 'event')
            ON DUPLICATE KEY UPDATE
                is_root = VALUES(is_root),
                task_status = VALUES(task_status),
                completed_at = VALUES(completed_at),
                update_by = 'event'
            """)
    int upsertTaskLink(
            @Param("krId") Long krId,
            @Param("taskId") Long taskId,
            @Param("isRoot") boolean isRoot,
            @Param("taskStatus") String taskStatus,
            @Param("completedAt") LocalDateTime completedAt
    );

    /** KR 关联的顶层任务总数（进度分母；子任务不计入）。 */
    @Select("""
            SELECT COUNT(*) FROM okr_kr_task_link
            WHERE kr_id = #{krId} AND is_root = 1
            """)
    int countRootTasks(@Param("krId") Long krId);

    /** KR 关联的已完成顶层任务数（进度分子；子任务不计入）。 */
    @Select("""
            SELECT COUNT(*) FROM okr_kr_task_link
            WHERE kr_id = #{krId} AND is_root = 1 AND task_status = 'done'
            """)
    int countDoneRootTasks(@Param("krId") Long krId);

    /** 事件处理结果计数（测试/排障用：按结果统计事件）。 */
    @Select("""
            SELECT COUNT(*) FROM okr_task_event_log
            WHERE kr_id = #{krId} AND process_result = #{processResult}
            """)
    int countEvents(@Param("krId") Long krId, @Param("processResult") String processResult);

    // ------------------------------------------------------------------
    // 投影清理（M03 补发的两个 G-3 遗留事件消费用）
    // ------------------------------------------------------------------

    /**
     * 删除某任务在某 KR 上的投影行（任务改挂 KR：清理旧 KR）。
     */
    @Delete("""
            DELETE FROM okr_kr_task_link
            WHERE kr_id = #{krId} AND task_id = #{taskId}
            """)
    int deleteTaskLink(@Param("krId") Long krId, @Param("taskId") Long taskId);

    /**
     * 查某任务在所有 KR 上的投影行所属 KR（任务删除时先取受影响 KR，供重算）。
     */
    @Select("""
            SELECT DISTINCT kr_id FROM okr_kr_task_link
            WHERE task_id = #{taskId}
            ORDER BY kr_id
            """)
    List<Long> selectKrIdsByTaskId(@Param("taskId") Long taskId);

    /**
     * 删除某任务在所有 KR 上的投影行（任务逻辑删除：完全退出 KR 分母）。
     */
    @Delete("""
            DELETE FROM okr_kr_task_link
            WHERE task_id = #{taskId}
            """)
    int deleteTaskLinksByTaskId(@Param("taskId") Long taskId);
}
