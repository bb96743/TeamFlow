-- M04 团队与成员权限码初始化（追加脚本，不修改 V1–V16；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：
--   team:team:list / add / edit / remove（remove = 解散，逻辑删除）
--   team:member:list / add / role / remove（移出写 leave_time，不删行）
-- 说明：V6 已建 M04 的 2 个页面占位菜单（1101 团队管理 / 1102 成员负载），本脚本只**追加按钮级权限行**（btn），
--       不改动 V6 已发布行、不改前端路由与组件（占位页保持规划中）；1102 成员负载页复用 team:member:list 权限，不另建重复权限码。
-- 重要（D-2026-10-26-08 结论二）：team_role（OWNER/MEMBER/VIEWER）是团队内分工，**不是系统角色**；
--       本脚本只登记系统权限码，**不写 sys_role / sys_user_role**，团队角色与系统授权完全正交。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」约定（如 1101 → 11011），与既有 ID 无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 团队管理（页面 1101）============
    (11011, 1101, '查询团队', 'btn', '', '', '', 'team:team:list', 1, 1, 1, 'system', 'system'),
    (11012, 1101, '新增团队', 'btn', '', '', '', 'team:team:add', 2, 1, 1, 'system', 'system'),
    (11013, 1101, '编辑团队/设置负责人', 'btn', '', '', '', 'team:team:edit', 3, 1, 1, 'system', 'system'),
    (11014, 1101, '解散团队', 'btn', '', '', '', 'team:team:remove', 4, 1, 1, 'system', 'system'),
    (11015, 1101, '查询成员/成员负载', 'btn', '', '', '', 'team:member:list', 5, 1, 1, 'system', 'system'),
    (11016, 1101, '添加成员', 'btn', '', '', '', 'team:member:add', 6, 1, 1, 'system', 'system'),
    (11017, 1101, '变更成员角色', 'btn', '', '', '', 'team:member:role', 7, 1, 1, 'system', 'system'),
    (11018, 1101, '移出成员', 'btn', '', '', '', 'team:member:remove', 8, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 11011 AND 11018 AND menu_type = 'btn';
