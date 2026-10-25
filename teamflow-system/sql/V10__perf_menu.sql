-- M05 绩效权限码初始化（追加脚本，不修改 V1–V9；依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>）
-- 权限码清单：
--   perf:cycle:list / add / edit / remove / status / collect
--   perf:template:list / add / edit
--   perf:metric:list（指标字典，R2 四要素登记查询）
--   perf:score:list / self / submit
--   perf:result:list / archive / lock / appeal / appealHandle
-- 说明：V6 已建 M05 的 4 个页面占位菜单（1201 绩效周期 / 1202 指标模板 / 1203 绩效评分 / 1204 结果归档），
--       本脚本只**追加按钮级权限行**（btn），不改动 V6 已发布行、不改前端路由与组件（占位页保持规划中）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」约定（如 1201 → 12011），与既有 ID 无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 绩效周期（页面 1201）============
    (12011, 1201, '查询绩效周期', 'btn', '', '', '', 'perf:cycle:list', 1, 1, 1, 'system', 'system'),
    (12012, 1201, '新增绩效周期', 'btn', '', '', '', 'perf:cycle:add', 2, 1, 1, 'system', 'system'),
    (12013, 1201, '编辑绩效周期', 'btn', '', '', '', 'perf:cycle:edit', 3, 1, 1, 'system', 'system'),
    (12014, 1201, '删除绩效周期', 'btn', '', '', '', 'perf:cycle:remove', 4, 1, 1, 'system', 'system'),
    (12015, 1201, '周期状态流转', 'btn', '', '', '', 'perf:cycle:status', 5, 1, 1, 'system', 'system'),
    (12016, 1201, '归集预览与重算', 'btn', '', '', '', 'perf:cycle:collect', 6, 1, 1, 'system', 'system'),
    -- ============ 指标模板 / 指标字典（页面 1202）============
    (12021, 1202, '查询指标模板', 'btn', '', '', '', 'perf:template:list', 1, 1, 1, 'system', 'system'),
    (12022, 1202, '新增指标模板', 'btn', '', '', '', 'perf:template:add', 2, 1, 1, 'system', 'system'),
    (12023, 1202, '编辑指标模板', 'btn', '', '', '', 'perf:template:edit', 3, 1, 1, 'system', 'system'),
    (12024, 1202, '指标字典查询', 'btn', '', '', '', 'perf:metric:list', 4, 1, 1, 'system', 'system'),
    -- ============ 绩效评分（页面 1203）============
    (12031, 1203, '查询评分', 'btn', '', '', '', 'perf:score:list', 1, 1, 1, 'system', 'system'),
    (12032, 1203, '本人自评', 'btn', '', '', '', 'perf:score:self', 2, 1, 1, 'system', 'system'),
    (12033, 1203, '上级评分', 'btn', '', '', '', 'perf:score:submit', 3, 1, 1, 'system', 'system'),
    -- ============ 结果归档（页面 1204）============
    (12041, 1204, '查询绩效结果', 'btn', '', '', '', 'perf:result:list', 1, 1, 1, 'system', 'system'),
    (12042, 1204, '结果归档', 'btn', '', '', '', 'perf:result:archive', 2, 1, 1, 'system', 'system'),
    (12043, 1204, '结果锁定', 'btn', '', '', '', 'perf:result:lock', 3, 1, 1, 'system', 'system'),
    (12044, 1204, '提交申诉', 'btn', '', '', '', 'perf:result:appeal', 4, 1, 1, 'system', 'system'),
    (12045, 1204, '申诉处理', 'btn', '', '', '', 'perf:result:appealHandle', 5, 1, 1, 'system', 'system');

-- 超级管理员按 V2 规则应持有全部菜单（V2 建库时这些按钮尚未存在，此处补齐按钮授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 12011 AND 12045 AND menu_type = 'btn';

-- 系统管理员（role 2）持有全部绩效权限；业务管理员（role 3）持有涉本人/本部门的只读与自评/申诉权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 12011 AND 12045 AND menu_type = 'btn';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn'
  AND permission IN ('perf:cycle:list', 'perf:score:list', 'perf:score:self',
                     'perf:result:list', 'perf:result:appeal');
