-- M02 目标与 OKR 权限码数据初始化（追加脚本，不修改 V1/V2；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：okr:period:list/add/edit/remove、okr:objective:list/add/edit/remove、okr:kr:list/add/edit/remove。
-- /api/okr/align-check 复用 okr:objective:list；/api/okr/progress 为内部接口（本期未开放，待 M03 事件接入）。
-- 说明：前端页面属 M02 阶段二交付，本期仅登记菜单/按钮权限码，避免新增权限码只存在于代码中。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (400, 0, '目标管理', 'dir', 'flag', '/okr', '', '', 5, 1, 1, 'system', 'system'),
    (401, 400, '目标周期', 'menu', 'calendar', '/okr/period', 'okr/period/index', 'okr:period:list', 1, 1, 1, 'system', 'system'),
    (4011, 401, '新增周期', 'btn', '', '', '', 'okr:period:add', 1, 1, 1, 'system', 'system'),
    (4012, 401, '编辑周期', 'btn', '', '', '', 'okr:period:edit', 2, 1, 1, 'system', 'system'),
    (4013, 401, '删除周期', 'btn', '', '', '', 'okr:period:remove', 3, 1, 1, 'system', 'system'),
    (402, 400, '目标制定', 'menu', 'target', '/okr/objective', 'okr/objective/index', 'okr:objective:list', 2, 1, 1, 'system', 'system'),
    (4021, 402, '新增目标', 'btn', '', '', '', 'okr:objective:add', 1, 1, 1, 'system', 'system'),
    (4022, 402, '编辑目标', 'btn', '', '', '', 'okr:objective:edit', 2, 1, 1, 'system', 'system'),
    (4023, 402, '删除目标', 'btn', '', '', '', 'okr:objective:remove', 3, 1, 1, 'system', 'system'),
    (403, 400, '关键结果', 'menu', 'trending', '/okr/key-result', 'okr/key-result/index', 'okr:kr:list', 3, 1, 1, 'system', 'system'),
    (4031, 403, '新增关键结果', 'btn', '', '', '', 'okr:kr:add', 1, 1, 1, 'system', 'system'),
    (4032, 403, '编辑关键结果', 'btn', '', '', '', 'okr:kr:edit', 2, 1, 1, 'system', 'system'),
    (4033, 403, '删除关键结果', 'btn', '', '', '', 'okr:kr:remove', 3, 1, 1, 'system', 'system'),
    (404, 400, '对齐检查', 'menu', 'link', '/okr/align-check', 'okr/align/index', 'okr:objective:list', 4, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时新菜单尚未存在，此处补齐目录、菜单与按钮）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 400 AND 499 OR permission LIKE 'okr:%';
