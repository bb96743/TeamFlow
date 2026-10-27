-- M12 集成与开放 · Webhook 与导入导出权限（V41；只追加不改 V1–V40）
-- 依据 L0 §6.2 权限码约定；2.4.4 Webhook 事件外发、2.4.5 导入导出。
-- 权限码清单（7 个）：
--   Webhook（页面 1901 集成中心）：integration:webhook:list / add / edit / remove / test
--   导入导出（页面 1902 开放密钥）：integration:transfer:import / export
-- 说明：不新增页面菜单（沿用 V6 已建 M12 页面，避免再增占位页）；只追加按钮级权限并授权。
-- 幂等：固定主键 + INSERT IGNORE。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (19016, 1901, '查询 Webhook', 'btn', '', '', '', 'integration:webhook:list', 6, 1, 1, 'system', 'system'),
    (19017, 1901, '新增 Webhook', 'btn', '', '', '', 'integration:webhook:add', 7, 1, 1, 'system', 'system'),
    (19018, 1901, '编辑 Webhook', 'btn', '', '', '', 'integration:webhook:edit', 8, 1, 1, 'system', 'system'),
    (19019, 1901, '删除 Webhook', 'btn', '', '', '', 'integration:webhook:remove', 9, 1, 1, 'system', 'system'),
    (19020, 1901, '测试 Webhook 投递', 'btn', '', '', '', 'integration:webhook:test', 10, 1, 1, 'system', 'system'),
    (19024, 1902, '导入数据', 'btn', '', '', '', 'integration:transfer:import', 4, 1, 1, 'system', 'system'),
    (19025, 1902, '导出报表', 'btn', '', '', '', 'integration:transfer:export', 5, 1, 1, 'system', 'system');

-- 超级管理员 / 系统管理员：全部
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (19016, 19017, 19018, 19019, 19020, 19024, 19025) AND menu_type = 'btn';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id IN (19016, 19017, 19018, 19019, 19020, 19024, 19025) AND menu_type = 'btn';

-- 业务管理员：只读 + 导出（Webhook 查询、报表导出），不授予导入
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn' AND permission IN ('integration:webhook:list', 'integration:transfer:export');
