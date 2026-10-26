package com.teamflow.integration.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.R;
import com.teamflow.insight.service.InsightMetricService;
import com.teamflow.integration.service.OpenApiScopeService;
import com.teamflow.knowledge.service.KnowledgeSearchService;
import com.teamflow.okr.service.OkrKeyResultService;
import com.teamflow.okr.service.OkrObjectiveService;
import com.teamflow.project.service.ProjectService;
import com.teamflow.project.service.ProjectTaskLinkService;
import com.teamflow.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 开放 API v1（需求 2.4.3）：对外一律走 {@code /open/v1/**}，与内部 {@code /api/**} 物理隔离。
 *
 * <p>鉴权：{@code X-API-Key} → 绑定用户 + 权限范围；数据范围仍按绑定用户计算。
 * 首期<b>只读 + 少量白名单写</b>（仅任务认领一个写操作，便于外部系统回写执行状态；
 * 不提供任务创建/修改/删除等写入能力）。全部接口经业务模块 Service 取数，不做业务实现。</p>
 *
 * <p>OpenAPI 文档由工程既有的 springdoc/Knife4j 自动收录（该控制器全部方法会出现在
 * {@code /v3/api-docs} 的 paths 中）。</p>
 */
@RestController
@RequestMapping("/open/v1")
public class OpenApiController {

    private final OpenApiScopeService scopeService;
    private final TaskService taskService;
    private final OkrObjectiveService objectiveService;
    private final OkrKeyResultService keyResultService;
    private final ProjectService projectService;
    private final ProjectTaskLinkService taskLinkService;
    private final InsightMetricService insightMetricService;
    private final KnowledgeSearchService knowledgeSearchService;

    public OpenApiController(
            OpenApiScopeService scopeService,
            TaskService taskService,
            OkrObjectiveService objectiveService,
            OkrKeyResultService keyResultService,
            ProjectService projectService,
            ProjectTaskLinkService taskLinkService,
            InsightMetricService insightMetricService,
            KnowledgeSearchService knowledgeSearchService
    ) {
        this.scopeService = scopeService;
        this.taskService = taskService;
        this.objectiveService = objectiveService;
        this.keyResultService = keyResultService;
        this.projectService = projectService;
        this.taskLinkService = taskLinkService;
        this.insightMetricService = insightMetricService;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    // ------------------------------------------------------------------
    // 只读
    // ------------------------------------------------------------------

    @GetMapping("/tasks")
    public R<Object> tasks(HttpServletRequest request,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long assigneeId,
                           @RequestParam(required = false) Long krId,
                           @RequestParam(required = false) Integer pageNum,
                           @RequestParam(required = false) Integer pageSize) {
        scopeService.require(request, "task:task:list");
        return R.success(taskService.list(new PageQuery(pageNum, pageSize, null, null),
                keyword, status, assigneeId, krId, null));
    }

    @GetMapping("/tasks/{id}")
    public R<Object> task(HttpServletRequest request, @PathVariable Long id) {
        scopeService.require(request, "task:task:list");
        return R.success(taskService.detail(id));
    }

    @GetMapping("/objectives")
    public R<Object> objectives(HttpServletRequest request,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(required = false) Long periodId,
                                @RequestParam(required = false) Long ownerId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Integer pageNum,
                                @RequestParam(required = false) Integer pageSize) {
        scopeService.require(request, "okr:objective:list");
        return R.success(objectiveService.list(new PageQuery(pageNum, pageSize, null, null),
                periodId, null, ownerId, status, keyword));
    }

    @GetMapping("/objectives/{id}/krs")
    public R<Object> keyResults(HttpServletRequest request, @PathVariable Long id) {
        scopeService.require(request, "okr:kr:list");
        return R.success(keyResultService.list(id));
    }

    @GetMapping("/projects")
    public R<Object> projects(HttpServletRequest request,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) Integer pageNum,
                              @RequestParam(required = false) Integer pageSize) {
        scopeService.require(request, "project:project:list");
        return R.success(projectService.list(new PageQuery(pageNum, pageSize, null, null),
                keyword, status, null));
    }

    @GetMapping("/projects/{id}/gantt")
    public R<Object> projectGantt(HttpServletRequest request, @PathVariable Long id) {
        scopeService.require(request, "project:gantt:list");
        return R.success(taskLinkService.gantt(id));
    }

    @GetMapping("/insights/metrics")
    public R<Object> metrics(HttpServletRequest request,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) Integer pageNum,
                             @RequestParam(required = false) Integer pageSize) {
        scopeService.require(request, "insight:metric:list");
        return R.success(insightMetricService.list(new PageQuery(pageNum, pageSize, null, null),
                keyword, category, null));
    }

    @GetMapping("/knowledge/search")
    public R<Object> knowledgeSearch(HttpServletRequest request,
                                     @RequestParam String keyword,
                                     @RequestParam(required = false) Long spaceId,
                                     @RequestParam(required = false) Integer limit) {
        scopeService.require(request, "knowledge:page:list");
        int size = limit == null ? 5 : Math.max(1, Math.min(limit, 20));
        return R.success(knowledgeSearchService.search(keyword, spaceId, null, size));
    }

    // ------------------------------------------------------------------
    // 少量白名单写（仅任务认领）
    // ------------------------------------------------------------------

    @PostMapping("/tasks/{id}/claim")
    public R<Object> claimTask(HttpServletRequest request, @PathVariable Long id) {
        scopeService.require(request, "task:task:assign");
        return R.success(taskService.claim(id));
    }

    /** 开放能力清单（仅返回公开元数据，不返回任何业务数据）。 */
    @GetMapping("/capabilities")
    public R<List<Map<String, Object>>> capabilities(HttpServletRequest request) {
        scopeService.require(request, "task:task:list");
        return R.success(List.of(
                Map.of("path", "/open/v1/tasks", "method", "GET", "permission", "task:task:list", "readOnly", true),
                Map.of("path", "/open/v1/tasks/{id}", "method", "GET", "permission", "task:task:list", "readOnly", true),
                Map.of("path", "/open/v1/objectives", "method", "GET", "permission", "okr:objective:list", "readOnly", true),
                Map.of("path", "/open/v1/objectives/{id}/krs", "method", "GET", "permission", "okr:kr:list", "readOnly", true),
                Map.of("path", "/open/v1/projects", "method", "GET", "permission", "project:project:list", "readOnly", true),
                Map.of("path", "/open/v1/projects/{id}/gantt", "method", "GET", "permission", "project:gantt:list", "readOnly", true),
                Map.of("path", "/open/v1/insights/metrics", "method", "GET", "permission", "insight:metric:list", "readOnly", true),
                Map.of("path", "/open/v1/knowledge/search", "method", "GET", "permission", "knowledge:page:list", "readOnly", true),
                Map.of("path", "/open/v1/tasks/{id}/claim", "method", "POST", "permission", "task:task:assign", "readOnly", false)
        ));
    }
}
