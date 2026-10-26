-- M13 统计与洞察权限码初始化（V21；只追加不改 V1–V20；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：
--   insight:task:view    任务统计看板（7.2）
--   insight:team:view    团队效能视图（7.3）
--   insight:metric:list / add / edit / remove   指标字典维护（7.1）
-- 说明：V6 已建 M13 的 4 个页面占位菜单
--       （2001 任务总览 / 2002 团队负载 / 2003 跨域看板 / 2004 指标口径），
--       本脚本只**追加按钮级权限行**（btn），不改 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
--       2003 跨域看板属 7.4（M3 排期，本期不实现），故不落权限码。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 任务总览（页面 2001）============
    (20011, 2001, '查询任务统计', 'btn', '', '', '', 'insight:task:view', 1, 1, 1, 'system', 'system'),
    -- ============ 团队负载（页面 2002）============
    (20021, 2002, '查询团队效能', 'btn', '', '', '', 'insight:team:view', 1, 1, 1, 'system', 'system'),
    -- ============ 指标口径（页面 2004）============
    (20041, 2004, '查询指标字典', 'btn', '', '', '', 'insight:metric:list', 1, 1, 1, 'system', 'system'),
    (20042, 2004, '新增指标口径', 'btn', '', '', '', 'insight:metric:add', 2, 1, 1, 'system', 'system'),
    (20043, 2004, '编辑指标口径', 'btn', '', '', '', 'insight:metric:edit', 3, 1, 1, 'system', 'system'),
    (20044, 2004, '删除指标口径', 'btn', '', '', '', 'insight:metric:remove', 4, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 20011 AND 20044 AND menu_type = 'btn';

-- 系统管理员（role 2）持有全部统计与指标字典权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 20011 AND 20044 AND menu_type = 'btn';

-- 业务管理员（role 3）持有统计查看权限（数据范围仍由其 DEPT 数据权限收窄，M13 R3）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn' AND permission IN ('insight:task:view', 'insight:team:view');
