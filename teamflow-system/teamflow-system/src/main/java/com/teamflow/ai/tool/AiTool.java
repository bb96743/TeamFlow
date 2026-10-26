package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;

import java.util.Map;

/**
 * AI 工具契约：白名单内的只读能力，以调用者身份执行。
 *
 * <p>实现类只调用对应模块的 Service（不直连业务表），权限码由 ToolRegistry 在调用前校验。</p>
 */
public interface AiTool {

    /** 工具名（与意图路由使用的名称一致，如 task.query）。 */
    String name();

    /** 工具中文说明（展示在对话轨迹/审计中）。 */
    String label();

    /** 执行所需权限码；为空表示登录即可（如知识检索，结果仍受页面 ACL 过滤）。 */
    String permissionCode();

    ToolOutcome execute(Map<String, Object> params, AiCaller caller);
}
