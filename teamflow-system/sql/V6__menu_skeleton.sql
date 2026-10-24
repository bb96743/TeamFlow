-- M03–M14 菜单骨架（可见性交付，非功能实现；追加脚本，不改 V1–V5）
-- 依据：docs/需求总计划.md §5.3.4「菜单骨架（可见性交付，非需求实现）」
--   · 覆盖 M03–M14 共 12 个有 UI 的模块：12 个目录 + 42 个页面；M01 工程与交付为技术模块、无 UI，不建菜单。
--   · 页面形态统一为「占位页」：前端 component 一律以 placeholder/ 开头，由 Placeholder.vue 渲染，
--     并如实标注「规划中 + 所属里程碑 + 模块职责 + 计划功能」；本脚本不新增任何业务表、不写入任何业务数据。
--   · 菜单存在 ≠ 功能完成：各模块状态仍按真实代码进度标注（§2.2 不变）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不会新增行、不会报错。
-- 权限：menu 级不写 permission（走菜单授权）；超级管理员由 AuthService.selectAllRouteMenus() 自动全量可见，
--   因此本脚本不写 sys_role_menu（V2/V4 的后续变更同样不在此处理）。
-- ID 段位：沿用既有约定，目录取整百（1000/1100/…），页面取 +1…+n，与 M00(100–)、M02(400–) 不冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ M03 任务与工作流（里程碑 M1）============
    (1000, 0, '任务与工作流', 'dir', 'board', '/task', '', '', 6, 1, 1, 'system', 'system'),
    (1001, 1000, '任务列表', 'menu', 'table', '/task/list', 'placeholder/task/list/index', '', 1, 1, 1, 'system', 'system'),
    (1002, 1000, '任务看板', 'menu', 'board', '/task/board', 'placeholder/task/board/index', '', 2, 1, 1, 'system', 'system'),
    (1003, 1000, '流程定义', 'menu', 'command', '/task/flow-def', 'placeholder/task/flow-def/index', '', 3, 1, 1, 'system', 'system'),
    (1004, 1000, '审批中心', 'menu', 'check', '/task/approvals', 'placeholder/task/approvals/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M04 团队与成员（里程碑 M1）============
    (1100, 0, '团队与成员', 'dir', 'users', '/team', '', '', 7, 1, 1, 'system', 'system'),
    (1101, 1100, '团队管理', 'menu', 'users', '/team/list', 'placeholder/team/list/index', '', 1, 1, 1, 'system', 'system'),
    (1102, 1100, '成员负载', 'menu', 'chart', '/team/workload', 'placeholder/team/workload/index', '', 2, 1, 1, 'system', 'system'),

    -- ============ M05 绩效（里程碑 M1）============
    (1200, 0, '绩效', 'dir', 'layers', '/perf', '', '', 8, 1, 1, 'system', 'system'),
    (1201, 1200, '绩效周期', 'menu', 'clock', '/perf/cycle', 'placeholder/perf/cycle/index', '', 1, 1, 1, 'system', 'system'),
    (1202, 1200, '指标模板', 'menu', 'matrix', '/perf/template', 'placeholder/perf/template/index', '', 2, 1, 1, 'system', 'system'),
    (1203, 1200, '绩效评分', 'menu', 'edit', '/perf/score', 'placeholder/perf/score/index', '', 3, 1, 1, 'system', 'system'),
    (1204, 1200, '结果归档', 'menu', 'file', '/perf/result', 'placeholder/perf/result/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M06 日程与会议（里程碑 M3）============
    (1300, 0, '日程与会议', 'dir', 'clock', '/meeting', '', '', 9, 1, 1, 'system', 'system'),
    (1301, 1300, '日程日历', 'menu', 'clock', '/meeting/calendar', 'placeholder/meeting/calendar/index', '', 1, 1, 1, 'system', 'system'),
    (1302, 1300, '会议室', 'menu', 'home', '/meeting/room', 'placeholder/meeting/room/index', '', 2, 1, 1, 'system', 'system'),
    (1303, 1300, '会议管理', 'menu', 'users', '/meeting/list', 'placeholder/meeting/list/index', '', 3, 1, 1, 'system', 'system'),
    (1304, 1300, '纪要模板', 'menu', 'file', '/meeting/template', 'placeholder/meeting/template/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M07 知识与文档（里程碑 M3）============
    (1400, 0, '知识与文档', 'dir', 'file', '/knowledge', '', '', 10, 1, 1, 'system', 'system'),
    (1401, 1400, '知识空间', 'menu', 'layers', '/knowledge/space', 'placeholder/knowledge/space/index', '', 1, 1, 1, 'system', 'system'),
    (1402, 1400, '文档中心', 'menu', 'file', '/knowledge/page', 'placeholder/knowledge/page/index', '', 2, 1, 1, 'system', 'system'),
    (1403, 1400, '全文检索', 'menu', 'search', '/knowledge/search', 'placeholder/knowledge/search/index', '', 3, 1, 1, 'system', 'system'),

    -- ============ M08 招聘（里程碑 M2）============
    (1500, 0, '招聘', 'dir', 'user', '/recruit', '', '', 11, 1, 1, 'system', 'system'),
    (1501, 1500, '职位管理', 'menu', 'post', '/recruit/job', 'placeholder/recruit/job/index', '', 1, 1, 1, 'system', 'system'),
    (1502, 1500, '候选人', 'menu', 'users', '/recruit/candidate', 'placeholder/recruit/candidate/index', '', 2, 1, 1, 'system', 'system'),
    (1503, 1500, '面试安排', 'menu', 'clock', '/recruit/interview', 'placeholder/recruit/interview/index', '', 3, 1, 1, 'system', 'system'),
    (1504, 1500, 'Offer 管理', 'menu', 'edit', '/recruit/offer', 'placeholder/recruit/offer/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M09 协作与消息（里程碑 M3）============
    (1600, 0, '协作与消息', 'dir', 'mail', '/collab', '', '', 12, 1, 1, 'system', 'system'),
    (1601, 1600, '站内信', 'menu', 'mail', '/collab/message', 'placeholder/collab/message/index', '', 1, 1, 1, 'system', 'system'),
    (1602, 1600, '公告', 'menu', 'bell', '/collab/announcement', 'placeholder/collab/announcement/index', '', 2, 1, 1, 'system', 'system'),
    (1603, 1600, '动态流', 'menu', 'activity', '/collab/feed', 'placeholder/collab/feed/index', '', 3, 1, 1, 'system', 'system'),

    -- ============ M10 个人效率（里程碑 M1）============
    (1700, 0, '个人效率', 'dir', 'home', '/personal', '', '', 13, 1, 1, 'system', 'system'),
    (1701, 1700, '待办收件箱', 'menu', 'bell', '/personal/todo', 'placeholder/personal/todo/index', '', 1, 1, 1, 'system', 'system'),
    (1702, 1700, '工时记录', 'menu', 'clock', '/personal/time', 'placeholder/personal/time/index', '', 2, 1, 1, 'system', 'system'),
    (1703, 1700, '工作复盘', 'menu', 'edit', '/personal/review', 'placeholder/personal/review/index', '', 3, 1, 1, 'system', 'system'),
    (1704, 1700, '效能画像', 'menu', 'chart', '/personal/profile', 'placeholder/personal/profile/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M11 AI 助手（里程碑 M4）============
    (1800, 0, 'AI 助手', 'dir', 'activity', '/ai', '', '', 14, 1, 1, 'system', 'system'),
    (1801, 1800, 'AI 对话', 'menu', 'activity', '/ai/chat', 'placeholder/ai/chat/index', '', 1, 1, 1, 'system', 'system'),
    (1802, 1800, '知识问答', 'menu', 'search', '/ai/qa', 'placeholder/ai/qa/index', '', 2, 1, 1, 'system', 'system'),
    (1803, 1800, '日报生成', 'menu', 'file', '/ai/report', 'placeholder/ai/report/index', '', 3, 1, 1, 'system', 'system'),

    -- ============ M12 集成与开放（里程碑 M4）============
    (1900, 0, '集成与开放', 'dir', 'key', '/integration', '', '', 15, 1, 1, 'system', 'system'),
    (1901, 1900, '第三方集成', 'menu', 'grid', '/integration/app', 'placeholder/integration/app/index', '', 1, 1, 1, 'system', 'system'),
    (1902, 1900, '开放密钥', 'menu', 'key', '/integration/key', 'placeholder/integration/key/index', '', 2, 1, 1, 'system', 'system'),

    -- ============ M13 统计与洞察（里程碑 M2）============
    (2000, 0, '统计与洞察', 'dir', 'chart', '/insight', '', '', 16, 1, 1, 'system', 'system'),
    (2001, 2000, '任务总览', 'menu', 'dashboard', '/insight/task', 'placeholder/insight/task/index', '', 1, 1, 1, 'system', 'system'),
    (2002, 2000, '团队负载', 'menu', 'users', '/insight/load', 'placeholder/insight/load/index', '', 2, 1, 1, 'system', 'system'),
    (2003, 2000, '跨域看板', 'menu', 'chart', '/insight/cross', 'placeholder/insight/cross/index', '', 3, 1, 1, 'system', 'system'),
    (2004, 2000, '指标口径', 'menu', 'matrix', '/insight/metric', 'placeholder/insight/metric/index', '', 4, 1, 1, 'system', 'system'),

    -- ============ M14 项目管理（里程碑 M2）============
    (2100, 0, '项目管理', 'dir', 'grid', '/project', '', '', 17, 1, 1, 'system', 'system'),
    (2101, 2100, '项目列表', 'menu', 'grid', '/project/list', 'placeholder/project/list/index', '', 1, 1, 1, 'system', 'system'),
    (2102, 2100, '里程碑', 'menu', 'clock', '/project/milestone', 'placeholder/project/milestone/index', '', 2, 1, 1, 'system', 'system'),
    (2103, 2100, '甘特视图', 'menu', 'table', '/project/gantt', 'placeholder/project/gantt/index', '', 3, 1, 1, 'system', 'system'),
    (2104, 2100, '风险登记', 'menu', 'alert', '/project/risk', 'placeholder/project/risk/index', '', 4, 1, 1, 'system', 'system'),
    (2105, 2100, '项目复盘', 'menu', 'file', '/project/retro', 'placeholder/project/retro/index', '', 5, 1, 1, 'system', 'system');
