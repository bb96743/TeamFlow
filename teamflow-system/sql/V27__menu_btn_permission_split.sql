-- ============================================================================
-- V27 · M2-9 修复：角色-权限矩阵 + 「menu 只管可见、btn 只管权限」
-- ============================================================================
--
-- 依据 L0 §9.1 D-2026-10-26-10（结论一/二/三/四，2026-10-26 领导者裁定）：
--   结论一：menu 记录只表达「可见性」，权限码一律由 btn 承载；授权菜单不得隐含任何权限。
--   结论二：M02 的 4 个 menu 带 okr:*:list 是历史做法，本期清空并由新增 btn 承载；
--           M00 平台的 11 个带 permission 的 menu 暂不动（已知例外，已在 M00 RULE/CR 登记）。
--   结论三：sys_admin = M2 业务全权（已有 55 个 btn）+ 平台写 + M1 无权限（测试边界，不改）；
--           biz_admin = M2 业务只读（已有 15 个 list/view btn）；super_admin = 全部。
--   结论四：用通用规则消除「有权无门」——凡某角色在某页面下持有任意 btn，就授予该页面
--           与其所属目录的可见性；不逐条枚举角色/模块清单，新增模块自动生效。
--
-- 【顺序很关键（领导者已踩过的真实坑）】
--   必须先清空 M02 四个 menu 的 permission，再做菜单可见性推导。
--   否则 `sys_menu.permission` 连带生效：授权菜单 = 连带授予权限码，
--   sys_admin(chensy) 会凭空获得 okr:*:list，直接推翻 OkrApiTest 的 403 断言。
--
-- 【推导只覆盖「无 permission 的页面」】
--   `selectPermissions` 的权限来源是 `sys_role_menu JOIN sys_menu WHERE permission 非空`，
--   因此仅授予 permission 为 NULL 的页面/目录才能保证「只加可见性、不加权限码」。
--   M00 的 11 个带 permission 的 menu 属已知例外，不在推导范围（D-10 结论二）。
--
-- 【幂等】INSERT IGNORE + UPDATE + 推导 INSERT IGNORE，重复执行不新增、不报错。
-- 【不涉及】不改 V1–V26、不改 M00 的 11 个 menu.permission、不改任何业务表数据。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 新增 3 个 btn 承载 okr:*:list
--    （okr:objective:list 被 /okr/objective 与 /okr/align-check 两个 menu 共用，只建一个 btn）
--    新 id 段 22011–22013：既有 MAX(sys_menu.id)=21024，不冲突。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (22011, 401, '查询目标周期', 'btn', '', '', '', 'okr:period:list', 1, 1, 1, 'system', 'system'),
    (22012, 402, '查询目标', 'btn', '', '', '', 'okr:objective:list', 1, 1, 1, 'system', 'system'),
    (22013, 403, '查询关键结果', 'btn', '', '', '', 'okr:kr:list', 1, 1, 1, 'system', 'system');

-- ---------------------------------------------------------------------------
-- 2) 清空 M02 四个 menu 的 permission（menu 只表达可见性；list 权限改由 btn 承载）
-- ---------------------------------------------------------------------------
UPDATE sys_menu
SET permission = NULL
WHERE id IN (401, 402, 403, 404) AND menu_type = 'menu';

-- ---------------------------------------------------------------------------
-- 3) 新 btn 授予 super_admin（role 1）；**不授予** sys_admin(2)/biz_admin(3)
--    （M1 边界：sys_admin 只管平台与 M2，不持有 okr 权限，OkrApiTest/TeamApiTest 依赖此边界）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 22011), (1, 22012), (1, 22013);

-- ---------------------------------------------------------------------------
-- 4) 通用规则：凡某角色在某页面下持有任意 btn → 授予该页面可见性
--    （页面必须无 permission，保证只加可见性、不连带权限码）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, page.id
FROM sys_role_menu rm
JOIN sys_menu btn ON btn.id = rm.menu_id AND btn.menu_type = 'btn'
JOIN sys_menu page ON page.id = btn.parent_id AND page.menu_type = 'menu'
WHERE page.permission IS NULL OR page.permission = '';

-- ---------------------------------------------------------------------------
-- 5) 通用规则：同上角色 → 授予该页面的所属目录可见性（保证前端路由层级完整）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, dir.id
FROM sys_role_menu rm
JOIN sys_menu btn ON btn.id = rm.menu_id AND btn.menu_type = 'btn'
JOIN sys_menu page ON page.id = btn.parent_id AND page.menu_type = 'menu'
JOIN sys_menu dir ON dir.id = page.parent_id AND dir.menu_type = 'dir'
WHERE page.permission IS NULL OR page.permission = '';
