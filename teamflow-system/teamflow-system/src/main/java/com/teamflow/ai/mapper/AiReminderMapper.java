package com.teamflow.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M11 AI 智能提醒表（{@code ai_reminder}）访问层。
 *
 * <p>只保存「判定 + 理由 + M09 通知 ID」；触达由通知模块完成，本 Mapper 无 {@code msg_*} 表写入。</p>
 */
@Mapper
public interface AiReminderMapper {

    String COLUMNS = """
            SELECT r.id,
                   r.reminder_type AS reminderType,
                   r.biz_type AS bizType,
                   r.biz_id AS bizId,
                   r.receiver_id AS receiverId,
                   u.nickname AS receiverName,
                   DATE_FORMAT(r.remind_date, '%Y-%m-%d') AS remindDate,
                   DATE_FORMAT(r.due_date, '%Y-%m-%d') AS dueDate,
                   r.reason,
                   r.notify_status AS notifyStatus,
                   r.notification_id AS notificationId,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM ai_reminder r
            LEFT JOIN sys_user u ON u.id = r.receiver_id
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_reminder r
            WHERE r.deleted = 0
              <if test="status != null and status != ''">AND r.notify_status = #{status}</if>
            </script>
            """)
    long countReminders(@Param("status") String status);

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE r.deleted = 0
              <if test="status != null and status != ''">AND r.notify_status = #{status}</if>
            ORDER BY r.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectReminders(@Param("status") String status,
                                              @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select("SELECT id FROM ai_reminder WHERE reminder_type = #{reminderType} AND biz_type = #{bizType}"
            + " AND biz_id = #{bizId} AND receiver_id = #{receiverId} AND remind_date = #{remindDate} LIMIT 1")
    Long selectIdByDailyKey(@Param("reminderType") String reminderType,
                            @Param("bizType") String bizType,
                            @Param("bizId") Long bizId,
                            @Param("receiverId") Long receiverId,
                            @Param("remindDate") String remindDate);

    @Insert("""
            INSERT INTO ai_reminder (reminder_type, biz_type, biz_id, receiver_id, remind_date, due_date,
                                     reason, notify_status, notification_id, create_by, update_by)
            VALUES (#{row.reminderType}, #{row.bizType}, #{row.bizId}, #{row.receiverId}, #{row.remindDate},
                    #{row.dueDate}, #{row.reason}, #{row.notifyStatus}, #{row.notificationId},
                    #{operator}, #{operator})
            """)
    int insertReminder(@Param("row") Map<String, Object> row, @Param("operator") String operator);
}
