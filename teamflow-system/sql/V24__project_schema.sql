-- M14 项目管理 · 表结构（V24；只追加不改 V1–V23）
--
-- 本次范围：仅需求 2.5.1「项目立项」（甘特/时间线、风险登记、项目复盘属 M3，本期不做）。
-- 口径依据：L0 §2.4.3 2.5.1「项目与目标关联、负责人、周期、里程碑」；M14 RULE §四 数据归属（前缀 `proj_`）。
--
-- 硬约束落地：
--   ❌ 不建目标表、不复制目标字段：项目只存 objective_id、里程碑只存 kr_id 作为引用；
--      目标标题/进度一律经 M02 的 Service 实时读取，本模块表内**不存目标/KR 的标题快照**。
--   ❌ 不建任务表、不复制任务字段：里程碑完成度汇总经 M03 的 Service 按 KR 实时统计，
--      本模块表内不存任务标题/状态/时间快照。
--   ❌ 不做流程引擎：本期立项无审批链（2.5.1 未要求），不建任何流程引擎相关表。
-- 表前缀：`proj_`（同时含审计四字段 + 逻辑删除）。
-- 注释规范：表注释不使用任何既有模块表名/关键词（避免被代码生成器的表注释关键字检索误命中）。

CREATE TABLE IF NOT EXISTS proj_project (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目 ID',
    project_code VARCHAR(64) NOT NULL COMMENT '项目编号（唯一）',
    project_name VARCHAR(150) NOT NULL COMMENT '项目名称',
    project_desc VARCHAR(2000) DEFAULT NULL COMMENT '项目描述/立项说明',
    objective_id BIGINT DEFAULT NULL COMMENT '关联目标 ID（仅存引用，目标标题/进度实时经上游服务读取，不复制）',
    no_objective_reason VARCHAR(500) DEFAULT NULL COMMENT '不关联目标的原因（与 objective_id 至少填一项，R1 项目不得悬空）',
    owner_id BIGINT DEFAULT NULL COMMENT '项目负责人用户 ID（只读引用）',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（只读引用）',
    start_date DATE DEFAULT NULL COMMENT '计划开始日期',
    end_date DATE DEFAULT NULL COMMENT '计划结束日期',
    status VARCHAR(16) NOT NULL DEFAULT 'draft'
        COMMENT '项目状态：draft 立项草稿 / active 进行中 / closed 已结项 / archived 已归档（归档为终态）',
    priority VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '优先级：low / normal / high / urgent',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_proj_project_code (project_code),
    KEY idx_proj_project_status (status),
    KEY idx_proj_project_owner (owner_id),
    KEY idx_proj_project_dept (dept_id),
    KEY idx_proj_project_objective (objective_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目主表（立项信息；关联目标仅存引用，不复制其字段）';

CREATE TABLE IF NOT EXISTS proj_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目成员 ID',
    project_id BIGINT NOT NULL COMMENT '项目 ID（逻辑关联本模块项目主表）',
    user_id BIGINT NOT NULL COMMENT '成员用户 ID（只读引用）',
    member_role VARCHAR(16) NOT NULL DEFAULT 'member'
        COMMENT '项目内角色：owner 负责人 / member 参与人 / viewer 观察者（只表示项目内分工，不映射系统权限）',
    join_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    leave_time DATETIME DEFAULT NULL COMMENT '移出时间（为空表示在项目内）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_proj_member_pair (project_id, user_id),
    KEY idx_proj_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表（负责人与参与人；同一项目同一人唯一）';

CREATE TABLE IF NOT EXISTS proj_milestone (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '里程碑 ID',
    project_id BIGINT NOT NULL COMMENT '所属项目 ID（逻辑关联本模块项目主表）',
    milestone_name VARCHAR(150) NOT NULL COMMENT '里程碑名称',
    milestone_desc VARCHAR(1000) DEFAULT NULL COMMENT '里程碑说明',
    kr_id BIGINT DEFAULT NULL COMMENT '关联关键结果 ID（仅存引用；完成度经上游任务服务按该引用实时统计，不复制其字段）',
    plan_start_date DATE DEFAULT NULL COMMENT '计划开始日期',
    plan_end_date DATE DEFAULT NULL COMMENT '计划完成日期（延期判定依据，R7 口径唯一）',
    actual_start_date DATE DEFAULT NULL COMMENT '实际开始日期',
    actual_end_date DATE DEFAULT NULL COMMENT '实际完成日期',
    status VARCHAR(16) NOT NULL DEFAULT 'planned'
        COMMENT '里程碑状态：planned 计划中 / in_progress 进行中 / completed 已完成 / cancelled 已取消',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_proj_milestone_project (project_id),
    KEY idx_proj_milestone_kr (kr_id),
    KEY idx_proj_milestone_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目里程碑表（计划/实际时间与完成度汇总；完成度实时计算、不可手填）';
