package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * LLM 路径的 {@code @Tool} 回调集合：模型只能通过这些白名单方法取数。
 *
 * <p>每次回调都走 {@link ToolRegistry}（权限闸门 + 调用者身份），调用轨迹写入 holder，
 * 用于审计与响应回放；模型本身不直接接触 Service 或数据表。</p>
 */
public class AiLlmToolFacade {

    private final ToolRegistry registry;
    private final AiCaller caller;
    private final LlmInvocationHolder holder;

    public AiLlmToolFacade(ToolRegistry registry, AiCaller caller, LlmInvocationHolder holder) {
        this.registry = registry;
        this.caller = caller;
        this.holder = holder;
    }

    @Tool(name = "queryTasks", description = "查询某员工在指定时间范围内的任务；员工姓名可空，空表示当前用户")
    public String queryTasks(
            @ToolParam(description = "员工姓名或账号，可为空", required = false) String employeeName,
            @ToolParam(description = "时间范围：this_week/last_week/today/this_month", required = false) String range) {
        return invoke("task.query", Map.of(
                "employeeName", employeeName == null ? "" : employeeName,
                "range", range == null ? "this_week" : range));
    }

    @Tool(name = "queryPerformance", description = "查询某员工的绩效结果与最近周期")
    public String queryPerformance(
            @ToolParam(description = "员工姓名或账号") String employeeName) {
        return invoke("perf.query", Map.of("employeeName", employeeName == null ? "" : employeeName));
    }

    @Tool(name = "queryObjectives", description = "查询目标与关键结果（需要目标查看权限）")
    public String queryObjectives(
            @ToolParam(description = "员工姓名或账号，可为空", required = false) String employeeName,
            @ToolParam(description = "关键词，可为空", required = false) String keyword) {
        return invoke("okr.query", Map.of(
                "employeeName", employeeName == null ? "" : employeeName,
                "keyword", keyword == null ? "" : keyword));
    }

    @Tool(name = "queryMeetings", description = "查询指定时间范围内的会议与日程")
    public String queryMeetings(
            @ToolParam(description = "时间范围：this_week/last_week/today/this_month", required = false) String range) {
        return invoke("meeting.query", Map.of("range", range == null ? "this_week" : range));
    }

    @Tool(name = "searchKnowledge", description = "在知识库中检索页面片段，返回可引用的页面与片段")
    public String searchKnowledge(
            @ToolParam(description = "检索关键词") String keyword) {
        return invoke("knowledge.qa", Map.of("keyword", keyword == null ? "" : keyword));
    }

    @Tool(name = "generateReportDraft", description = "生成日报或周报草稿（仅草稿，不发布）")
    public String generateReportDraft(
            @ToolParam(description = "报告类型：daily 或 weekly") String type,
            @ToolParam(description = "日报日期 yyyy-MM-dd，可为空", required = false) String date) {
        return invoke("report.generate", Map.of(
                "type", type == null ? "daily" : type,
                "date", date == null ? "" : date));
    }

    private String invoke(String toolName, Map<String, Object> params) {
        ToolOutcome outcome = registry.execute(toolName, params, caller);
        holder.add(new LlmToolInvocation(toolName, params, outcome));
        return outcome.summary();
    }
}
