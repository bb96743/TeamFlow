-- M12 集成与开放 · 权限码与菜单可见性（V39；只追加不改 V1–V38）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>；D-2026-10-27-12 结论四/五。
-- 权限码清单（8 个）：
--   第三方集成（页面 1901）：integration:app:list / add / edit / remove / check
--   开放密钥（页面 1902）：integration:key:list / add / revoke
-- 说明：
--   1) V6 已建 M12 的页面占位菜单（1900 目录 / 1901 第三方集成 / 1902 开放密钥），
--      本脚本只追加按钮级权限行（btn），不改 V6 已发布行、不改前端路由与组件。
--   2) 遵循「menu 只表达可见性、权限码一律由 btn 承载」：页面 permission 保持为空。
--   3) V6/V26 未给 M12 页面建立角色可见性授权，本脚本一并补齐：
--      role1/role2 可见全部三个菜单；role3（业务管理员）可见并只读（list）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 第三方集成（页面 1901）============
    (19011, 1901, '查询集成应用', 'btn', '', '', '', 'integration:app:list', 1, 1, 1, 'system', 'system'),
    (19012, 1901, '新增集成应用', 'btn', '', '', '', 'integration:app:add', 2, 1, 1, 'system', 'system'),
    (19013, 1901, '编辑集成应用', 'btn', '', '', '', 'integration:app:edit', 3, 1, 1, 'system', 'system'),
    (19014, 1901, '删除集成应用', 'btn', '', '', '', 'integration:app:remove', 4, 1, 1, 'system', 'system'),
    (19015, 1901, '连通性检测', 'btn', '', '', '', 'integration:app:check', 5, 1, 1, 'system', 'system'),
    -- ============ 开放密钥（页面 1902）============
    (19021, 1902, '查询开放密钥', 'btn', '', '', '', 'integration:key:list', 1, 1, 1, 'system', 'system'),
    (19022, 1902, '创建开放密钥', 'btn', '', '', '', 'integration:key:add', 2, 1, 1, 'system', 'system'),
    (19023, 1902, '撤销开放密钥', 'btn', '', '', '', 'integration:key:revoke', 3, 1, 1, 'system', 'system');

-- 超级管理员（role 1）/ 系统管理员（role 2）：全部按钮 + 菜单可见
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE (id BETWEEN 19011 AND 19023 AND menu_type = 'btn') OR id IN (1900, 1901, 1902);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE (id BETWEEN 19011 AND 19023 AND menu_type = 'btn') OR id IN (1900, 1901, 1902);

-- 业务管理员（role 3）：只读（查询权限 + 菜单可见）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE permission IN ('integration:app:list', 'integration:key:list')
   OR (id IN (1900, 1901, 1902) AND menu_type IN ('dir', 'menu'));
