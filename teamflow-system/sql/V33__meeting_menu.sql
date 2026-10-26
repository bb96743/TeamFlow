-- M06 日程与会议（M3-C）权限码与基础数据初始化（V33；只追加不改 V1–V32）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>；D-2026-10-26-10「menu 只表达可见性、权限码一律由 btn 承载」。
--
-- 权限码清单（19 个）：
--   meeting:event:list/add/edit/remove        日程（1301）
--   meeting:room:list/add/edit/remove         会议室（1302）
--   meeting:booking:list/add/cancel           会议室预定与冲突检测（1302）
--   meeting:meeting:list/add/edit/remove      会议、改期、取消、结束、纪要归档（1303）
--   meeting:template:list/add/edit/remove     会议模板（1304）
-- 说明：
--   1) V6 已建 M06 的目录（1300）与 4 个页面占位菜单（1301–1304）；本脚本只追加按钮级权限行，
--      不改 V6 已发布行；菜单本身 permission 保持为空（授权菜单只增加可见性，不连带权限码）。
--   2) 角色授权沿用 M3 批次口径：super_admin 全部；sys_admin 持本模块写权限；biz_admin 只读。
--   3) 会议邀请/改期/取消通知与纪要待办分别经 M09 / M10 的服务级入口，本模块不落任何消息/待办表。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」（1301 → 13011、1302 → 13021、1303 → 13031、1304 → 13041）。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 日程日历（页面 1301）============
    (13011, 1301, '查询日程', 'btn', '', '', '', 'meeting:event:list', 1, 1, 1, 'system', 'system'),
    (13012, 1301, '新增日程', 'btn', '', '', '', 'meeting:event:add', 2, 1, 1, 'system', 'system'),
    (13013, 1301, '编辑日程', 'btn', '', '', '', 'meeting:event:edit', 3, 1, 1, 'system', 'system'),
    (13014, 1301, '取消日程', 'btn', '', '', '', 'meeting:event:remove', 4, 1, 1, 'system', 'system'),
    -- ============ 会议室 / 预定（页面 1302）============
    (13021, 1302, '查询会议室', 'btn', '', '', '', 'meeting:room:list', 1, 1, 1, 'system', 'system'),
    (13022, 1302, '新增会议室', 'btn', '', '', '', 'meeting:room:add', 2, 1, 1, 'system', 'system'),
    (13023, 1302, '编辑会议室', 'btn', '', '', '', 'meeting:room:edit', 3, 1, 1, 'system', 'system'),
    (13024, 1302, '删除会议室', 'btn', '', '', '', 'meeting:room:remove', 4, 1, 1, 'system', 'system'),
    (13025, 1302, '查询预定', 'btn', '', '', '', 'meeting:booking:list', 5, 1, 1, 'system', 'system'),
    (13026, 1302, '新增预定', 'btn', '', '', '', 'meeting:booking:add', 6, 1, 1, 'system', 'system'),
    (13027, 1302, '取消预定', 'btn', '', '', '', 'meeting:booking:cancel', 7, 1, 1, 'system', 'system'),
    -- ============ 会议管理（页面 1303）============
    (13031, 1303, '查询会议', 'btn', '', '', '', 'meeting:meeting:list', 1, 1, 1, 'system', 'system'),
    (13032, 1303, '新增会议', 'btn', '', '', '', 'meeting:meeting:add', 2, 1, 1, 'system', 'system'),
    (13033, 1303, '编辑会议', 'btn', '', '', '', 'meeting:meeting:edit', 3, 1, 1, 'system', 'system'),
    (13034, 1303, '删除会议', 'btn', '', '', '', 'meeting:meeting:remove', 4, 1, 1, 'system', 'system'),
    -- ============ 纪要模板（页面 1304）============
    (13041, 1304, '查询会议模板', 'btn', '', '', '', 'meeting:template:list', 1, 1, 1, 'system', 'system'),
    (13042, 1304, '新增会议模板', 'btn', '', '', '', 'meeting:template:add', 2, 1, 1, 'system', 'system'),
    (13043, 1304, '编辑会议模板', 'btn', '', '', '', 'meeting:template:edit', 3, 1, 1, 'system', 'system'),
    (13044, 1304, '删除会议模板', 'btn', '', '', '', 'meeting:template:remove', 4, 1, 1, 'system', 'system');

-- 超级管理员（role 1）持有本模块全部权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 13011 AND 13014 OR id BETWEEN 13021 AND 13027
   OR id BETWEEN 13031 AND 13034 OR id BETWEEN 13041 AND 13044;

-- 系统管理员（role 2）持有会议模块全部按钮（沿用 M3 批次授权口径）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 13011 AND 13014 OR id BETWEEN 13021 AND 13027
   OR id BETWEEN 13031 AND 13034 OR id BETWEEN 13041 AND 13044;

-- 业务管理员（role 3）只读：日程 / 会议室 / 预定 / 会议 / 模板查询
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn'
  AND permission IN ('meeting:event:list', 'meeting:room:list', 'meeting:booking:list',
                     'meeting:meeting:list', 'meeting:template:list');

-- 菜单可见性（menu 本身无 permission，只授权可见、不连带权限码）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 1300), (1, 1301), (1, 1302), (1, 1303), (1, 1304),
    (2, 1300), (2, 1301), (2, 1302), (2, 1303), (2, 1304),
    (3, 1300), (3, 1301), (3, 1302), (3, 1303), (3, 1304);

-- ============ 内置会议模板（常规 / 目标校准会 / 复盘会）============
INSERT IGNORE INTO meeting_template
    (template_code, template_name, template_type, description, agenda_template, default_duration_minutes,
     status, builtin, sort, create_by, update_by)
VALUES
    ('normal', '常规会议', 'normal', '周会/例会等常规沟通会议，默认 60 分钟。',
     '## 议程\n1. 上周进展与数据回顾\n2. 本周计划与风险\n3. 需要协同的事项\n4. 结论与待办（责任人 + 截止时间）',
     60, 'active', 1, 1, 'system', 'system'),
    ('okr_calibration', '目标校准会', 'okr_calibration', '面向目标对齐与进度校准的专项会议，衔接待办事项。',
     '## 议程\n1. 周期目标与关键结果进度回顾（只读目标数据）\n2. 对齐关系与断点检查\n3. 未达成风险与应对\n4. 校准结论与待办（责任人 + 截止时间）',
     90, 'active', 1, 2, 'system', 'system'),
    ('retro', '项目复盘会', 'retro', '项目结项复盘，结论归档到知识模块。',
     '## 议程\n1. 项目目标与结果对照\n2. 做得好的（可复用经验）\n3. 待改进的（根因）\n4. 改进措施与责任人\n5. 复盘结论（归档为知识页面）',
     90, 'active', 1, 3, 'system', 'system');

-- ============ 默认会议室（便于演示与联调，可按需停用）============
INSERT IGNORE INTO meeting_room
    (room_name, room_code, location, capacity, equipment, status, sort, remark, create_by, update_by)
VALUES
    ('A-301 研发会议室', 'A-301', '3 层东侧', 12, '投影,视频会议,白板', 'available', 1, '研发团队主会议室', 'system', 'system'),
    ('B-201 洽谈室', 'B-201', '2 层西侧', 6, '投屏,白板', 'available', 2, '面试与洽谈使用', 'system', 'system');
