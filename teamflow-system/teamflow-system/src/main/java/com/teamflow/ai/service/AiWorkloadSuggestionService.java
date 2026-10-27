package com.teamflow.ai.service;

import com.teamflow.ai.config.AiProperties;
import com.teamflow.ai.domain.dto.SuggestionAssignRequest;
import com.teamflow.ai.mapper.AiPlatformMapper;
import com.teamflow.ai.mapper.AiSuggestionMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.service.TaskPerfQueryService;
import com.teamflow.task.service.TaskService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 待办建议（1.3.5）：基于**负载均衡规则**给出任务分配建议与可解释理由。
 *
 * <p><b>只建议、不派发</b>：本服务只写 {@code ai_suggestion}，**从不调用任务指派**、
 * 也不直写任务表；接口不提供"一键执行"，如需调整必须由用户显式走 M03 的指派接口。</p>
 *
 * <p><b>不写 N+1</b>：候选人负载一次经 M03 {@link TaskPerfQueryService#memberTaskLoads} 批量取数；
 * 候选目录一次读取；单次生成 SQL 为个位数。</p>
 *
 * <p><b>诚实</b>：本模块无大模型，结论来自确定性规则（进行中任务最少优先 → 总任务少 → 用户 ID 稳定排序）。</p>
 */
@Service
public class AiWorkloadSuggestionService {

    private static final String SUGGESTION_TYPE = "task_assign";
    private static final String BIZ_TYPE = "task";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TaskService taskService;
    private final TaskPerfQueryService taskPerfQueryService;
    private final AiPlatformMapper platformMapper;
    private final AiSuggestionMapper suggestionMapper;
    private final AiProperties properties;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public AiWorkloadSuggestionService(
            TaskService taskService,
            TaskPerfQueryService taskPerfQueryService,
            AiPlatformMapper platformMapper,
            AiSuggestionMapper suggestionMapper,
            AiProperties properties,
            tools.jackson.databind.ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.taskPerfQueryService = taskPerfQueryService;
        this.platformMapper = platformMapper;
        this.suggestionMapper = suggestionMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 为指定任务生成分配建议（存在则覆盖为最新建议）。
     */
    @Transactional
    public Map<String, Object> generate(SuggestionAssignRequest request) {
        if (request == null || request.taskId() == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        Long taskId = request.taskId();
        // 任务事实经 M03 Service（@DataScope：仅当前用户可见的任务）
        Map<String, Object> task = taskService.detail(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在或无权访问");
        }
        String taskTitle = String.valueOf(task.get("taskTitle"));
        String taskStatus = String.valueOf(task.get("status"));
        Long currentAssigneeId = numberAsLong(task.get("assigneeId"));
        Long deptId = numberAsLong(task.get("deptId"));

        // 候选目录：一次读取全部启用用户（负载对比的候选池）；任务归属部门只用于展示/审计
        List<Map<String, Object>> directory = platformMapper.selectUserDirectory();
        List<Map<String, Object>> candidates = new ArrayList<>(directory);
        if (currentAssigneeId != null) {
            boolean contains = candidates.stream()
                    .anyMatch(user -> currentAssigneeId.equals(numberAsLong(user.get("userId"))));
            if (!contains) {
                Map<String, Object> current = platformMapper.selectUserById(currentAssigneeId);
                if (current != null) {
                    candidates.add(current);
                }
            }
        }
        int limit = Math.max(2, properties.getSuggestionCandidateLimit());
        if (candidates.size() > limit) {
            candidates = new ArrayList<>(candidates.subList(0, limit));
        }
        if (candidates.isEmpty()) {
            throw new BusinessException("没有可用的候选用户，无法生成分配建议");
        }
        List<Long> candidateIds = candidates.stream()
                .map(user -> numberAsLong(user.get("userId")))
                .filter(java.util.Objects::nonNull)
                .toList();

        // 负载一次批量取数（M03 刚优化过的批量端口，不写 N+1）
        Map<Long, Map<String, Object>> loads = new HashMap<>();
        for (Map<String, Object> row : taskPerfQueryService.memberTaskLoads(candidateIds)) {
            loads.put(numberAsLong(row.get("assigneeId")), row);
        }

        List<Candidate> ranked = new ArrayList<>();
        for (Map<String, Object> user : candidates) {
            Long userId = numberAsLong(user.get("userId"));
            if (userId == null) {
                continue;
            }
            Map<String, Object> load = loads.getOrDefault(userId, Map.of());
            ranked.add(new Candidate(userId,
                    String.valueOf(user.get("nickname") == null ? user.get("username") : user.get("nickname")),
                    numberAsLong(load.get("taskInProgress")) == null ? 0 : numberAsLong(load.get("taskInProgress")),
                    numberAsLong(load.get("taskTotal")) == null ? 0 : numberAsLong(load.get("taskTotal"))));
        }
        if (ranked.isEmpty()) {
            throw new BusinessException("候选用户负载数据为空，无法生成建议");
        }
        ranked.sort(Comparator.comparingLong(Candidate::inProgress)
                .thenComparingLong(Candidate::total)
                .thenComparing(Candidate::userId));
        Candidate best = ranked.get(0);
        String reason = buildReason(taskTitle, taskStatus, currentAssigneeId, ranked, best);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "load_balance: 进行中任务最少优先 → 总任务少 → 用户 ID 稳定排序");
        evidence.put("taskId", taskId);
        evidence.put("taskTitle", taskTitle);
        evidence.put("taskStatus", taskStatus);
        evidence.put("currentAssigneeId", currentAssigneeId);
        evidence.put("candidates", ranked.stream().map(item -> Map.of(
                "userId", item.userId(),
                "name", item.name(),
                "inProgress", item.inProgress(),
                "total", item.total(),
                "selected", item == best)).toList());
        evidence.put("generatedAt", LocalDateTime.now().format(DATE_TIME));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("suggestionType", SUGGESTION_TYPE);
        row.put("bizType", BIZ_TYPE);
        row.put("bizId", taskId);
        row.put("currentAssigneeId", currentAssigneeId);
        row.put("suggestedUserId", best.userId());
        row.put("reason", reason);
        row.put("evidenceJson", toJson(evidence));
        suggestionMapper.upsertSuggestion(row, currentUsername());
        Long suggestionId = suggestionMapper.selectIdByBiz(SUGGESTION_TYPE, BIZ_TYPE, taskId);
        return view(suggestionId, taskTitle, taskStatus, currentAssigneeId, best, ranked, reason);
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String status) {
        Page page = pageOf(query);
        String statusFilter = trimToNull(status);
        long total = suggestionMapper.countSuggestions(statusFilter);
        List<Map<String, Object>> rows = suggestionMapper.selectSuggestions(statusFilter,
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = suggestionMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("分配建议不存在");
        }
        decorate(row);
        return row;
    }

    // ------------------------------------------------------------------

    private record Candidate(Long userId, String name, long inProgress, long total) {
    }

    private String buildReason(String taskTitle, String taskStatus, Long currentAssigneeId,
                               List<Candidate> ranked, Candidate best) {
        StringBuilder builder = new StringBuilder();
        builder.append("任务「").append(taskTitle).append("」（当前状态 ").append(taskStatus).append("）");
        if (currentAssigneeId == null) {
            builder.append("尚无负责人");
        } else {
            String currentName = ranked.stream()
                    .filter(item -> item.userId().equals(currentAssigneeId))
                    .map(Candidate::name)
                    .findFirst().orElse("用户 #" + currentAssigneeId);
            builder.append("当前负责人为 ").append(currentName);
        }
        builder.append("。候选人负载（进行中/总任务）：");
        List<String> parts = new ArrayList<>();
        for (Candidate item : ranked.subList(0, Math.min(3, ranked.size()))) {
            parts.add(item.name() + " " + item.inProgress() + "/" + item.total());
        }
        builder.append(String.join("，", parts)).append("。");
        builder.append("按「进行中任务最少优先，其次总任务少」的规则，建议分配给 ")
                .append(best.name()).append("（进行中 ").append(best.inProgress())
                .append(" 个、总任务 ").append(best.total()).append(" 个），")
                .append("该建议仅供参考，系统不会自动修改任务负责人。");
        return builder.toString();
    }

    private Map<String, Object> view(Long suggestionId, String taskTitle, String taskStatus,
                                     Long currentAssigneeId, Candidate best, List<Candidate> ranked,
                                     String reason) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("suggestionId", suggestionId);
        view.put("suggestionType", SUGGESTION_TYPE);
        view.put("bizType", BIZ_TYPE);
        view.put("taskTitle", taskTitle);
        view.put("taskStatus", taskStatus);
        view.put("currentAssigneeId", currentAssigneeId);
        view.put("suggestedUserId", best.userId());
        view.put("suggestedUserName", best.name());
        view.put("reason", reason);
        view.put("rule", "load_balance");
        view.put("executable", false);
        view.put("note", "仅为建议：本接口不会派发任务；如需调整请由用户显式调用任务模块的指派接口");
        view.put("candidates", ranked.stream().map(item -> Map.of(
                "userId", item.userId(),
                "name", item.name(),
                "inProgress", item.inProgress(),
                "total", item.total(),
                "selected", item == best)).toList());
        return view;
    }

    private void decorate(Map<String, Object> row) {
        row.put("statusLabel", "pending".equals(stringOf(row.get("status"))) ? "待处理" : "已过期");
        row.put("executable", false);
    }

    private record Page(int pageNum, int pageSize, int offset) {
    }

    private Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }
}
