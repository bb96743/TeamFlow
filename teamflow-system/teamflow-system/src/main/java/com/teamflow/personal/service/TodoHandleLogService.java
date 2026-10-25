package com.teamflow.personal.service;

import com.teamflow.personal.mapper.TodoHandleLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M10 待办处理日志服务（R3 可审计）。
 *
 * <p><b>为什么有两种事务语义</b>：成功路径的日志必须与「关闭待办」同一事务（保持一致性）；
 * 失败/拒绝路径的日志必须在**独立事务**里先落库，否则外层抛业务异常回滚后，日志会被一起回滚，
 * 导致「为什么没关」无据可查。</p>
 */
@Service
public class TodoHandleLogService {

    private final TodoHandleLogMapper logMapper;

    public TodoHandleLogService(TodoHandleLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    /** 成功路径：与关闭待办同一事务。 */
    @Transactional
    public void record(
            Long todoId, String bizType, Long bizId, Long userId, String actionType,
            String action, String result, String handler, String reason, long costMs, String operator
    ) {
        logMapper.insertLog(row(todoId, bizType, bizId, userId, actionType, action, result, handler, reason, costMs),
                operator);
    }

    /** 失败/拒绝路径：独立事务，保证外层回滚后仍能看到失败原因。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(
            Long todoId, String bizType, Long bizId, Long userId, String actionType,
            String action, String result, String handler, String reason, long costMs, String operator
    ) {
        logMapper.insertLog(row(todoId, bizType, bizId, userId, actionType, action, result, handler, reason, costMs),
                operator);
    }

    private Map<String, Object> row(
            Long todoId, String bizType, Long bizId, Long userId, String actionType,
            String action, String result, String handler, String reason, long costMs
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("todoId", todoId);
        row.put("bizType", bizType);
        row.put("bizId", bizId);
        row.put("userId", userId);
        row.put("actionType", actionType);
        row.put("action", action);
        row.put("result", result);
        row.put("handler", handler);
        row.put("reason", reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500));
        row.put("costMs", costMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) costMs);
        return row;
    }
}
