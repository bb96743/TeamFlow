-- M08 招聘权限码初始化（V23；只追加不改 V1–V22；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单（19 个）：
--   recruit:job:list / add / edit / remove / approve
--   recruit:candidate:list / add / edit / resume:add
--   recruit:interview:list / add / edit
--   recruit:offer:list / add / edit / approve / onboard
--   recruit:dashboard:view / register
-- 说明：V6 已建 M08 的 4 个页面占位菜单（1501 职位管理 / 1502 候选人 / 1503 面试安排 / 1504 Offer 管理），
--       本脚本只**追加按钮级权限行**（btn），不改 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
--       2.1.5 招聘看板暂挂在职位管理页（1501）下作为按钮级入口，不新增页面。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 职位管理（页面 1501）============
    (15011, 1501, '查询职位', 'btn', '', '', '', 'recruit:job:list', 1, 1, 1, 'system', 'system'),
    (15012, 1501, '新增职位', 'btn', '', '', '', 'recruit:job:add', 2, 1, 1, 'system', 'system'),
    (15013, 1501, '编辑职位', 'btn', '', '', '', 'recruit:job:edit', 3, 1, 1, 'system', 'system'),
    (15014, 1501, '删除职位', 'btn', '', '', '', 'recruit:job:remove', 4, 1, 1, 'system', 'system'),
    (15015, 1501, '需求审批处理', 'btn', '', '', '', 'recruit:job:approve', 5, 1, 1, 'system', 'system'),
    (15016, 1501, '查询招聘看板', 'btn', '', '', '', 'recruit:dashboard:view', 6, 1, 1, 'system', 'system'),
    (15017, 1501, '登记看板指标口径', 'btn', '', '', '', 'recruit:dashboard:register', 7, 1, 1, 'system', 'system'),
    -- ============ 候选人（页面 1502）============
    (15021, 1502, '查询候选人', 'btn', '', '', '', 'recruit:candidate:list', 1, 1, 1, 'system', 'system'),
    (15022, 1502, '新增候选人', 'btn', '', '', '', 'recruit:candidate:add', 2, 1, 1, 'system', 'system'),
    (15023, 1502, '编辑候选人/推进阶段', 'btn', '', '', '', 'recruit:candidate:edit', 3, 1, 1, 'system', 'system'),
    (15024, 1502, '上传简历版本', 'btn', '', '', '', 'recruit:resume:add', 4, 1, 1, 'system', 'system'),
    -- ============ 面试安排（页面 1503）============
    (15031, 1503, '查询面试', 'btn', '', '', '', 'recruit:interview:list', 1, 1, 1, 'system', 'system'),
    (15032, 1503, '安排面试', 'btn', '', '', '', 'recruit:interview:add', 2, 1, 1, 'system', 'system'),
    (15033, 1503, '面试评价/修改', 'btn', '', '', '', 'recruit:interview:edit', 3, 1, 1, 'system', 'system'),
    -- ============ Offer 管理（页面 1504）============
    (15041, 1504, '查询 Offer', 'btn', '', '', '', 'recruit:offer:list', 1, 1, 1, 'system', 'system'),
    (15042, 1504, '新增 Offer', 'btn', '', '', '', 'recruit:offer:add', 2, 1, 1, 'system', 'system'),
    (15043, 1504, '编辑 Offer/答复', 'btn', '', '', '', 'recruit:offer:edit', 3, 1, 1, 'system', 'system'),
    (15044, 1504, 'Offer 审批处理', 'btn', '', '', '', 'recruit:offer:approve', 4, 1, 1, 'system', 'system'),
    (15045, 1504, '入职转用户', 'btn', '', '', '', 'recruit:offer:onboard', 5, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 15011 AND 15045 AND menu_type = 'btn';

-- 系统管理员（role 2）持有全部招聘权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 15011 AND 15045 AND menu_type = 'btn';

-- 业务管理员（role 3）持有招聘查看权限（数据范围仍由其 DEPT 数据权限收窄）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn' AND permission IN (
    'recruit:job:list', 'recruit:candidate:list', 'recruit:interview:list',
    'recruit:offer:list', 'recruit:dashboard:view'
);
