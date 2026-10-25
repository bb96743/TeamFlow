package com.teamflow.personal.service;

import com.teamflow.personal.mapper.FocusSessionMapper;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import com.teamflow.personal.mapper.ReviewLogMapper;
import com.teamflow.personal.mapper.TimeLogMapper;
import com.teamflow.personal.mapper.TodoItemMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M10 个人效能画像（R7）：**只读实时聚合，不落明细画像表**。
 *
 * <p>口径说明（与 M13 指标字典对齐前的临时口径，交付 CR 已登记）：</p>
 * <ul>
 *   <li><b>任务吞吐</b>：按 {@code todo_item.biz_type='task_assign'} 的收到/已完成数近似（个人视角的任务承接与处理量）；</li>
 *   <li><b>响应时长</b>：{@code todo_item} 的 {@code handle_time - create_time} 平均分钟数（仅已处理）；</li>
 *   <li><b>承接量</b>：收到的待办总数按 bizType 分布；</li>
 *   <li>另附工时总分钟数、涉及任务数、专注会话与复盘篇数，全部实时 COUNT/SUM/AVG，无画像明细表。</li>
 * </ul>
 * <p>数据全部来自 M10 自有表（{@code todo_item}/{@code time_log}/{@code focus_session}/{@code review_log}），
 * 不直连 M03/M05 业务表（L0 §4.3）。</p>
 */
@Service
public class PersonalProfileService extends PersonalServiceSupport {

    private final TodoItemMapper todoItemMapper;
    private final TimeLogMapper timeLogMapper;
    private final FocusSessionMapper focusSessionMapper;
    private final ReviewLogMapper reviewLogMapper;

    public PersonalProfileService(
            PersonalPlatformMapper platformMapper,
            TodoItemMapper todoItemMapper,
            TimeLogMapper timeLogMapper,
            FocusSessionMapper focusSessionMapper,
            ReviewLogMapper reviewLogMapper
    ) {
        super(platformMapper);
        this.todoItemMapper = todoItemMapper;
        this.timeLogMapper = timeLogMapper;
        this.focusSessionMapper = focusSessionMapper;
        this.reviewLogMapper = reviewLogMapper;
    }

    /**
     * 本人效能画像（实时聚合）。返回结构直接可序列化，不做持久化。
     */
    public Map<String, Object> profile() {
        Long userId = currentUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("readOnly", true);
        result.put("sourceTables", List.of("todo_item", "time_log", "focus_session", "review_log"));

        Map<String, Object> todoStat = todoItemMapper.selectStatByUser(userId);
        Map<String, Object> throughput = new LinkedHashMap<>();
        throughput.put("received", longOf(todoStat, "total"));
        throughput.put("open", longOf(todoStat, "openCount"));
        throughput.put("handled", longOf(todoStat, "handledCount"));
        throughput.put("ignored", longOf(todoStat, "ignoredCount"));
        throughput.put("avgHandleMinutes", decimalOf(todoStat, "avgHandleMinutes"));
        result.put("todoThroughput", throughput);
        result.put("byBizType", todoItemMapper.selectStatByBizType(userId));

        Map<String, Object> timeStat = timeLogMapper.selectStatByUser(userId);
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("totalMinutes", longOf(timeStat, "totalMinutes"));
        time.put("taskCount", longOf(timeStat, "taskCount"));
        time.put("logCount", longOf(timeStat, "logCount"));
        time.put("topTasks", timeLogMapper.selectTopTasks(userId, 5));
        result.put("workTime", time);

        Map<String, Object> focusStat = focusSessionMapper.selectStatByUser(userId);
        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("sessionCount", longOf(focusStat, "sessionCount"));
        focus.put("totalMinutes", longOf(focusStat, "totalMinutes"));
        result.put("focus", focus);

        result.put("reviewCount", reviewLogMapper.countByUser(userId));
        return result;
    }

    private long longOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private Double decimalOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? null : Double.valueOf(String.valueOf(value));
    }
}
