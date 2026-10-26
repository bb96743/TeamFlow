-- M09 协作与消息（M3-A）权限码初始化（V29；只追加不改 V1–V28）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>
-- 权限码清单（11 个）：
--   collab:msg:list / send            站内信会话与消息
--   collab:comment:list / add / remove  全站统一评论（多态挂载）
--   collab:notice:list / add / edit / remove  公告
--   collab:feed:list                  动态流（只读，来源于领域事件投影）
--   collab:internal:notify            统一通知投递入口（内部权限，不授予一般角色）
-- 说明：
--   1) V6 已建 M09 的 3 个页面占位菜单（1601 站内信 / 1602 公告 / 1603 动态流）；
--      本脚本只追加按钮级权限行（btn），不改 V6 已发布行、不改前端路由与组件。
--   2) 遵循 D-2026-10-26-10「menu 只表达可见性、权限码一律由 btn 承载」：
--      1600 目录与 1601/1602/1603 三个页面的 permission 保持为空，只授予可见性，
--      不连带授予任何权限码。
--   3) 角色授权沿用近期业务模块口径：super_admin 全部；sys_admin（平台管理员）持有
--      协作模块全部按钮；biz_admin 只读（list）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」（1601 → 16011），既有 MAX(sys_menu.id)=22013，无冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 站内信 / 统一评论（页面 1601）============
    (16011, 1601, '查询会话与消息', 'btn', '', '', '', 'collab:msg:list', 1, 1, 1, 'system', 'system'),
    (16012, 1601, '发送站内信', 'btn', '', '', '', 'collab:msg:send', 2, 1, 1, 'system', 'system'),
    (16013, 1601, '查询评论', 'btn', '', '', '', 'collab:comment:list', 3, 1, 1, 'system', 'system'),
    (16014, 1601, '发表评论', 'btn', '', '', '', 'collab:comment:add', 4, 1, 1, 'system', 'system'),
    (16015, 1601, '删除本人评论', 'btn', '', '', '', 'collab:comment:remove', 5, 1, 1, 'system', 'system'),
    (16016, 1601, '统一通知投递', 'btn', '', '', '', 'collab:internal:notify', 6, 1, 1, 'system', 'system'),
    -- ============ 公告（页面 1602）============
    (16021, 1602, '查询公告', 'btn', '', '', '', 'collab:notice:list', 1, 1, 1, 'system', 'system'),
    (16022, 1602, '发布公告', 'btn', '', '', '', 'collab:notice:add', 2, 1, 1, 'system', 'system'),
    (16023, 1602, '编辑公告', 'btn', '', '', '', 'collab:notice:edit', 3, 1, 1, 'system', 'system'),
    (16024, 1602, '删除公告', 'btn', '', '', '', 'collab:notice:remove', 4, 1, 1, 'system', 'system'),
    -- ============ 动态流（页面 1603）============
    (16031, 1603, '查看动态流', 'btn', '', '', '', 'collab:feed:list', 1, 1, 1, 'system', 'system');

-- 超级管理员（role 1）持有全部协作权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 16011 AND 16031 AND menu_type = 'btn';

-- 系统管理员（role 2）持有协作模块全部按钮（沿用近期业务模块授权口径）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE id BETWEEN 16011 AND 16031 AND menu_type = 'btn';

-- 业务管理员（role 3）只读：会话/评论/公告/动态流
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu WHERE menu_type = 'btn'
  AND permission IN ('collab:msg:list', 'collab:comment:list', 'collab:notice:list', 'collab:feed:list');

-- 菜单可见性（menu 本身 permission 为空，只授权可见、不连带权限码；遵循 D-10 结论一）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 1600), (1, 1601), (1, 1602), (1, 1603),
    (2, 1600), (2, 1601), (2, 1602), (2, 1603),
    (3, 1600), (3, 1601), (3, 1602), (3, 1603);
