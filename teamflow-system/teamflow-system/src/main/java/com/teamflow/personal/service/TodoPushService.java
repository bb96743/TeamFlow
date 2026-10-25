package com.teamflow.personal.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import com.teamflow.personal.mapper.TodoItemMapper;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.personal.todo.TodoPushCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * M10 统一投递服务（R1）：外部 HTTP 入口 {@code POST /api/personal/todos/internal/push} 与
 * 业务模块的进程内调用**共用同一个实现**，是写入 {@code todo_item} 的唯一入口。
 *
 * <p><b>禁止直写</b>：业务模块不得注入 {@code TodoItemMapper} 或拼 SQL 写 {@code todo_item}；
 * 只能调用本服务（或 HTTP 入口），保证幂等（R2）与防复活（R4）逻辑只有一份。</p>
 *
 * <p><b>未登记 bizType</b>：直接拒绝（防止拼写错误产生永远无法处理的脏待办）。
 * 已登记但无 handler 的预留类型（如 {@code meeting_invite}）允许投递，处理时由 {@link TodoService} 拒绝。</p>
 */
@Service
public class TodoPushService extends PersonalServiceSupport {

    private static final Logger log = LoggerFactory.getLogger(TodoPushService.class);
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final String DEFAULT_ACTION_TYPE = "handle";

    private final TodoItemMapper todoItemMapper;

    public TodoPushService(PersonalPlatformMapper platformMapper, TodoItemMapper todoItemMapper) {
        super(platformMapper);
        this.todoItemMapper = todoItemMapper;
    }

    /**
     * 投递一条待办（幂等）：同一 {@code bizType+bizId+userId+actionType} 只保留一行；
     * 已处理/已忽略的行不会被重开，也不会刷新标题（R4）。
     *
     * @return 待办 ID（重复投递返回既有行 ID）
     */
    @Transactional
    public Long push(TodoPushCommand command) {
        if (command == null) {
            throw new BusinessException("投递命令不能为空");
        }
        String bizType = required(command.bizType(), "bizType 不能为空");
        if (!TodoBizTypes.isKnown(bizType)) {
            throw new BusinessException("未登记的 bizType：" + bizType + "，已登记类型为 " + TodoBizTypes.known());
        }
        Long bizId = command.bizId();
        if (bizId == null) {
            throw new BusinessException("bizId 不能为空");
        }
        Long userId = command.userId();
        if (userId == null) {
            throw new BusinessException("userId 不能为空（待办必须归属到具体用户）");
        }
        if (platformMapper.countActiveUser(userId) == 0) {
            throw new BusinessException("待办所属用户不存在或已停用：" + userId);
        }
        String title = required(command.title(), "待办标题不能为空");
        String actionUrl = required(command.actionUrl(), "回源链接 actionUrl 不能为空（R5：由投递方提供）");
        String priority = defaultString(command.priority(), "normal");
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException("待办优先级只能为 low、normal、high、urgent");
        }
        String actionType = defaultString(command.actionType(), DEFAULT_ACTION_TYPE);
        String operator = currentUsername();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("userId", userId);
        row.put("actionType", actionType);
        row.put("title", title);
        row.put("actionUrl", actionUrl);
        row.put("dueTime", command.dueTime());
        row.put("priority", priority);
        row.put("sourceEventId", trimToNull(command.sourceEventId()));
        todoItemMapper.upsertItem(row, operator);

        // V15 起同一业务键可能有多条历史行（handled/ignored 记录保留）：只按「当前未处理」行回传 ID
        Map<String, Object> saved = todoItemMapper.selectOpenByBizKey(bizType, bizId, userId, actionType);
        if (saved == null) {
            // 兜底：upsert 后理论上必有 open 行；取最新行仅用于排障，不改变幂等语义
            saved = todoItemMapper.selectByBizKey(bizType, bizId, userId, actionType);
        }
        Long todoId = saved == null ? null : numberAsLong(saved.get("id"));
        log.info("待办投递完成：bizType={} bizId={} userId={} actionType={} todoId={} status={}",
                bizType, bizId, userId, actionType, todoId, saved == null ? null : saved.get("status"));
        return todoId;
    }
}
