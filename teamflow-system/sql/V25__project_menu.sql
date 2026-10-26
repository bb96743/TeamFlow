-- M14 项目管理权限码初始化（V25；只追加不改 V1–V24；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单（12 个）：
--   project:project:list / add / edit / remove / status
--   project:member:list / add / remove
--   project:milestone:list / add / edit / remove
-- 说明：V6 已建 M14 的 5 个页面占位菜单
--       （2101 项目列表 / 2102 里程碑 / 2103 甘特视图 / 2104 风险登记 / 2105 项目复盘），
--       本脚本只**追加按钮级权限行**（btn），不改 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
--       2103/2104/2105 属 M3 排期（甘特、风险、复盘），本期不落权限码。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 项目列表（页面 2101）============
    (21011, 2101, '查询项目', 'btn', '', '', '', 'project:project:list', 1, 1, 1, 'system', 'system'),
    (21012, 2101, '项目立项', 'btn', '', '', '', 'project:project:add', 2, 1, 1, 'system', 'system'),
    (21013, 2101, '编辑项目', 'btn', '', '', '', 'project:project:edit', 3, 1, 1, 'system', 'system'),
    (21014, 2101, '删除项目', 'btn', '', '', '', 'project:project:remove', 4, 1, 1, 'system', 'system'),
    (21015, 2101, '项目状态流转', 'btn', '', '', '', 'project:project:status', 5, 1, 1, 'system', 'system'),
    (21016, 2101, '查询项目成员', 'btn', '', '', '', 'project:member:list', 6, 1, 1, 'system', 'system'),
    (21017, 2101, '添加项目成员', 'btn', '', '', '', 'project:member:add', 7, 1, 1, 'system', 'system'),
    (21018, 2101, '移出项目成员', 'btn', '', '', '', 'project:member:remove', 8, 1, 1, 'system', 'system'),
    -- ============ 里程碑（页面 2102）============
    (21021, 2102, '查询里程碑', 'btn', '', '', '', 'project:milestone:list', 1, 1, 1, 'system', 'system'),
    (21022, 2102, '新增里程碑', 'btn', '', '', '', 'project:milestone:add', 2, 1, 1, 'system', 'system'),
    (21023, 2102, '编辑里程碑', 'btn', '', '', '', 'project:milestone:edit', 3, 1, 1, 'system', 'system'),
    (21024, 2102, '删除里程碑', 'btn', '', '', '', 'project:milestone:remove', 4, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 21011 AND 21024 AND menu_type = 'btn';

-- 系统管理员（role 2）持有全部项目权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 21011 AND 21024 AND menu_type = 'btn';

-- 业务管理员（role 3）持有项目查看权限（数据范围仍由其 DEPT 数据权限收窄）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu WHERE menu_type = 'btn'
  AND permission IN ('project:project:list', 'project:member:list', 'project:milestone:list');
