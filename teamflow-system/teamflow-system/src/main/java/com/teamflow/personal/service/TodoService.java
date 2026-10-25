package com.teamflow.personal.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.domain.dto.TodoHandleRequest;
import com.teamflow.personal.domain.dto.TodoPushRequest;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import com.teamflow.personal.mapper.TodoItemMapper;
import com.teamflow.personal.todo.TodoAction;
import com.teamflow.personal.todo.TodoBizHandler;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.personal.todo.TodoPushCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M10 收件箱服务：列表、处理（R3 回写后关闭）、内部投递（R1/R2/R4）与 bizType 登记查询。
 *
 * <p><b>R3 强约束的落地位置就在 {@link #handle}</b>：先命中 {@link TodoBizHandler} 让原模块改业务状态，
 * 只有 handler 返回 true 才关闭待办；handler 返回 false 或抛异常时，本方法抛业务异常并保持待办
 * {@code status='open'}（同时把失败原因写入 {@code todo_handle_log}，独立事务提交）。</p>
 */
@Service
public class TodoService extends PersonalServiceSupport {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);
    private static final String STATUS_OPEN = "open";

    private final TodoItemMapper todoItemMapper;
    private final TodoHandlerRegistry handlerRegistry;
    private final TodoHandleLogService handleLogService;
    private final TodoPushService pushService;

    public TodoService(
            PersonalPlatformMapper platformMapper,
            TodoItemMapper todoItemMapper,
            TodoHandlerRegistry handlerRegistry,
            TodoHandleLogService handleLogService,
            TodoPushService pushService
    ) {
        super(platformMapper);
        this.todoItemMapper = todoItemMapper;
        this.handlerRegistry = handlerRegistry;
        this.handleLogService = handleLogService;
        this.pushService = pushService;
    }

    // ------------------------------------------------------------------
    // 列表 / 登记表
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> list(
            PageQuery query, String bizType, Long bizId, String status, String priority, String dueBefore) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        LocalDateTime dueLimit = parseDateTime(dueBefore, "截止时间筛选");
        long total = todoItemMapper.countItems(userId, trimToNull(bizType), bizId, trimToNull(status),
                trimToNull(priority), dueLimit);
        List<Map<String, Object>> rows = todoItemMapper.selectItems(userId, trimToNull(bizType), bizId,
                trimToNull(status), trimToNull(priority), dueLimit, page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** bizType 登记表：已知类型 + 是否已注册 handler（未注册的类型只能展示、不能处理）。 */
    public Map<String, Object> bizTypes() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (String bizType : TodoBizTypes.known().stream().sorted().toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bizType", bizType);
            row.put("handlerRegistered", handlerRegistry.hasHandler(bizType));
            row.put("reserved", TodoBizTypes.reservedWithoutHandler().contains(bizType));
            types.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knownBizTypes", types);
        result.put("registeredHandlers", handlerRegistry.registered());
        return result;
    }

    // ------------------------------------------------------------------
    // 处理（R3）
    // ------------------------------------------------------------------

    /**
     * 在收件箱处理一条待办：**必须**先由原模块 handler 回写业务状态，成功后才关闭待办。
     *
     * @param todoId  待办 ID
     * @param request 用户动作（complete / ignore）
     * @return 处理后的待办行（status=handled/ignored）
     */
    @Transactional
    public Map<String, Object> handle(Long todoId, TodoHandleRequest request) {
        TodoAction action = TodoAction.of(request == null ? null : request.getAction());
        Long userId = currentUserId();
        String username = currentUsername();

        Map<String, Object> item = todoItemMapper.selectById(todoId);
        if (item == null) {
            throw new BusinessException("待办不存在或已删除");
        }
        Long ownerId = numberAsLong(item.get("userId"));
        if (!userId.equals(ownerId)) {
            throw new BusinessException("只能处理本人的待办");
        }
        String currentStatus = String.valueOf(item.get("status"));
        if (!STATUS_OPEN.equals(currentStatus)) {
            throw new BusinessException("待办已处理（" + statusLabel(currentStatus) + "），不能重复处理");
        }
        String bizType = String.valueOf(item.get("bizType"));
        Long bizId = numberAsLong(item.get("bizId"));
        String actionType = String.valueOf(item.get("actionType"));
        long startedAt = System.currentTimeMillis();

        Optional<TodoBizHandler> handlerOptional = handlerRegistry.find(bizType);
        if (handlerOptional.isEmpty()) {
            String reason = "该类型暂不支持在收件箱直接处理（bizType=" + bizType + " 未登记 TodoBizHandler）";
            recordInNewTransaction(todoId, bizType, bizId, userId, actionType, action.code(),
                    "rejected", null, reason, startedAt, username);
            throw new BusinessException(reason);
        }
        TodoBizHandler handler = handlerOptional.get();
        String handlerName = handler.getClass().getSimpleName();

        boolean accepted;
        try {
            accepted = handler.handle(bizId, userId, action);
        } catch (RuntimeException exception) {
            String reason = "原模块处理失败，待办保持未处理：" + exception.getMessage();
            recordInNewTransaction(todoId, bizType, bizId, userId, actionType, action.code(),
                    "failed", handlerName, reason, startedAt, username);
            throw new BusinessException(reason);
        }
        long costMs = System.currentTimeMillis() - startedAt;

        if (!accepted) {
            // R3 强约束：原模块没接受（返回 false），绝不关闭待办
            String reason = "原模块未接受本次处理（返回 false），待办保持未处理；请到原模块处理后重试";
            recordInNewTransaction(todoId, bizType, bizId, userId, actionType, action.code(),
                    "rejected", handlerName, reason, startedAt, username);
            throw new BusinessException(reason);
        }

        String finalStatus = action == TodoAction.IGNORE ? "ignored" : "handled";
        int updated = todoItemMapper.closeItem(todoId, finalStatus, LocalDateTime.now(), userId, username);
        if (updated == 0) {
            throw new BusinessException("待办已被处理，请刷新后重试");
        }
        handleLogService.record(todoId, bizType, bizId, userId, actionType, action.code(), "closed",
                handlerName, "原模块回写成功，待办已关闭", costMs, username);
        log.info("待办处理完成：todoId={} bizType={} bizId={} action={} -> {}",
                todoId, bizType, bizId, action.code(), finalStatus);
        return todoItemMapper.selectById(todoId);
    }

    // ------------------------------------------------------------------
    // 内部投递（R1）：进程内调用入口与 HTTP internal/push 共用
    // ------------------------------------------------------------------

    @Transactional
    public Long push(TodoPushRequest request) {
        if (request == null) {
            throw new BusinessException("投递请求不能为空");
        }
        TodoPushCommand command = new TodoPushCommand(
                request.getBizType(),
                request.getBizId(),
                request.getUserId(),
                request.getActionType(),
                request.getTitle(),
                request.getActionUrl(),
                parseDateTime(request.getDueTime(), "截止时间"),
                request.getPriority(),
                request.getSourceEventId());
        return pushService.push(command);
    }

    // ------------------------------------------------------------------
    // 展示字段与日志辅助
    // ------------------------------------------------------------------

    private void decorate(Map<String, Object> row) {
        String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
        row.put("statusLabel", statusLabel(status));
        String priority = row.get("priority") == null ? null : String.valueOf(row.get("priority"));
        row.put("priorityLabel", priorityLabel(priority));
        row.put("handlerRegistered", handlerRegistry.hasHandler(
                row.get("bizType") == null ? null : String.valueOf(row.get("bizType"))));
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "open" -> "未处理";
            case "handled" -> "已完成";
            case "ignored" -> "已忽略";
            default -> status;
        };
    }

    private String priorityLabel(String priority) {
        return switch (priority == null ? "" : priority) {
            case "low" -> "低";
            case "normal" -> "普通";
            case "high" -> "高";
            case "urgent" -> "紧急";
            default -> priority;
        };
    }

    private void recordInNewTransaction(
            Long todoId, String bizType, Long bizId, Long userId, String actionType,
            String action, String result, String handler, String reason, long startedAt, String username
    ) {
        try {
            handleLogService.recordInNewTransaction(todoId, bizType, bizId, userId, actionType, action, result,
                    handler, reason, System.currentTimeMillis() - startedAt, username);
        } catch (RuntimeException logException) {
            log.warn("待办处理失败日志写入失败：todoId={}", todoId, logException);
        }
    }
}
