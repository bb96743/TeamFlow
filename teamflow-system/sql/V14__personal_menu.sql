-- M10 个人效率权限码初始化（追加脚本，不修改 V1–V13；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：
--   personal:todo:list / handle / push（push 为统一投递入口的内部权限，不授予一般角色）
--   personal:time:list / add / edit
--   personal:focus:list / add
--   personal:review:list / add / edit / remove
--   personal:profile:view（效能画像，只读实时聚合）
-- 说明：V6 已建 M10 的 4 个页面占位菜单（1701 待办收件箱 / 1702 工时记录 / 1703 工作复盘 / 1704 效能画像），
--       本脚本只**追加按钮级权限行**（btn），不改动 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」约定（如 1701 → 17011），与既有 ID 无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 待办收件箱（页面 1701）============
    (17011, 1701, '查询待办', 'btn', '', '', '', 'personal:todo:list', 1, 1, 1, 'system', 'system'),
    (17012, 1701, '处理待办', 'btn', '', '', '', 'personal:todo:handle', 2, 1, 1, 'system', 'system'),
    (17013, 1701, '内部投递待办', 'btn', '', '', '', 'personal:todo:push', 3, 1, 1, 'system', 'system'),
    -- ============ 工时记录（页面 1702）============
    (17021, 1702, '查询工时', 'btn', '', '', '', 'personal:time:list', 1, 1, 1, 'system', 'system'),
    (17022, 1702, '填报工时', 'btn', '', '', '', 'personal:time:add', 2, 1, 1, 'system', 'system'),
    (17023, 1702, '编辑工时', 'btn', '', '', '', 'personal:time:edit', 3, 1, 1, 'system', 'system'),
    (17024, 1702, '查询专注会话', 'btn', '', '', '', 'personal:focus:list', 4, 1, 1, 'system', 'system'),
    (17025, 1702, '开始专注会话', 'btn', '', '', '', 'personal:focus:add', 5, 1, 1, 'system', 'system'),
    -- ============ 工作复盘（页面 1703）============
    (17031, 1703, '查询复盘', 'btn', '', '', '', 'personal:review:list', 1, 1, 1, 'system', 'system'),
    (17032, 1703, '新增复盘', 'btn', '', '', '', 'personal:review:add', 2, 1, 1, 'system', 'system'),
    (17033, 1703, '编辑复盘', 'btn', '', '', '', 'personal:review:edit', 3, 1, 1, 'system', 'system'),
    (17034, 1703, '删除复盘', 'btn', '', '', '', 'personal:review:remove', 4, 1, 1, 'system', 'system'),
    -- ============ 效能画像（页面 1704）============
    (17041, 1704, '查看效能画像', 'btn', '', '', '', 'personal:profile:view', 1, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 17011 AND 17041 AND menu_type = 'btn';
