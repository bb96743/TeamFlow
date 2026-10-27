package com.teamflow.integration.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.mapper.IntegrationPlatformMapper;
import com.teamflow.integration.mapper.OpenTransferLogMapper;
import com.teamflow.integration.support.CsvSupport;
import com.teamflow.task.domain.dto.TaskWriteRequest;
import com.teamflow.task.service.TaskService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入导出服务（2.4.5）。
 *
 * <p><b>导入</b>：CSV 模板 + 逐行校验 + 逐行结果（成功/失败原因），每行都调用 M03 的
 * {@link TaskService#create} 走原模块校验与落库，<b>不直写业务表</b>；失败行不产生数据。
 * <b>导出</b>：CSV（UTF-8 BOM，Excel 友好），数据来自 {@link TaskService#list}，
 * 因此<b>自动受当前用户数据权限约束</b>（低权限导出条数少于超管）。</p>
 */
@Service
public class TransferService extends IntegrationSupport {

    private static final String BIZ_TYPE = "tasks";
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final List<String> HEADER = List.of(
            "任务标题", "任务描述", "优先级", "负责人账号", "计划开始", "截止日期", "关联KR ID");

    private final TaskService taskService;
    private final IntegrationPlatformMapper platformMapper;
    private final OpenTransferLogMapper transferLogMapper;

    public TransferService(
            TaskService taskService,
            IntegrationPlatformMapper platformMapper,
            OpenTransferLogMapper transferLogMapper
    ) {
        this.taskService = taskService;
        this.platformMapper = platformMapper;
        this.transferLogMapper = transferLogMapper;
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /** 导出任务列表为 CSV（UTF-8 BOM）；只导出当前用户数据范围内可见的任务。 */
    public byte[] exportTasks(String keyword, String status) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int page = 1;
        long total = 0;
        while (page <= MAX_PAGES) {
            PageResult<Map<String, Object>> result = taskService.list(
                    new PageQuery(page, PAGE_SIZE, null, null), trimToNull(keyword), trimToNull(status),
                    null, null, null);
            if (result.rows() != null && !result.rows().isEmpty()) {
                rows.addAll(result.rows());
            }
            total = result.total();
            if (rows.size() >= total || result.rows() == null || result.rows().size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        StringBuilder csv = new StringBuilder(CsvSupport.BOM);
        csv.append(CsvSupport.row(HEADER.toArray())).append("\r\n");
        for (Map<String, Object> row : rows) {
            csv.append(CsvSupport.row(
                    row.get("taskTitle"),
                    row.get("taskDesc"),
                    row.get("priority"),
                    row.get("assigneeName"),
                    row.get("startDate"),
                    row.get("dueDate"),
                    row.get("krId"))).append("\r\n");
        }
        recordLog("export", "tasks-" + System.currentTimeMillis() + ".csv", rows.size(), rows.size(),
                0, "success", null);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 导入模板（表头 + 一行示例；UTF-8 BOM）。 */
    public byte[] taskTemplate() {
        StringBuilder csv = new StringBuilder(CsvSupport.BOM);
        csv.append(CsvSupport.row(HEADER.toArray())).append("\r\n");
        csv.append(CsvSupport.row(
                "示例：完成项目复盘报告", "可留空", "normal", "", "2026-11-01", "2026-11-10", ""))
                .append("\r\n");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 导入（逐行校验，失败行不留脏数据）
    // ------------------------------------------------------------------

    public Map<String, Object> importTasks(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException("导入文件为空");
        }
        List<List<String>> rows = CsvSupport.parse(new String(content, StandardCharsets.UTF_8));
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        int startIndex = 0;
        if (!rows.isEmpty() && looksLikeHeader(rows.get(0))) {
            startIndex = 1;
        }
        for (int index = startIndex; index < rows.size(); index++) {
            List<String> cells = rows.get(index);
            int line = index + 1;
            if (cells.stream().allMatch(item -> item == null || item.isBlank())) {
                continue;
            }
            Map<String, Object> rowResult = new LinkedHashMap<>();
            rowResult.put("line", line);
            try {
                Long taskId = importOne(cells);
                rowResult.put("success", true);
                rowResult.put("taskId", taskId);
                success++;
            } catch (BusinessException exception) {
                rowResult.put("success", false);
                rowResult.put("error", exception.getMessage());
                failed++;
                if (errors.size() < 5) {
                    errors.add("第 " + line + " 行：" + exception.getMessage());
                }
            } catch (RuntimeException exception) {
                rowResult.put("success", false);
                rowResult.put("error", "导入失败：" + exception.getMessage());
                failed++;
                if (errors.size() < 5) {
                    errors.add("第 " + line + " 行：" + exception.getMessage());
                }
            }
            results.add(rowResult);
        }
        int total = success + failed;
        String status = failed == 0 ? "success" : (success == 0 ? "failed" : "partial");
        Long batchId = recordLog("import", trimToNull(fileName), total, success, failed, status,
                errors.isEmpty() ? null : String.join("；", errors));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("total", total);
        result.put("success", success);
        result.put("failed", failed);
        result.put("status", status);
        result.put("rows", results);
        result.put("note", failed == 0 ? "全部导入成功" : "失败行未写入任何数据，可修正后重试");
        return result;
    }

    /** 单行导入：先本地格式校验，再走 M03 Service（原模块校验 + 落库 + 事件/轨迹）。 */
    private Long importOne(List<String> cells) {
        String title = required(cell(cells, 0), "任务标题不能为空");
        String desc = trimToNull(cell(cells, 1));
        String priority = defaultString(cell(cells, 2), "normal");
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException("优先级只能为 low / normal / high / urgent");
        }
        String assigneeUsername = trimToNull(cell(cells, 3));
        Long assigneeId = null;
        if (assigneeUsername != null) {
            assigneeId = platformMapper.selectUserIdByUsername(assigneeUsername);
            if (assigneeId == null) {
                throw new BusinessException("负责人账号不存在或已删除：" + assigneeUsername);
            }
        }
        String startDate = dateOf(cell(cells, 4), "计划开始");
        String dueDate = dateOf(cell(cells, 5), "截止日期");
        Long krId = krIdOf(cell(cells, 6));
        TaskWriteRequest request = new TaskWriteRequest();
        request.setTaskTitle(title);
        request.setTaskDesc(desc);
        request.setPriority(priority);
        request.setAssigneeId(assigneeId);
        request.setStartDate(startDate);
        request.setDueDate(dueDate);
        request.setKrId(krId);
        return numberAsLong(taskService.create(request).id());
    }

    public List<Map<String, Object>> recentLogs(Integer limit) {
        return transferLogMapper.selectRecent(limit == null ? 20 : Math.max(1, Math.min(limit, 100)));
    }

    // ------------------------------------------------------------------

    private boolean looksLikeHeader(List<String> firstRow) {
        return firstRow.stream().anyMatch(cell -> cell != null
                && (cell.contains("任务标题") || "taskTitle".equalsIgnoreCase(cell.trim())));
    }

    private String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : null;
    }

    private String dateOf(String value, String label) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            LocalDate.parse(text, DATE);
            return text;
        } catch (DateTimeParseException exception) {
            throw new BusinessException(label + "日期格式应为 yyyy-MM-dd：" + text);
        }
    }

    private Long krIdOf(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            throw new BusinessException("关联 KR ID 必须为数字：" + text);
        }
    }

    private Long recordLog(String direction, String fileName, int total, int success, int failed,
                           String status, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("direction", direction);
        row.put("bizType", BIZ_TYPE);
        row.put("fileName", fileName);
        row.put("totalRows", total);
        row.put("successRows", success);
        row.put("failedRows", failed);
        row.put("status", status);
        row.put("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000));
        try {
            transferLogMapper.insertLog(row, currentUsername());
            return numberAsLong(row.get("id"));
        } catch (RuntimeException exception) {
            // 审计写入失败不影响导入导出主流程
            return null;
        }
    }
}
