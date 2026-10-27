package com.teamflow.ai.service;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.mapper.AiPlatformMapper;
import com.teamflow.ai.mapper.AiReminderMapper;
import com.teamflow.collab.domain.dto.InternalNotifyRequest;
import com.teamflow.collab.service.CollabNotificationService;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.service.TaskService;
import com.teamflow.task.status.TaskStatusMachine;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能提醒（1.3.6）：判定「何时提醒谁」，**实际触达由通知模块执行**。
 *
 * <p><b>判定规则（确定性，无大模型）</b>：当前用户可见任务中，责任人非空、任务未结束
 * （{@link TaskStatusMachine#isTerminal} 为假）、到期日在「今天 + N 天」之内或已逾期。</p>
 *
 * <p><b>触达边界</b>：本模块只调用 M09 的 {@link CollabNotificationService#notifyInternal} 统一入口，
 * **不直写消息/待办表**；同一任务同一责任人每天只提醒一次（唯一键 + 通知幂等键双重去重）。</p>
 */
@Service
public class AiSmartReminderService {

    private static final String REMINDER_TYPE = "task_due";
    private static final String BIZ_TYPE = "task";
    private static final String IDEMPOTENCY_PREFIX = "ai-reminder:";

    private final TaskService taskService;
    private final AiPlatformMapper platformMapper;
    private final AiReminderMapper reminderMapper;
    private final CollabNotificationService notificationService;
    private final AiProperties properties;

    public AiSmartReminderService(
            TaskService taskService,
            AiPlatformMapper platformMapper,
            AiReminderMapper reminderMapper,
            CollabNotificationService notificationService,
            AiProperties properties
    ) {
        this.taskService = taskService;
        this.platformMapper = platformMapper;
        this.reminderMapper = reminderMapper;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /**
     * 扫描并判定提醒（默认提前 3 天；可传 withinDays / limit 覆盖）。
     *
     * @return 扫描统计 + 逐条判定结果（含可读理由与 M09 通知 ID）
     */
    @Transactional
    public Map<String, Object> scan(Integer withinDays, Integer limit) {
        int days = clamp(withinDays == null ? properties.getReminderDaysAhead() : withinDays, 1, 14);
        int max = clamp(limit == null ? 20 : limit, 1, 50);
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(days);

        List<Map<String, Object>> matched = new ArrayList<>();
        int scanned = 0;
        for (int page = 1; page <= 3; page++) {
            PageResult<Map<String, Object>> result = taskService.list(
                    new PageQuery(page, 100, null, null), null, null, null, null, null);
            List<Map<String, Object>> rows = result.rows() == null ? List.of() : result.rows();
            scanned += rows.size();
            for (Map<String, Object> row : rows) {
                if (matched.size() >= max) {
                    break;
                }
                Long taskId = numberAsLong(row.get("id"));
                Long assigneeId = numberAsLong(row.get("assigneeId"));
                String status = stringOf(row.get("status"));
                String dueDate = stringOf(row.get("dueDate"));
                if (taskId == null || assigneeId == null || dueDate == null || dueDate.isBlank()) {
                    continue;
                }
                if (TaskStatusMachine.isTerminal(status)) {
                    continue;
                }
                LocalDate due;
                try {
                    due = LocalDate.parse(dueDate);
                } catch (RuntimeException exception) {
                    continue;
                }
                if (due.isAfter(deadline)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", taskId);
                item.put("taskTitle", row.get("taskTitle"));
                item.put("taskStatus", status);
                item.put("receiverId", assigneeId);
                item.put("receiverName", row.get("assigneeName"));
                item.put("dueDate", dueDate);
                item.put("daysLeft", ChronoUnit.DAYS.between(today, due));
                item.put("overdue", due.isBefore(today));
                matched.add(item);
            }
            if (rows.size() < 100 || scanned >= result.total() || matched.size() >= max) {
                break;
            }
        }

        int reminded = 0;
        int duplicated = 0;
        int failed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : matched) {
            Long taskId = numberAsLong(item.get("taskId"));
            Long receiverId = numberAsLong(item.get("receiverId"));
            String remindDate = today.toString();
            Long existing = reminderMapper.selectIdByDailyKey(REMINDER_TYPE, BIZ_TYPE, taskId, receiverId, remindDate);
            Map<String, Object> row = new LinkedHashMap<>(item);
            if (existing != null) {
                duplicated++;
                row.put("notifyStatus", "duplicated");
                row.put("reason", buildReason(item, duplicatedNotice(item)));
                rows.add(row);
                continue;
            }
            String reason = buildReason(item, null);
            String idempotencyKey = IDEMPOTENCY_PREFIX + taskId + ":" + receiverId + ":" + remindDate;
            try {
                String title = Boolean.TRUE.equals(item.get("overdue"))
                        ? "任务已逾期：" + stringOf(item.get("taskTitle"))
                        : "任务即将到期：" + stringOf(item.get("taskTitle"));
                Map<String, Object> result = notificationService.notifyInternal(new InternalNotifyRequest(
                        receiverId,
                        "system",
                        Map.of("title", title, "content", reason),
                        BIZ_TYPE,
                        taskId,
                        "/task/tasks?id=" + taskId,
                        idempotencyKey,
                        false
                ));
                Long notificationId = numberAsLong(result.get("id"));
                boolean existed = Boolean.TRUE.equals(result.get("duplicated"));
                Map<String, Object> reminder = new LinkedHashMap<>();
                reminder.put("reminderType", REMINDER_TYPE);
                reminder.put("bizType", BIZ_TYPE);
                reminder.put("bizId", taskId);
                reminder.put("receiverId", receiverId);
                reminder.put("remindDate", remindDate);
                reminder.put("dueDate", stringOf(item.get("dueDate")));
                reminder.put("reason", reason);
                reminder.put("notifyStatus", existed ? "duplicated" : "sent");
                reminder.put("notificationId", notificationId);
                reminderMapper.insertReminder(reminder, currentUsername());
                if (existed) {
                    duplicated++;
                } else {
                    reminded++;
                }
                row.put("notifyStatus", existed ? "duplicated" : "sent");
                row.put("notificationId", notificationId);
                row.put("reason", reason);
            } catch (RuntimeException exception) {
                failed++;
                row.put("notifyStatus", "failed");
                row.put("reason", reason);
                row.put("error", exception.getMessage());
                Map<String, Object> reminder = new LinkedHashMap<>();
                reminder.put("reminderType", REMINDER_TYPE);
                reminder.put("bizType", BIZ_TYPE);
                reminder.put("bizId", taskId);
                reminder.put("receiverId", receiverId);
                reminder.put("remindDate", remindDate);
                reminder.put("dueDate", stringOf(item.get("dueDate")));
                reminder.put("reason", reason + "（触达失败：" + exception.getMessage() + "）");
                reminder.put("notifyStatus", "failed");
                reminder.put("notificationId", null);
                try {
                    reminderMapper.insertReminder(reminder, currentUsername());
                } catch (RuntimeException ignored) {
                    // 唯一键冲突（并发扫描）时忽略
                }
            }
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("matched", matched.size());
        result.put("reminded", reminded);
        result.put("duplicated", duplicated);
        result.put("failed", failed);
        result.put("withinDays", days);
        result.put("remindDate", today.toString());
        result.put("rule", "任务未结束、责任人非空、到期日在 " + days + " 天内或已逾期；同一任务同一责任人每天只提醒一次");
        result.put("notifyChannel", "M09 统一通知入口（msg_notification）；本模块不直写消息/待办表");
        result.put("rows", rows);
        return result;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String status) {
        Page page = pageOf(query);
        String statusFilter = trimToNull(status);
        long total = reminderMapper.countReminders(statusFilter);
        List<Map<String, Object>> rows = reminderMapper.selectReminders(statusFilter,
                page.offset(), page.pageSize());
        rows.forEach(row -> row.put("notifyStatusLabel", switch (stringOf(row.get("notifyStatus"))) {
            case "sent" -> "已提醒";
            case "duplicated" -> "已存在（去重）";
            case "failed" -> "触达失败";
            default -> stringOf(row.get("notifyStatus"));
        }));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    // ------------------------------------------------------------------

    private String buildReason(Map<String, Object> item, String extra) {
        Long daysLeft = numberAsLong(item.get("daysLeft"));
        boolean overdue = Boolean.TRUE.equals(item.get("overdue"));
        String title = stringOf(item.get("taskTitle"));
        String receiverName = stringOf(item.get("receiverName"));
        if (receiverName == null || receiverName.isBlank()) {
            receiverName = "用户 #" + item.get("receiverId");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("任务「").append(title).append("」");
        if (overdue) {
            builder.append("已逾期 ").append(Math.abs(daysLeft == null ? 0 : daysLeft)).append(" 天");
        } else {
            builder.append("将在 ").append(daysLeft).append(" 天后到期");
        }
        builder.append("（到期日 ").append(item.get("dueDate")).append("，当前状态 ").append(item.get("taskStatus"))
                .append("），责任人为 ").append(receiverName)
                .append("；按提醒规则需要提醒该责任人及时处理。");
        if (extra != null) {
            builder.append(extra);
        }
        return builder.toString();
    }

    private String duplicatedNotice(Map<String, Object> item) {
        return "（今日已提醒过，本次不重复触达）";
    }

    private record Page(int pageNum, int pageSize, int offset) {
    }

    private Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
    }

    private int clamp(Integer value, int min, int max) {
        int raw = value == null ? min : value;
        return Math.max(min, Math.min(raw, max));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    private Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
