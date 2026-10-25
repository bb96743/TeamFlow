-- M04 团队与成员模块表结构（追加脚本，不修改 V1–V15；依据 M04 RULE §四 数据归属）
-- 依据 L0 §9.1 D-2026-10-26-08：
--   结论二：team_member.team_role（OWNER/MEMBER/VIEWER）**只用于团队内部分工展示与操作校验**，
--           绝不写入 sys_user_role、绝不映射为权限码；系统授权由 team:* 权限码决定，与 team_role 正交；
--   结论三：team_member 只存 user_id + 团队内属性，**禁止缓存昵称/工号/部门名**（展示时关联 sys_user/sys_dept）；
--   结论四：移出成员只写 leave_time 不删行（R3）；「我参与的团队」筛选条件 = leave_time IS NULL。
-- 表前缀：team_（M04 单一前缀，L0 §2.1）。
-- 审计：所有业务表均带 create_by/update_by/create_time/update_time + 逻辑删除 deleted。

CREATE TABLE IF NOT EXISTS team_team (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '团队 ID',
    team_name VARCHAR(100) NOT NULL COMMENT '团队名称',
    team_code VARCHAR(64) DEFAULT NULL COMMENT '团队编码（可空、唯一；便于外部引用）',
    team_desc VARCHAR(1000) DEFAULT NULL COMMENT '团队描述',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（只读引用 sys_dept，不复制部门名）',
    owner_id BIGINT DEFAULT NULL COMMENT '团队负责人用户 ID（只读引用 sys_user；与 team_member 的 OWNER 角色保持同步）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '团队状态：active 正常，archived 已归档，dissolved 已解散',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除（解散），0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_team_code (team_code),
    KEY idx_team_team_dept (dept_id),
    KEY idx_team_team_owner (owner_id),
    KEY idx_team_team_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队表（解散为逻辑删除，保留成员历史；R4）';

CREATE TABLE IF NOT EXISTS team_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id BIGINT NOT NULL COMMENT '团队 ID（逻辑关联 team_team.id）',
    user_id BIGINT NOT NULL COMMENT '用户 ID（只读引用 sys_user.id；禁止缓存姓名/工号/部门名）',
    team_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT '团队内角色：OWNER 负责人，MEMBER 成员，VIEWER 观察者（仅团队内分工，不映射系统角色/权限码）',
    join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间（重新加入时重置）',
    leave_time DATETIME DEFAULT NULL COMMENT '移出时间（NULL 表示仍在团队；移出不删行，R3）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常（成员移出不使用本字段，用 leave_time）',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_member_team_user (team_id, user_id),
    KEY idx_team_member_team_leave (team_id, leave_time),
    KEY idx_team_member_user_leave (user_id, leave_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队成员关系表（同一团队内 user_id 唯一；一人可属多团队 R5；移出写 leave_time 不删行 R3）';
