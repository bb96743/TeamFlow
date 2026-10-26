package com.teamflow.ai.service;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.mapper.AiReportDraftMapper;
import com.teamflow.ai.support.AiDates;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.meeting.service.MeetingMeetingService;
import com.teamflow.okr.service.OkrObjectiveService;
import com.teamflow.task.service.TaskService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11 日报/周报草稿生成（1.3.4）。
 *
 * <p>数据只经 M03 任务、M02 目标、M06 会议的 Service 读取，并按调用者权限决定是否纳入；
 * 产出固定为 {@code draft} 草稿，可编辑但<b>没有发布接口</b>，必须人工确认后使用。</p>
 */
@Service
public class AiReportService {

    private final TaskService taskService;
    private final OkrObjectiveService objectiveService;
    private final MeetingMeetingService meetingService;
    private final AiPermissionService permissionService;
    private final AiPromptService promptService;
    private final AiReportDraftMapper reportMapper;

    public AiReportService(TaskService taskService, OkrObjectiveService objectiveService,
                           MeetingMeetingService meetingService, AiPermissionService permissionService,
                           AiPromptService promptService, AiReportDraftMapper reportMapper) {
        this.taskService = taskService;
        this.objectiveService = objectiveService;
        this.meetingService = meetingService;
        this.permissionService = permissionService;
        this.promptService = promptService;
        this.reportMapper = reportMapper;
    }

    @Transactional
    public Map<String, Object> generateDaily(AiCaller caller, String reportDate) {
        LocalDate date = reportDate == null || reportDate.isBlank()
                ? LocalDate.now() : AiDates.parseDate(reportDate, "日报日期");
        ReportData data = collect(caller, date, date, true);
        String title = "日报 · " + AiDates.DATE.format(date);
        String content = composeDaily(caller, date, data);
        return save(caller, "daily", date, date, date, title, content, data.summary());
    }

    @Transactional
    public Map<String, Object> generateWeekly(AiCaller caller, String periodStart, String periodEnd) {
        LocalDate today = LocalDate.now();
        LocalDate from = periodStart == null || periodStart.isBlank()
                ? AiDates.weekStart(today) : AiDates.parseDate(periodStart, "统计开始日期");
        LocalDate to = periodEnd == null || periodEnd.isBlank()
                ? today : AiDates.parseDate(periodEnd, "统计结束日期");
        if (to.isBefore(from)) {
            throw new BusinessException("统计结束日期不能早于开始日期");
        }
        ReportData data = collect(caller, from, to, false);
        String title = "周报 · " + AiDates.rangeLabel(from, to);
        String content = composeWeekly(caller, from, to, data);
        return save(caller, "weekly", null, from, to, title, content, data.summary());
    }

    public PageResult<Map<String, Object>> list(AiCaller caller, PageQuery query, String reportType) {
        int pageNum = query == null ? 1 : query.normalizedPageNum();
        int pageSize = query == null ? 10 : query.normalizedPageSize();
        String type = reportType == null || reportType.isBlank() ? null : reportType;
        long total = reportMapper.countReports(caller.userId(), type);
        List<Map<String, Object>> rows = reportMapper.selectReports(
                caller.userId(), type, (pageNum - 1) * pageSize, pageSize);
        return PageResult.of(rows, total, pageNum, pageSize);
    }

    public Map<String, Object> detail(AiCaller caller, Long id) {
        Map<String, Object> report = requireOwned(caller, id);
        return report;
    }

    @Transactional
    public Map<String, Object> update(AiCaller caller, Long id, String title, String content) {
        Map<String, Object> report = requireOwned(caller, id);
        if (!"draft".equals(String.valueOf(report.get("status")))) {
            throw new BusinessException("只有草稿可以编辑");
        }
        String nextTitle = title == null || title.isBlank()
                ? String.valueOf(report.get("reportTitle")) : title.trim();
        String nextContent = content == null || content.isBlank()
                ? String.valueOf(report.get("content")) : content;
        int updated = reportMapper.updateReport(id, nextTitle, nextContent, caller.username());
        if (updated == 0) {
            throw new BusinessException("草稿已变化，请刷新后重试");
        }
        return requireOwned(caller, id);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> save(AiCaller caller, String type, LocalDate reportDate,
                                     LocalDate periodStart, LocalDate periodEnd,
                                     String title, String content, String sourceSummary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reportType", type);
        row.put("reportDate", reportDate == null ? null : AiDates.DATE.format(reportDate));
        row.put("periodStart", AiDates.DATE.format(periodStart));
        row.put("periodEnd", AiDates.DATE.format(periodEnd));
        row.put("reportTitle", title);
        row.put("content", content);
        row.put("sourceSummary", sourceSummary);
        row.put("ownerId", caller.userId());
        row.put("ownerName", caller.username());
        reportMapper.insertReport(row, caller.username());
        return requireOwned(caller, numberAsLong(row.get("id")));
    }

    private Map<String, Object> requireOwned(AiCaller caller, Long id) {
        if (id == null) {
            throw new BusinessException("报告 ID 不能为空");
        }
        Map<String, Object> report = reportMapper.selectReportById(id);
        if (report == null) {
            throw new BusinessException("报告草稿不存在");
        }
        Long ownerId = numberAsLong(report.get("ownerId"));
        if (ownerId == null || !ownerId.equals(caller.userId())) {
            throw new AccessDeniedException("无权访问该报告草稿");
        }
        return report;
    }

    /** 按权限收集事实数据：无权限的维度不取数、在草稿中明确标注。 */
    private ReportData collect(AiCaller caller, LocalDate from, LocalDate to, boolean daily) {
        ReportData data = new ReportData();
        PageResult<Map<String, Object>> tasks = taskService.myTasks(new PageQuery(1, 100, null, null), null);
        List<Map<String, Object>> taskRows = tasks.rows() == null ? List.of() : tasks.rows();
        for (Map<String, Object> row : taskRows) {
            String status = text(row, "status");
            String doneTime = text(row, "doneTime");
            String dueDate = text(row, "dueDate");
            if ("done".equals(status) && !doneTime.isBlank()
                    && AiDates.inRange(doneTime, from, to)) {
                data.done.add(row);
            } else if ("todo".equals(status) || "in_progress".equals(status)) {
                data.inProgress.add(row);
            }
            if (!"done".equals(status) && !"cancelled".equals(status) && !dueDate.isBlank()
                    && AiDates.inRange(dueDate, from, to) && dueDate.compareTo(AiDates.DATE.format(LocalDate.now())) < 0) {
                data.overdue.add(row);
            }
        }

        if (permissionService.has(caller, "okr:objective:list")) {
            PageResult<Map<String, Object>> objectives = objectiveService.list(
                    new PageQuery(1, 50, null, null), null, null, caller.userId(), null, null);
            data.objectives = objectives.rows() == null ? List.of() : objectives.rows();
            data.objectivesAllowed = true;
        }
        if (permissionService.has(caller, "meeting:meeting:list")) {
            PageResult<Map<String, Object>> meetings = meetingService.list(
                    new PageQuery(1, 50, null, null), null, null,
                    AiDates.dateTime(from), AiDates.endOfDay(to));
            data.meetings = meetings.rows() == null ? List.of() : meetings.rows();
            data.meetingsAllowed = true;
        }
        data.daily = daily;
        return data;
    }

    private String composeDaily(AiCaller caller, LocalDate date, ReportData data) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 日报 · ").append(AiDates.DATE.format(date)).append("\n\n");
        builder.append("> 本内容为 AI 草稿，需人工确认后使用；生成时间 ").append(AiDates.display(java.time.LocalDateTime.now()))
                .append("。\n\n");
        builder.append("## 今日完成\n");
        appendTaskList(builder, data.done, "今日暂无已完成任务");
        builder.append("\n## 进行中\n");
        appendTaskList(builder, data.inProgress.stream().limit(10).toList(), "暂无进行中任务");
        builder.append("\n## 风险与阻塞\n");
        if (data.overdue.isEmpty()) {
            builder.append("- 暂无逾期任务\n");
        } else {
            for (Map<String, Object> row : data.overdue.stream().limit(10).toList()) {
                builder.append("- 逾期：").append(text(row, "taskTitle"))
                        .append("（截止 ").append(text(row, "dueDate")).append("）\n");
            }
        }
        builder.append("\n## 明日计划\n- 待补充（请在此处编辑）\n");
        appendObjectives(builder, data);
        appendMeetings(builder, data);
        builder.append("\n---\n> 草稿状态：draft；本期系统不提供发布动作，请人工确认后复制使用。\n");
        return builder.toString();
    }

    private String composeWeekly(AiCaller caller, LocalDate from, LocalDate to, ReportData data) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 周报 · ").append(AiDates.rangeLabel(from, to)).append("\n\n");
        builder.append("> 本内容为 AI 草稿，需人工确认后使用；生成时间 ").append(AiDates.display(java.time.LocalDateTime.now()))
                .append("。\n\n");
        builder.append("## 本周完成\n");
        appendTaskList(builder, data.done, "本周暂无已完成任务");
        builder.append("\n## 进行中\n");
        appendTaskList(builder, data.inProgress.stream().limit(10).toList(), "暂无进行中任务");
        builder.append("\n## 问题与风险\n");
        if (data.overdue.isEmpty()) {
            builder.append("- 暂无逾期任务\n");
        } else {
            for (Map<String, Object> row : data.overdue.stream().limit(10).toList()) {
                builder.append("- 逾期：").append(text(row, "taskTitle"))
                        .append("（截止 ").append(text(row, "dueDate")).append("）\n");
            }
        }
        builder.append("\n## 下周计划\n- 待补充（请在此处编辑）\n");
        appendObjectives(builder, data);
        appendMeetings(builder, data);
        builder.append("\n---\n> 草稿状态：draft；本期系统不提供发布动作，请人工确认后复制使用。\n");
        return builder.toString();
    }

    private void appendTaskList(StringBuilder builder, List<Map<String, Object>> rows, String emptyText) {
        if (rows.isEmpty()) {
            builder.append("- ").append(emptyText).append("\n");
            return;
        }
        for (Map<String, Object> row : rows) {
            builder.append("- ").append(text(row, "taskTitle"));
            String dueDate = text(row, "dueDate");
            if (!dueDate.isBlank()) {
                builder.append("（截止 ").append(dueDate).append("）");
            }
            builder.append("\n");
        }
    }

    private void appendObjectives(StringBuilder builder, ReportData data) {
        builder.append("\n## 目标进度\n");
        if (!data.objectivesAllowed) {
            builder.append("- 无目标查看权限，本次未纳入目标进度\n");
            return;
        }
        if (data.objectives.isEmpty()) {
            builder.append("- 本人暂无目标\n");
            return;
        }
        for (Map<String, Object> row : data.objectives.stream().limit(10).toList()) {
            builder.append("- ").append(text(row, "objectiveTitle"))
                    .append("：进度 ").append(text(row, "progress")).append("%\n");
        }
    }

    private void appendMeetings(StringBuilder builder, ReportData data) {
        builder.append("\n## 会议记录\n");
        if (!data.meetingsAllowed) {
            builder.append("- 无会议查看权限，本次未纳入会议记录\n");
            return;
        }
        if (data.meetings.isEmpty()) {
            builder.append("- 区间内无会议\n");
            return;
        }
        for (Map<String, Object> row : data.meetings.stream().limit(10).toList()) {
            builder.append("- ").append(text(row, "meetingTitle"))
                    .append("（").append(text(row, "startTime"));
            String endTime = text(row, "endTime");
            if (!endTime.isBlank()) {
                builder.append(" ~ ").append(endTime);
            }
            builder.append("）\n");
        }
    }

    private String text(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? "" : String.valueOf(row.get(key));
    }

    private Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static final class ReportData {
        private boolean daily;
        private boolean objectivesAllowed;
        private boolean meetingsAllowed;
        private final List<Map<String, Object>> done = new ArrayList<>();
        private final List<Map<String, Object>> inProgress = new ArrayList<>();
        private final List<Map<String, Object>> overdue = new ArrayList<>();
        private List<Map<String, Object>> objectives = List.of();
        private List<Map<String, Object>> meetings = List.of();

        private String summary() {
            return "任务：完成 " + done.size() + " 条 / 进行中 " + inProgress.size() + " 条 / 逾期 " + overdue.size()
                    + " 条；目标：" + (objectivesAllowed ? objectives.size() + " 条" : "无权限未纳入")
                    + "；会议：" + (meetingsAllowed ? meetings.size() + " 场" : "无权限未纳入")
                    + "；提示词：" + (daily ? "daily_report_system" : "weekly_report_system");
        }
    }
}
