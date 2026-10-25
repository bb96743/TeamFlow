-- M03 任务与工作流权限码初始化（追加脚本，不修改 V1–V7；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：
--   task:task:list / add / edit / remove / assign / status
--   flow:def:list / add / edit
--   flow:instance:list / approve
-- 说明：V6 已建 M03 的 4 个页面占位菜单（1001 任务列表 / 1002 任务看板 / 1003 流程定义 / 1004 审批中心），
--       本脚本只**追加按钮级权限行**（btn），不改动 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」约定（如 401 → 4011），1001 → 10011，与既有 ID 无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 任务列表（页面 1001）============
    (10011, 1001, '查询任务', 'btn', '', '', '', 'task:task:list', 1, 1, 1, 'system', 'system'),
    (10012, 1001, '新增任务', 'btn', '', '', '', 'task:task:add', 2, 1, 1, 'system', 'system'),
    (10013, 1001, '编辑任务', 'btn', '', '', '', 'task:task:edit', 3, 1, 1, 'system', 'system'),
    (10014, 1001, '删除任务', 'btn', '', '', '', 'task:task:remove', 4, 1, 1, 'system', 'system'),
    (10015, 1001, '指派任务', 'btn', '', '', '', 'task:task:assign', 5, 1, 1, 'system', 'system'),
    (10016, 1001, '任务状态流转', 'btn', '', '', '', 'task:task:status', 6, 1, 1, 'system', 'system'),
    -- ============ 流程定义（页面 1003）============
    (10031, 1003, '查询流程定义', 'btn', '', '', '', 'flow:def:list', 1, 1, 1, 'system', 'system'),
    (10032, 1003, '新增流程定义', 'btn', '', '', '', 'flow:def:add', 2, 1, 1, 'system', 'system'),
    (10033, 1003, '编辑流程定义', 'btn', '', '', '', 'flow:def:edit', 3, 1, 1, 'system', 'system'),
    -- ============ 审批中心（页面 1004）============
    (10041, 1004, '查询流程实例', 'btn', '', '', '', 'flow:instance:list', 1, 1, 1, 'system', 'system'),
    (10042, 1004, '审批处理', 'btn', '', '', '', 'flow:instance:approve', 2, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 10011 AND 10042 AND menu_type = 'btn';
