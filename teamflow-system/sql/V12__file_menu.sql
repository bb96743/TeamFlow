-- G-1 统一文件上传：按钮级权限码落 sys_menu（追加脚本，不改 V1–V11）
-- 依据 L0 §6.2 权限码约定 <代号>:<资源>:<动作>；M00 RULE §六 R2「新增权限点必须落地三处」。
-- 权限码清单：
--   system:file:list   查询/下载（GET /api/system/files/{id}）
--   system:file:upload 上传（POST /api/system/files）
--   system:file:remove 删除（DELETE /api/system/files/{id}）
-- 说明：
--   · 前端文件管理页面属阶段二（M03/M07 前端尚未开发），本脚本先登记"文件管理"页面（108）与按钮，
--     页面 component 用 placeholder/file/index，沿用 V6「菜单骨架」的占位约定，避免空路由。
--   · ID 段位沿用既有约定：系统管理目录 100 下页面 108（101–107 已用），按钮 = 页面 ID * 10 + 序号（1081–1083）。
--   · 幂等：固定主键 + INSERT IGNORE，重复执行不新增、不报错。
--   · 授权：超级管理员按 V2/V4/V8 规则持有全部菜单，此处补齐 108 及按钮授权。

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, permission, sort, visible, status, create_by, update_by)
VALUES
    (108, 100, '文件管理', 'menu', 'file', '/system/file', 'placeholder/file/index', 'system:file:list', 8, 1, 1, 'system', 'system'),
    (1081, 108, '上传文件', 'btn', '', '', '', 'system:file:upload', 1, 1, 1, 'system', 'system'),
    (1082, 108, '下载文件', 'btn', '', '', '', 'system:file:list', 2, 1, 1, 'system', 'system'),
    (1083, 108, '删除文件', 'btn', '', '', '', 'system:file:remove', 3, 1, 1, 'system', 'system');

-- 超级管理员持有全部菜单（与 V2/V4/V8 一致）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (108, 1081, 1082, 1083);
