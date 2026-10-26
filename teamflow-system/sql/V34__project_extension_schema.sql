-- M14 项目管理 · M3 扩展表结构（V34；只追加不改 V1–V33）
-- 依据 L0 §9.1 D-2026-10-26-11：
--   结论三：项目复盘正文只存知识库页面，本模块表内只保存返回的页面 ID；
--   M2-1 遗留：项目与任务的直连关联由本模块持有，任务存在性经任务模块 Service 校验，任务侧不加字段。
-- 前缀：proj_（本模块既定前缀）。
-- 审计：业务表均带审计四字段 + 逻辑删除。
-- 表注释刻意不写既有模块表名，避免代码生成器的表名检索被误命中。

-- ---------------------------------------------------------------------------
-- 1) 项目-任务直连关联（M2-1 遗留）
--    M14 持有 project_id + task_id + 关联类型；任务本体/状态/时间/完成度一律实时读 M03，
--    本表不保存任何任务字段快照（R2）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS proj_task_link (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id BIGINT NOT NULL COMMENT '项目 ID',
    task_id BIGINT NOT NULL COMMENT '任务 ID（只存引用，任务数据实时读任务模块）',
    link_type VARCHAR(16) NOT NULL DEFAULT 'execution' COMMENT '关联类型：plan 计划承接，execution 执行任务',
    remark VARCHAR(200) DEFAULT NULL COMMENT '关联说明',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_proj_task_link (project_id, task_id),
    KEY idx_proj_task_link_project (project_id),
    KEY idx_proj_task_link_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目与任务直连关联表（只存引用，任务事实实时读取）';

-- ---------------------------------------------------------------------------
-- 2) 项目风险登记（2.5.3）：风险条目 + 责任人 + 应对措施 + 状态机
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS proj_risk (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '风险 ID',
    project_id BIGINT NOT NULL COMMENT '项目 ID',
    risk_title VARCHAR(200) NOT NULL COMMENT '风险标题',
    risk_desc VARCHAR(2000) DEFAULT NULL COMMENT '风险描述',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'medium' COMMENT '风险等级：low 低，medium 中，high 高，critical 严重',
    risk_type VARCHAR(32) NOT NULL DEFAULT 'schedule' COMMENT '风险类别：schedule 进度，cost 成本，quality 质量，resource 资源，other 其他',
    owner_id BIGINT DEFAULT NULL COMMENT '责任人用户 ID',
    response_plan VARCHAR(2000) DEFAULT NULL COMMENT '应对措施',
    due_date DATE DEFAULT NULL COMMENT '计划应对完成日期',
    status VARCHAR(16) NOT NULL DEFAULT 'identified' COMMENT '风险状态：identified 已识别，assessed 已评估，responding 应对中，closed 已关闭，cancelled 已取消',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_proj_risk_project (project_id, status),
    KEY idx_proj_risk_owner (owner_id),
    KEY idx_proj_risk_level (risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目风险登记表';

-- ---------------------------------------------------------------------------
-- 3) 风险状态流转留痕（每次状态变更 append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS proj_risk_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '留痕 ID',
    risk_id BIGINT NOT NULL COMMENT '风险 ID',
    from_status VARCHAR(16) DEFAULT NULL COMMENT '变更前状态（创建时为空）',
    to_status VARCHAR(16) NOT NULL COMMENT '变更后状态',
    action_note VARCHAR(500) DEFAULT NULL COMMENT '处理意见/说明',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人账号',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_proj_risk_log_risk (risk_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险状态流转留痕表（只追加）';

-- ---------------------------------------------------------------------------
-- 4) 项目复盘归档引用（2.5.4）
--    正文存知识模块页面；本表只补三个引用列，绝不保存复盘正文。
-- ---------------------------------------------------------------------------
ALTER TABLE proj_project
    ADD COLUMN retro_page_id BIGINT DEFAULT NULL COMMENT '结项复盘归档页面 ID（正文归知识模块，本表只存引用）',
    ADD COLUMN retro_archived_time DATETIME DEFAULT NULL COMMENT '复盘归档时间',
    ADD COLUMN retro_archived_by VARCHAR(64) DEFAULT NULL COMMENT '复盘归档操作人账号';
