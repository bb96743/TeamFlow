package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.perf.service.PerfCycleService;
import com.teamflow.perf.service.PerfResultService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查询绩效结果（perf.query）。
 *
 * <p>按员工定位「最近一个有结果的绩效周期」，调用 M05 的结果查询 Service；
 * 无结果时明确说明并返回可见周期，不编造分数。</p>
 */
@Component
public class PerfQueryTool implements AiTool {

    private final PerfCycleService cycleService;
    private final PerfResultService resultService;
    private final AiToolSupport support;

    public PerfQueryTool(PerfCycleService cycleService, PerfResultService resultService, AiToolSupport support) {
        this.cycleService = cycleService;
        this.resultService = resultService;
        this.support = support;
    }

    @Override
    public String name() {
        return "perf.query";
    }

    @Override
    public String label() {
        return "查询绩效";
    }

    @Override
    public String permissionCode() {
        return "perf:result:list";
    }

    @Override
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        String employeeName = support.text(params, "employeeName", "");
        if (employeeName.isBlank()) {
            return ToolOutcome.error(name(), params, permissionCode(), "请补充要查询绩效的员工姓名，例如「查张三的绩效」");
        }
        Map<String, Object> user = support.findUser(employeeName);
        if (user == null) {
            return ToolOutcome.ok(name(), params, permissionCode(),
                    "未找到员工「" + employeeName + "」，请确认姓名或账号；未读取任何绩效数据。",
                    Map.of("employee", employeeName));
        }
        Long employeeId = support.userId(user);
        String employeeLabel = support.displayName(user);

        PageResult<Map<String, Object>> cycles = cycleService.list(new PageQuery(1, 50, null, null), null, null);
        List<Map<String, Object>> cycleRows = cycles.rows() == null ? List.of() : cycles.rows();
        Map<String, Object> matchedCycle = null;
        Map<String, Object> matchedResult = null;
        for (Map<String, Object> cycle : cycleRows) {
            Long cycleId = longOf(cycle.get("id"));
            if (cycleId == null) {
                continue;
            }
            Map<String, Object> result = resultService.findByEmployee(cycleId, employeeId);
            if (result != null) {
                matchedCycle = cycle;
                matchedResult = result;
                break;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employee", employeeLabel);
        data.put("employeeId", employeeId);
        if (matchedResult == null) {
            StringBuilder summary = new StringBuilder();
            summary.append("未找到「").append(employeeLabel).append("」的绩效结果（本周期可能尚未归档）；");
            summary.append("当前可见绩效周期：");
            if (cycleRows.isEmpty()) {
                summary.append("无");
            } else {
                int index = 1;
                for (Map<String, Object> cycle : cycleRows.stream().limit(5).toList()) {
                    summary.append(index++ == 1 ? "" : "、")
                            .append(support.text(cycle, "cycleName"))
                            .append("（").append(support.text(cycle, "status")).append("）");
                }
            }
            summary.append("。未读取到绩效明细。");
            data.put("cycles", cycleRows.stream().limit(5).toList());
            return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data);
        }

        String cycleName = support.text(matchedCycle, "cycleName");
        String cycleStatus = support.text(matchedCycle, "status");
        String totalScore = support.text(matchedResult, "totalScore");
        String selfScore = support.text(matchedResult, "selfScore");
        String resultStatus = support.text(matchedResult, "status");
        String archiveTime = support.text(matchedResult, "archiveTime");
        StringBuilder summary = new StringBuilder();
        summary.append("员工：").append(employeeLabel)
                .append("｜周期：").append(cycleName).append("（").append(cycleStatus).append("）")
                .append("｜加权总分：").append(totalScore.isBlank() ? "—" : totalScore);
        if (!selfScore.isBlank()) {
            summary.append("｜自评口径：").append(selfScore);
        }
        summary.append("｜结果状态：").append(support.perfStatusLabel(resultStatus));
        if (!archiveTime.isBlank()) {
            summary.append("（归档于 ").append(archiveTime).append("）");
        }
        data.put("cycle", matchedCycle);
        data.put("result", matchedResult);
        return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data);
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
