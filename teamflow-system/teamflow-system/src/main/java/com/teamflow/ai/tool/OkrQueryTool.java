package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.okr.service.OkrObjectiveService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查询目标与关键结果（okr.query）。
 *
 * <p>权限码 {@code okr:objective:list} 由注册表统一校验：没有 OKR 权限的账号（如仅有任务权限）
 * 会在进入工具前被拒绝，返回可读原因且不读取任何目标数据。</p>
 */
@Component
public class OkrQueryTool implements AiTool {

    private final OkrObjectiveService objectiveService;
    private final AiToolSupport support;

    public OkrQueryTool(OkrObjectiveService objectiveService, AiToolSupport support) {
        this.objectiveService = objectiveService;
        this.support = support;
    }

    @Override
    public String name() {
        return "okr.query";
    }

    @Override
    public String label() {
        return "查询目标";
    }

    @Override
    public String permissionCode() {
        return "okr:objective:list";
    }

    @Override
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        String ownerName = support.text(params, "employeeName", "");
        String keyword = support.text(params, "keyword", "");
        Long ownerId = null;
        String ownerLabel = "当前可见范围";
        if (!ownerName.isBlank()) {
            Map<String, Object> user = support.findUser(ownerName);
            if (user == null) {
                return ToolOutcome.ok(name(), params, permissionCode(),
                        "未找到员工「" + ownerName + "」，请确认姓名或账号；未读取任何目标数据。",
                        Map.of("employee", ownerName, "objectives", List.of()));
            }
            ownerId = support.userId(user);
            ownerLabel = support.displayName(user);
        }

        PageResult<Map<String, Object>> page = objectiveService.list(
                new PageQuery(1, 30, null, null), null, null, ownerId, null, keyword.isBlank() ? null : keyword);
        List<Map<String, Object>> rows = page.rows() == null ? List.of() : page.rows();
        StringBuilder summary = new StringBuilder();
        summary.append("目标范围：").append(ownerLabel);
        if (!keyword.isBlank()) {
            summary.append("｜关键词：").append(keyword);
        }
        summary.append("｜共 ").append(page.total()).append(" 个目标：");
        int index = 1;
        for (Map<String, Object> row : rows.stream().limit(10).toList()) {
            summary.append("\n").append(index++).append(". ")
                    .append(support.text(row, "objectiveTitle"))
                    .append("（层级：").append(levelLabel(support.text(row, "level")))
                    .append("，进度：").append(progressText(row.get("progress")))
                    .append("%，负责人：").append(support.text(row, "ownerName")).append("）");
        }
        if (rows.isEmpty()) {
            summary.append("\n（当前可见范围内没有目标）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owner", ownerLabel);
        data.put("keyword", keyword);
        data.put("total", page.total());
        data.put("objectives", rows.stream().limit(20).toList());
        return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data);
    }

    private String levelLabel(String level) {
        return switch (level == null ? "" : level) {
            case "company" -> "公司级";
            case "dept" -> "部门级";
            case "personal" -> "个人级";
            default -> level;
        };
    }

    private String progressText(Object value) {
        if (value == null) {
            return "—";
        }
        return String.valueOf(value);
    }
}
