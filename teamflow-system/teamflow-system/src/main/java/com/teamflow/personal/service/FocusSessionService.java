package com.teamflow.personal.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.domain.dto.FocusSessionEndRequest;
import com.teamflow.personal.domain.dto.FocusSessionStartRequest;
import com.teamflow.personal.mapper.FocusSessionMapper;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M10 专注会话服务（番茄钟后端留痕）。
 *
 * <p>本期只做「开始 / 结束 / 列表」，免打扰与前端计时交互不在范围（task-13 明确不做）。
 * 会话可关联任务，便于后续与工时/效能画像联合分析。</p>
 */
@Service
public class FocusSessionService extends PersonalServiceSupport {

    private static final Set<String> END_STATUSES = Set.of("finished", "abandoned");

    private final FocusSessionMapper focusSessionMapper;

    public FocusSessionService(PersonalPlatformMapper platformMapper, FocusSessionMapper focusSessionMapper) {
        super(platformMapper);
        this.focusSessionMapper = focusSessionMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        long total = focusSessionMapper.countSessions(userId);
        List<Map<String, Object>> rows = focusSessionMapper.selectSessions(userId, page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @Transactional
    public Long start(FocusSessionStartRequest request) {
        Long userId = currentUserId();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", userId);
        row.put("taskId", request == null ? null : request.getTaskId());
        row.put("startTime", LocalDateTime.now());
        row.put("remark", request == null ? null : trimToNull(request.getRemark()));
        focusSessionMapper.insertSession(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    @Transactional
    public Map<String, Object> end(Long id, FocusSessionEndRequest request) {
        Map<String, Object> existing = focusSessionMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("专注会话不存在或已删除");
        }
        if (!currentUserId().equals(numberAsLong(existing.get("userId")))) {
            throw new BusinessException("只能操作本人的专注会话");
        }
        if (!"running".equals(String.valueOf(existing.get("status")))) {
            throw new BusinessException("专注会话已结束，不能重复结束");
        }
        LocalDateTime startTime = toLocalDateTime(existing.get("startTime"));
        LocalDateTime endTime = LocalDateTime.now();
        int minutes = startTime == null ? 0 : (int) Math.max(1, Duration.between(startTime, endTime).toMinutes());
        int interrupts = request != null && request.getInterruptCount() != null ? Math.max(0, request.getInterruptCount()) : 0;
        String status = request == null || request.getStatus() == null ? "finished" : request.getStatus().trim().toLowerCase();
        if (!END_STATUSES.contains(status)) {
            throw new BusinessException("结束状态只能为 finished 或 abandoned");
        }
        focusSessionMapper.finishSession(id, endTime, minutes, interrupts, status, currentUsername());
        return focusSessionMapper.selectById(id);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }
}
