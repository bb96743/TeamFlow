package com.teamflow.personal.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.domain.dto.TimeLogWriteRequest;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import com.teamflow.personal.mapper.TimeLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M10 工时服务：任务级工时填报、编辑、删除与按任务汇总。
 *
 * <p><b>R6</b>：{@code taskId} 必填且大于 0，拒绝裸工时；时长必须为正整数分钟。
 * 本模块**不校验任务是否存在**（不直连 {@code task_task}）：任务归属 M03，存在性由投递/选择流程保证，
 * 若需强校验待 M03 提供只读引用接口后补充（已在交付 CR 的已知问题中登记）。</p>
 */
@Service
public class TimeLogService extends PersonalServiceSupport {

    private final TimeLogMapper timeLogMapper;

    public TimeLogService(PersonalPlatformMapper platformMapper, TimeLogMapper timeLogMapper) {
        super(platformMapper);
        this.timeLogMapper = timeLogMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, Long taskId, String fromDate, String toDate) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        String from = dateText(parseDate(fromDate, "开始日期"));
        String to = dateText(parseDate(toDate, "结束日期"));
        long total = timeLogMapper.countLogs(userId, taskId, from, to);
        List<Map<String, Object>> rows = timeLogMapper.selectLogs(userId, taskId, from, to,
                page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 按任务汇总（DoD：工时按任务汇总正确）。 */
    public List<Map<String, Object>> summary(String fromDate, String toDate) {
        Long userId = currentUserId();
        return timeLogMapper.selectSummaryByTask(userId,
                dateText(parseDate(fromDate, "开始日期")), dateText(parseDate(toDate, "结束日期")));
    }

    @Transactional
    public Long create(TimeLogWriteRequest request) {
        Long userId = currentUserId();
        Map<String, Object> row = buildRow(request);
        row.put("userId", userId);
        timeLogMapper.insertLog(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    @Transactional
    public void update(Long id, TimeLogWriteRequest request) {
        requireOwned(id);
        Map<String, Object> row = buildRow(request);
        timeLogMapper.updateLog(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        timeLogMapper.softDelete(id, currentUsername());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildRow(TimeLogWriteRequest request) {
        Long taskId = request.getTaskId();
        if (taskId == null || taskId <= 0) {
            throw new BusinessException("工时必须关联任务（taskId 不能为空），禁止无归属的裸工时（M10 RULE R6）");
        }
        Integer duration = request.getDurationMinutes();
        if (duration == null || duration <= 0) {
            throw new BusinessException("工时分钟数必须大于 0");
        }
        LocalDate workDate = request.getWorkDate() == null ? LocalDate.now()
                : parseDate(request.getWorkDate(), "工作日期");
        LocalDateTime startTime = parseDateTime(request.getStartTime(), "开始时间");
        LocalDateTime endTime = parseDateTime(request.getEndTime(), "结束时间");
        if (startTime != null && endTime != null && endTime.isBefore(startTime)) {
            throw new BusinessException("结束时间不能早于开始时间");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", taskId);
        row.put("workDate", workDate);
        row.put("startTime", startTime);
        row.put("endTime", endTime);
        row.put("durationMinutes", duration);
        row.put("description", trimToNull(request.getDescription()));
        return row;
    }

    private void requireOwned(Long id) {
        Map<String, Object> existing = timeLogMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("工时记录不存在或已删除");
        }
        if (!currentUserId().equals(numberAsLong(existing.get("userId")))) {
            throw new BusinessException("只能操作本人的工时记录");
        }
    }
}
