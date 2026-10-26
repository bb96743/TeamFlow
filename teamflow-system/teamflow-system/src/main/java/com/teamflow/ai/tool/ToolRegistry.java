package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.service.AiPermissionService;
import com.teamflow.common.exception.BusinessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表与执行闸门。
 *
 * <p>执行前统一校验调用者权限（R2）：无权限时返回 {@code allowed=false} 的可读结果，
 * 不抛 500、不带出任何业务数据；工具内部异常也收敛为可读失败，不泄露堆栈。</p>
 */
@Service
public class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final AiPermissionService permissionService;

    public ToolRegistry(List<AiTool> toolList, AiPermissionService permissionService) {
        this.permissionService = permissionService;
        for (AiTool tool : toolList) {
            this.tools.put(tool.name(), tool);
        }
    }

    public Collection<AiTool> all() {
        return tools.values();
    }

    public AiTool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public ToolOutcome execute(String name, Map<String, Object> params, AiCaller caller) {
        AiTool tool = get(name);
        if (tool == null) {
            return ToolOutcome.error(name == null ? "unknown" : name, params, null, "不支持的工具请求：" + name);
        }
        if (!permissionService.has(caller, tool.permissionCode())) {
            String reason = "无权限使用「" + tool.label() + "」（缺少 " + tool.permissionCode()
                    + "），已拒绝执行，未读取任何数据";
            return ToolOutcome.denied(tool.name(), params, tool.permissionCode(), reason);
        }
        try {
            return tool.execute(params == null ? Map.of() : params, caller);
        } catch (AccessDeniedException exception) {
            return ToolOutcome.denied(tool.name(), params, tool.permissionCode(), "无权限查看该数据，已拒绝执行");
        } catch (BusinessException exception) {
            return ToolOutcome.error(tool.name(), params, tool.permissionCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolOutcome.error(tool.name(), params, tool.permissionCode(), "工具执行失败，请稍后重试或换一种问法");
        }
    }
}
