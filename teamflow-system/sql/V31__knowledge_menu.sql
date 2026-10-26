-- M07 知识与文档（M3-B）权限码与归档空间初始化（V31；只追加不改 V1–V30）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>；D-2026-10-26-10「menu 只表达可见性、权限码一律由 btn 承载」。
--
-- 权限码清单（17 个）：
--   knowledge:space:list / add / edit / remove      空间管理（1401）
--   knowledge:node:list / add / edit / remove       目录管理（1401）
--   knowledge:acl:edit                              访问授权（覆盖/取消，需资源 manage）
--   knowledge:page:list / add / edit / remove       页面与版本（1402）
--   knowledge:file:list / add / remove              文档中心附件引用（1402）
--   knowledge:internal:read                         内部只读端口（供 M11 切片；不授予一般角色）
-- 说明：
--   1) V6 已建 M07 的目录（1400）与 3 个页面占位菜单（1401 知识空间 / 1402 文档中心 / 1403 全文检索）；
--      本脚本只追加按钮级权限行，不改 V6 已发布行；1403 全文检索「已登录即可」，不落按钮。
--   2) 菜单本身 permission 保持为空：授权菜单只增加可见性，不连带授予任何权限码。
--   3) 角色授权沿用 M3-A（M09）口径：super_admin 全部；sys_admin 持本模块写权限；
--      biz_admin 只读；内部只读端口只授 super_admin（内部端口按 id 直取正文，不叠加页面授权）。
-- 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
-- ID 段位：沿用「页面 ID * 10 + 序号」（1401 → 14011、1402 → 14021），与既有段位不冲突。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    -- ============ 知识空间 / 目录 / 授权（页面 1401）============
    (14011, 1401, '查询知识空间', 'btn', '', '', '', 'knowledge:space:list', 1, 1, 1, 'system', 'system'),
    (14012, 1401, '新增知识空间', 'btn', '', '', '', 'knowledge:space:add', 2, 1, 1, 'system', 'system'),
    (14013, 1401, '编辑知识空间', 'btn', '', '', '', 'knowledge:space:edit', 3, 1, 1, 'system', 'system'),
    (14014, 1401, '删除知识空间', 'btn', '', '', '', 'knowledge:space:remove', 4, 1, 1, 'system', 'system'),
    (14015, 1401, '查询目录', 'btn', '', '', '', 'knowledge:node:list', 5, 1, 1, 'system', 'system'),
    (14016, 1401, '新增目录', 'btn', '', '', '', 'knowledge:node:add', 6, 1, 1, 'system', 'system'),
    (14017, 1401, '编辑目录', 'btn', '', '', '', 'knowledge:node:edit', 7, 1, 1, 'system', 'system'),
    (14018, 1401, '删除目录', 'btn', '', '', '', 'knowledge:node:remove', 8, 1, 1, 'system', 'system'),
    (14019, 1401, '知识访问授权', 'btn', '', '', '', 'knowledge:acl:edit', 9, 1, 1, 'system', 'system'),
    -- ============ 文档中心（页面 1402）：页面/版本/附件 ============
    (14021, 1402, '查询页面', 'btn', '', '', '', 'knowledge:page:list', 1, 1, 1, 'system', 'system'),
    (14022, 1402, '新增页面', 'btn', '', '', '', 'knowledge:page:add', 2, 1, 1, 'system', 'system'),
    (14023, 1402, '编辑页面', 'btn', '', '', '', 'knowledge:page:edit', 3, 1, 1, 'system', 'system'),
    (14024, 1402, '删除页面', 'btn', '', '', '', 'knowledge:page:remove', 4, 1, 1, 'system', 'system'),
    (14025, 1402, '查询附件', 'btn', '', '', '', 'knowledge:file:list', 5, 1, 1, 'system', 'system'),
    (14026, 1402, '引用附件', 'btn', '', '', '', 'knowledge:file:add', 6, 1, 1, 'system', 'system'),
    (14027, 1402, '移除附件引用', 'btn', '', '', '', 'knowledge:file:remove', 7, 1, 1, 'system', 'system'),
    (14028, 1402, '内部正文读取', 'btn', '', '', '', 'knowledge:internal:read', 8, 1, 1, 'system', 'system');

-- 超级管理员（role 1）持有本模块全部权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id BETWEEN 14011 AND 14019 OR id BETWEEN 14021 AND 14028;

-- 系统管理员（role 2）持有业务写权限，但**不含**内部正文读取端口
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu
WHERE (id BETWEEN 14011 AND 14019 OR id BETWEEN 14021 AND 14027)
  AND menu_type = 'btn';

-- 业务管理员（role 3）只读：空间 / 目录 / 页面 / 附件查询
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu
WHERE menu_type = 'btn'
  AND permission IN ('knowledge:space:list', 'knowledge:node:list', 'knowledge:page:list', 'knowledge:file:list');

-- 菜单可见性（menu 本身无 permission，只授权可见、不连带权限码）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 1400), (1, 1401), (1, 1402), (1, 1403),
    (2, 1400), (2, 1401), (2, 1402), (2, 1403),
    (3, 1400), (3, 1401), (3, 1402), (3, 1403);

-- ============ 归档默认空间（M06 纪要 / M14 复盘未指定空间时落这里）============
-- 正文只存 knowledge_page；业务模块只存页面 ID（D-2026-10-26-11 结论三）。
INSERT IGNORE INTO knowledge_space
    (id, space_name, space_code, space_desc, visibility, owner_id, status, sort, remark, create_by, update_by)
VALUES
    (1, '归档知识库', 'archive',
     '会议纪要与项目复盘的统一归档空间；正文只保存在知识页面，业务模块只保存页面 ID。',
     'internal', 1, 'active', 1, '系统预置归档空间，供 M06/M14 归档调用', 'system', 'system');

-- 归档空间所有者（admin / 用户 1）获得 manage 授权，便于后续维护空间与授权链
INSERT IGNORE INTO knowledge_acl
    (resource_type, resource_id, subject_type, subject_id, permission, remark, create_by, update_by)
VALUES
    ('space', 1, 'user', 1, 'manage', '归档空间所有者默认管理授权', 'system', 'system');
