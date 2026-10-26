-- M11 AI 助手权限码、角色授权与内置提示词（V37；只追加不改 V1–V36）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>，以及 D-2026-10-26-10「menu 只表达可见性、权限码由 btn 承载」。
--
-- 权限码（3 个）：
--   ai:chat:use          使用对话助手 / 会话列表（页面 1801）
--   ai:kb:ask            知识库问答（页面 1802）
--   ai:report:generate   生成日报/周报草稿（页面 1803）
-- 说明：
--   1) V6 已建 M11 目录（1800）与 3 个页面占位菜单（1801 AI 对话 / 1802 知识问答 / 1803 日报生成），本脚本只追加
--      按钮级权限行与可见性授权，不改 V6 已发布行；
--   2) 菜单本身 permission 保持为空：授权菜单只增加可见性，不连带授予任何权限码；
--   3) 角色授权：super_admin / sys_admin 全部；biz_admin 仅对话与知识问答（不授予报告生成）；
--   4) 提示词按版本落库，应用读取「同一编码下 status=active 的最大版本」，历史版本保留可回滚。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (18011, 1801, '使用对话助手', 'btn', '', '', '', 'ai:chat:use', 1, 1, 1, 'system', 'system'),
    (18021, 1802, '知识库问答', 'btn', '', '', '', 'ai:kb:ask', 1, 1, 1, 'system', 'system'),
    (18031, 1803, '生成日报周报', 'btn', '', '', '', 'ai:report:generate', 1, 1, 1, 'system', 'system');

-- 角色授权：super_admin / sys_admin 三个权限码
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE menu_type = 'btn' AND permission IN ('ai:chat:use', 'ai:kb:ask', 'ai:report:generate');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE menu_type = 'btn' AND permission IN ('ai:chat:use', 'ai:kb:ask', 'ai:report:generate');

-- 角色授权：biz_admin 仅对话与知识问答（报告生成不授予）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu WHERE menu_type = 'btn' AND permission IN ('ai:chat:use', 'ai:kb:ask');

-- 菜单可见性（menu 本身无 permission，只授权可见、不连带权限码）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 1800), (1, 1801), (1, 1802), (1, 1803),
    (2, 1800), (2, 1801), (2, 1802), (2, 1803),
    (3, 1800), (3, 1801), (3, 1802), (3, 1803);

-- ============ 内置提示词 v1（版本化；改动用新版本，不覆盖历史）============
INSERT IGNORE INTO ai_prompt_template
    (prompt_code, prompt_name, version_no, content, scope, status, builtin, remark, create_by, update_by)
VALUES
    ('intent_system', '意图识别系统提示', 1,
     '你是 TeamFlow 工作助手的意图识别器。只输出一行 JSON：{"intent":"task_query|perf_query|okr_query|meeting_query|knowledge_qa|daily_report|weekly_report|unknown","employeeName":"","keyword":"","range":"this_week|last_week|today|this_month|"}。规则：任务相关看 task_query；绩效/评分看 perf_query；目标/关键结果看 okr_query；会议/日程看 meeting_query；知识/文档/规范/流程类问题看 knowledge_qa；日报/周报分别看 daily_report / weekly_report；无法判断时 unknown。不要编造员工姓名。',
     'global', 'active', 1, 'LLM 意图识别用；未配置模型时由规则实现兜底', 'system', 'system'),
    ('kb_answer_system', '知识问答系统提示', 1,
     '你是 TeamFlow 知识库问答助手。只能依据给定的检索片段回答，不得编造。回答结构：先给结论，再给依据要点；每个要点标注引用序号 [1][2]，引用序号必须对应检索结果顺序。若片段不足以回答，明确说“未找到依据”，并建议补充关键词或联系文档负责人。',
     'global', 'active', 1, 'RAG 问答提示；回答必须带引用溯源', 'system', 'system'),
    ('daily_report_system', '日报草稿提示', 1,
     '你是 TeamFlow 日报草稿助手。基于给定的任务、目标进度与会议记录，生成一份 Markdown 日报草稿。要求：只使用给定事实，不编造；结构包含「今日完成」「进行中」「风险与阻塞」「明日计划」「目标进度」「会议记录」；缺失信息写“待补充”，不得虚构数据；结尾注明“本内容为 AI 草稿，需人工确认后使用”。',
     'global', 'active', 1, '日报草稿生成；输出需可编辑', 'system', 'system'),
    ('weekly_report_system', '周报草稿提示', 1,
     '你是 TeamFlow 周报草稿助手。基于给定区间的任务、目标进度与会议记录，生成一份 Markdown 周报草稿。要求：只使用给定事实，不编造；结构包含「本周完成」「进行中」「问题与风险」「下周计划」「目标进度」「会议与决策」；缺失信息写“待补充”；结尾注明“本内容为 AI 草稿，需人工确认后使用”。',
     'global', 'active', 1, '周报草稿生成；输出需可编辑', 'system', 'system');
