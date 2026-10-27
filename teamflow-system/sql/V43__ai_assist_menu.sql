-- M11 AI 助手 · 待办建议与智能提醒权限（V43；只追加不改 V1–V42）
-- 依据 L0 §6.2 权限码约定；1.3.5 AI 待办建议、1.3.6 智能提醒。
-- 权限码清单（4 个）：
--   ai:suggest:generate / ai:suggest:list（基于负载均衡生成/查看任务分配建议）
--   ai:reminder:scan / ai:reminder:list（扫描并判定提醒/查看提醒记录）
-- 说明：沿用 V6 已建 M11 页面（1801 AI 对话）作为按钮挂载点，不新增页面菜单。
-- 幂等：固定主键 + INSERT IGNORE。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (18012, 1801, '生成任务分配建议', 'btn', '', '', '', 'ai:suggest:generate', 2, 1, 1, 'system', 'system'),
    (18013, 1801, '查看分配建议', 'btn', '', '', '', 'ai:suggest:list', 3, 1, 1, 'system', 'system'),
    (18014, 1801, '扫描智能提醒', 'btn', '', '', '', 'ai:reminder:scan', 4, 1, 1, 'system', 'system'),
    (18015, 1801, '查看提醒记录', 'btn', '', '', '', 'ai:reminder:list', 5, 1, 1, 'system', 'system');

-- 超级管理员 / 系统管理员：全部
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (18012, 18013, 18014, 18015) AND menu_type = 'btn';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id IN (18012, 18013, 18014, 18015) AND menu_type = 'btn';

-- 业务管理员：只读（查看建议与提醒记录；生成/扫描需管理员或明确授权）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn' AND permission IN ('ai:suggest:list', 'ai:reminder:list');
