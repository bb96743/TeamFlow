-- ============================================================================
-- V26 · 补业务菜单对 super_admin 的显式授权
-- ============================================================================
--
-- 【为什么需要这个脚本】
-- V6 建菜单骨架时明确「不写 sys_role_menu」（骨架期避免给角色空承诺）。
-- 各业务模块的菜单迁移（V8/V10/V12/V14/V17/V21/V23/V25）只授权了 **btn**
-- 按钮权限，没有授权 menu 本身。super_admin 之所以能看到全部菜单，是因为
-- `AuthService.selectAllRouteMenus()` 对超管做了绕过。
-- 本脚本把业务菜单显式授予 super_admin，让「超管可见全部」这件事在数据层
-- 也成立，而不是只依赖代码里的绕过分支（避免将来收紧绕过逻辑时超管反而看不到）。
--
-- 【为什么只授权 role 1，不给 sys_admin / biz_admin】
-- 这是我在实现本脚本时踩到的真实教训，记录在此以免后人重犯：
--   · `sys_menu` 的 menu 记录本身带 permission（如 `okr:period:list`），
--     **授权菜单 = 连带授予该权限码**。
--   · 我曾尝试给 sys_admin（role 2）授权全部业务菜单，结果既有测试
--     `OkrApiTest#okrPermissionsAreEnforcedOnEveryEndpoint` 立即变红：
--     该测试断言 chensy（角色 sys_admin）访问 `/api/okr/*` 必须 403，
--     而授权菜单后它获得了 `okr:period:list` → 变成 200。
--   · 这说明 M1 阶段已确立边界：**sys_admin 是平台管理员（用户/角色/菜单/
--     参数），不持有业务权限**；biz_admin（role 3）目前只有 perf/recruit/
--     insight/project 的只读按钮权限，也没有 okr/task/team/personal 的。
--
-- 因此：**本脚本不改变 sys_admin 与 biz_admin 的权限模型**。给业务角色开哪些
-- 菜单需要按「角色-权限矩阵」统一设计，属专项工作（已记入 M2 收尾验收报告
-- 遗留 M2-9），不应由一次迁移顺手改动。
--
-- 【幂等】INSERT IGNORE + SELECT，重复执行不新增、不报错。
-- 【不涉及】不新增/修改菜单本身，不动按钮权限，不改任何业务表。
-- ============================================================================

-- super_admin（role 1）：显式持有全部业务菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.id
FROM sys_menu m
WHERE m.menu_type = 'menu'
  AND (
        m.path LIKE '/okr/%'
     OR m.path LIKE '/task/%'
     OR m.path LIKE '/perf/%'
     OR m.path LIKE '/personal/%'
     OR m.path LIKE '/team/%'
     OR m.path LIKE '/recruit/%'
     OR m.path LIKE '/insight/%'
     OR m.path LIKE '/project/%'
  );
