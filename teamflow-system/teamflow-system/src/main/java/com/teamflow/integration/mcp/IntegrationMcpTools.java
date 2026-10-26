package com.teamflow.integration.mcp;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.security.PermissionResolver;
import com.teamflow.insight.service.InsightMetricService;
import com.teamflow.integration.security.ApiKeyIdentity;
import com.teamflow.integration.security.McpCallerContext;
import com.teamflow.integration.service.OpenApiAuditService;
import com.teamflow.knowledge.service.KnowledgeSearchService;
import com.teamflow.okr.service.OkrKeyResultService;
import com.teamflow.okr.service.OkrObjectiveService;
import com.teamflow.perf.service.PerfResultService;
import com.teamflow.project.service.ProjectService;
import com.teamflow.task.service.TaskService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * MCP Server 只读工具白名单（需求 2.4.2；L0 D-2026-10-27-12 结论四）。
 *
 * <p><b>只读</b>：全部工具只做查询，不提供任何写操作（M12 RULE §二）。
 * <b>调用方身份</b>：工具从当前 API Key 认证上下文取账号，<b>不提供系统超管身份</b>；
 * 权限码经 {@link PermissionResolver} 校验，数据范围由各业务 Service 的 {@code @DataScope}
 * 按该账号计算——外部 AI 客户端不会因为调用 MCP 而看到比 Key 绑定用户更多的数据。</p>
 *
 * <p><b>诚实边界</b>：本机可端到端验证工具清单与只读取数；第三方 SaaS 调用不在本模块。</p>
 */
@Component
public class IntegrationMcpTools {

    private final McpCallerContext callerContext;
    private final PermissionResolver permissionResolver;
    private final OpenApiAuditService auditService;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final OkrObjectiveService objectiveService;
    private final OkrKeyResultService keyResultService;
    private final PerfResultService perfResultService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final ProjectService projectService;
    private final InsightMetricService insightMetricService;

    public IntegrationMcpTools(
            McpCallerContext callerContext,
            PermissionResolver permissionResolver,
            OpenApiAuditService auditService,
            ObjectMapper objectMapper,
            TaskService taskService,
            OkrObjectiveService objectiveService,
            OkrKeyResultService keyResultService,
            PerfResultService perfResultService,
            KnowledgeSearchService knowledgeSearchService,
            ProjectService projectService,
            InsightMetricService insightMetricService
    ) {
        this.callerContext = callerContext;
        this.permissionResolver = permissionResolver;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.objectiveService = objectiveService;
        this.keyResultService = keyResultService;
        this.perfResultService = perfResultService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.projectService = projectService;
        this.insightMetricService = insightMetricService;
    }

    // ------------------------------------------------------------------
    // 白名单工具（全部只读）
    // ------------------------------------------------------------------

    @Tool(name = "task_query",
            description = "查询当前账号可见的任务列表（只读）。权限要求：task:task:list。"
                    + "以调用方身份执行，数据范围与 Key 绑定用户一致，不支持越权。")
    public String taskQuery(
            @ToolParam(description = "任务标题关键词，可空", required = false) String keyword,
            @ToolParam(description = "任务状态：todo / in_progress / done / pool / cancelled，可空", required = false) String status,
            @ToolParam(description = "责任人用户 ID，可空", required = false) Long assigneeId,
            @ToolParam(description = "关联关键结果 ID，可空", required = false) Long krId,
            @ToolParam(description = "返回条数，默认 20，最大 50", required = false) Integer limit
    ) {
        return run("task_query", "task:task:list", () -> {
            var page = taskService.list(new PageQuery(1, clamp(limit), null, null),
                    trim(keyword), trim(status), assigneeId, krId, null);
            return pageView(page.total(), page.rows());
        });
    }

    @Tool(name = "objective_query",
            description = "查询当前账号可见的目标（OKR）列表与进度（只读）。权限要求：okr:objective:list。"
                    + "以调用方身份执行，不支持越权。")
    public String objectiveQuery(
            @ToolParam(description = "目标标题关键词，可空", required = false) String keyword,
            @ToolParam(description = "目标负责人用户 ID，可空", required = false) Long ownerId,
            @ToolParam(description = "目标周期 ID，可空", required = false) Long periodId,
            @ToolParam(description = "返回条数，默认 20，最大 50", required = false) Integer limit
    ) {
        return run("objective_query", "okr:objective:list", () -> {
            var page = objectiveService.list(new PageQuery(1, clamp(limit), null, null),
                    periodId, null, ownerId, null, trim(keyword));
            return pageView(page.total(), page.rows());
        });
    }

    @Tool(name = "kr_query",
            description = "查询某个目标下的关键结果（KR）与其进度（只读）。权限要求：okr:kr:list。"
                    + "以调用方身份执行，不支持越权。")
    public String krQuery(
            @ToolParam(description = "目标 ID（必填）") Long objectiveId
    ) {
        return run("kr_query", "okr:kr:list", () -> {
            if (objectiveId == null) {
                throw new BusinessException("objectiveId 不能为空");
            }
            List<Map<String, Object>> rows = keyResultService.list(objectiveId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("objectiveId", objectiveId);
            data.put("total", rows == null ? 0 : rows.size());
            data.put("rows", rows == null ? List.of() : rows);
            return data;
        });
    }

    @Tool(name = "perf_query",
            description = "查询某员工在指定绩效周期的绩效结果（只读，不编造分数）。"
                    + "权限要求：perf:result:list。以调用方身份执行，不支持越权。")
    public String perfQuery(
            @ToolParam(description = "绩效周期 ID（必填）") Long cycleId,
            @ToolParam(description = "员工用户 ID，可空；为空时查询当前调用方本人", required = false) Long employeeId
    ) {
        return run("perf_query", "perf:result:list", () -> {
            if (cycleId == null) {
                throw new BusinessException("cycleId 不能为空");
            }
            McpCallerContext.Caller caller = callerContext.require();
            Long targetEmployee = employeeId == null ? caller.userId() : employeeId;
            if (targetEmployee == null) {
                throw new BusinessException("无法确定员工，请提供 employeeId");
            }
            Map<String, Object> result = perfResultService.findByEmployee(cycleId, targetEmployee);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cycleId", cycleId);
            data.put("employeeId", targetEmployee);
            data.put("found", result != null);
            data.put("result", result);
            if (result == null) {
                data.put("message", "该员工在此周期暂无绩效结果（未编造分数）");
            }
            return data;
        });
    }

    @Tool(name = "knowledge_search",
            description = "检索知识库页面并返回命中片段（只读；结果已按当前用户页面权限过滤）。"
                    + "权限要求：knowledge:page:list。以调用方身份执行，不支持越权。")
    public String knowledgeSearch(
            @ToolParam(description = "检索关键词或问题（必填）") String keyword,
            @ToolParam(description = "知识空间 ID，可空", required = false) Long spaceId,
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit
    ) {
        return run("knowledge_search", "knowledge:page:list", () -> {
            if (keyword == null || keyword.isBlank()) {
                throw new BusinessException("keyword 不能为空");
            }
            int size = limit == null ? 5 : Math.max(1, Math.min(limit, 20));
            return knowledgeSearchService.search(keyword.trim(), spaceId, null, size);
        });
    }

    @Tool(name = "project_query",
            description = "查询当前账号可见的项目列表（只读）。权限要求：project:project:list。"
                    + "以调用方身份执行，不支持越权。")
    public String projectQuery(
            @ToolParam(description = "项目名称或编号关键词，可空", required = false) String keyword,
            @ToolParam(description = "项目状态：draft / active / closed / archived，可空", required = false) String status,
            @ToolParam(description = "返回条数，默认 20，最大 50", required = false) Integer limit
    ) {
        return run("project_query", "project:project:list", () -> {
            var page = projectService.list(new PageQuery(1, clamp(limit), null, null),
                    trim(keyword), trim(status), null);
            return pageView(page.total(), page.rows());
        });
    }

    @Tool(name = "insight_metrics",
            description = "查询指标字典中的指标定义与口径（只读）。权限要求：insight:metric:list。"
                    + "以调用方身份执行，不支持越权。")
    public String insightMetrics(
            @ToolParam(description = "指标编码或名称关键词，可空", required = false) String keyword,
            @ToolParam(description = "指标类目，可空", required = false) String category,
            @ToolParam(description = "返回条数，默认 20，最大 50", required = false) Integer limit
    ) {
        return run("insight_metrics", "insight:metric:list", () -> {
            var page = insightMetricService.list(new PageQuery(1, clamp(limit), null, null),
                    trim(keyword), trim(category), null);
            return pageView(page.total(), page.rows());
        });
    }

    // ------------------------------------------------------------------
    // 鉴权与返回
    // ------------------------------------------------------------------

    private String run(String toolName, String permission, Supplier<Object> body) {
        long started = System.currentTimeMillis();
        McpCallerContext.Caller caller = callerContext.require();
        if (!permissionResolver.hasPermission(caller.username(), permission)) {
            long cost = System.currentTimeMillis() - started;
            auditService.recordMcp(toolName, identityOf(caller), 403, cost, "缺少权限：" + permission);
            throw new AccessDeniedException("缺少权限：" + permission + "（以调用方身份执行，不支持越权）");
        }
        try {
            Object data = callerContext.callAs(caller, body::get);
            auditService.recordMcp(toolName, identityOf(caller), 200,
                    System.currentTimeMillis() - started, null);
            return toJson(Map.of("tool", toolName, "allowed", true, "data", data));
        } catch (AccessDeniedException exception) {
            auditService.recordMcp(toolName, identityOf(caller), 403,
                    System.currentTimeMillis() - started, exception.getMessage());
            throw exception;
        } catch (BusinessException exception) {
            auditService.recordMcp(toolName, identityOf(caller), 400,
                    System.currentTimeMillis() - started, exception.getMessage());
            throw exception;
        }
    }

    private ApiKeyIdentity identityOf(McpCallerContext.Caller caller) {
        return new ApiKeyIdentity(caller.keyId(), caller.appId(), caller.userId(), caller.username(),
                null, Set.of());
    }

    private Map<String, Object> pageView(long total, List<Map<String, Object>> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("rows", rows == null ? List.of() : rows);
        return data;
    }

    private int clamp(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(limit, 50));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException("工具结果序列化失败：" + exception.getMessage());
        }
    }
}
