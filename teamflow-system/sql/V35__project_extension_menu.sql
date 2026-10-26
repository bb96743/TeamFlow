-- M14 项目管理 · M3 扩展权限码初始化（V35；只追加不改 V1–V34）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>
-- 权限码清单（11 个）：
--   甘特/任务关联：project:gantt:list、project:tasklink:list/add/remove
--   风险登记：project:risk:list/add/edit/remove/status
--   项目复盘：project:retro:list、project:retro:archive
-- 说明：
--   1) V6 已建 M14 的 5 个页面占位菜单（2101 项目列表 / 2102 里程碑 / 2103 甘特视图 /
--      2104 风险登记 / 2105 项目复盘），本脚本只追加按钮级权限行（btn），
--      不改 V6 已发布行、不改前端路由与组件。
--   2) 遵循 D-2026-10-26-10「menu 只表达可见性、权限码一律由 btn 承载」：
--      页面 permission 保持为空，只授予可见性，不连带授予任何权限码。
--   3) 角色授权沿用本模块与近期业务模块口径：super_admin 全部；sys_admin 全部；
--      biz_admin 只读（list）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」（2103 → 21031），既有 MAX(sys_menu.id)=22013，无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 甘特视图（页面 2103）============
    (21031, 2103, '查看甘特视图', 'btn', '', '', '', 'project:gantt:list', 1, 1, 1, 'system', 'system'),
    (21032, 2103, '查询项目任务关联', 'btn', '', '', '', 'project:tasklink:list', 2, 1, 1, 'system', 'system'),
    (21033, 2103, '关联项目任务', 'btn', '', '', '', 'project:tasklink:add', 3, 1, 1, 'system', 'system'),
    (21034, 2103, '取消项目任务关联', 'btn', '', '', '', 'project:tasklink:remove', 4, 1, 1, 'system', 'system'),
    -- ============ 风险登记（页面 2104）============
    (21041, 2104, '查询风险', 'btn', '', '', '', 'project:risk:list', 1, 1, 1, 'system', 'system'),
    (21042, 2104, '登记风险', 'btn', '', '', '', 'project:risk:add', 2, 1, 1, 'system', 'system'),
    (21043, 2104, '编辑风险', 'btn', '', '', '', 'project:risk:edit', 3, 1, 1, 'system', 'system'),
    (21044, 2104, '删除风险', 'btn', '', '', '', 'project:risk:remove', 4, 1, 1, 'system', 'system'),
    (21045, 2104, '风险状态流转', 'btn', '', '', '', 'project:risk:status', 5, 1, 1, 'system', 'system'),
    -- ============ 项目复盘（页面 2105）============
    (21051, 2105, '查询项目复盘', 'btn', '', '', '', 'project:retro:list', 1, 1, 1, 'system', 'system'),
    (21052, 2105, '归档项目复盘', 'btn', '', '', '', 'project:retro:archive', 2, 1, 1, 'system', 'system');

-- 超级管理员（role 1）持有全部扩展权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 21031 AND 21052 AND menu_type = 'btn';

-- 系统管理员（role 2）持有本模块扩展全部按钮
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 21031 AND 21052 AND menu_type = 'btn';

-- 业务管理员（role 3）只读：甘特、任务关联查询、风险查询、复盘查询
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu WHERE menu_type = 'btn'
  AND permission IN ('project:gantt:list', 'project:tasklink:list', 'project:risk:list', 'project:retro:list');

-- 菜单可见性（menu 本身 permission 为空，只授权可见、不连带权限码；遵循 D-10 结论一）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 2100), (1, 2103), (1, 2104), (1, 2105),
    (2, 2100), (2, 2103), (2, 2104), (2, 2105),
    (3, 2100), (3, 2103), (3, 2104), (3, 2105);
