-- M02 目标与 OKR 模块表结构（新增脚本，不修改 V1/V2；依据 M02 RULE §四 数据归属）
-- KR 进度口径见 L0 §9.1 D-2026-10-25-03：量化型正向 min(1, current/target)、反向 min(1, target/current)；
-- 任务驱动型 = 已完成顶层任务数 / 关联顶层任务总数（子任务不计入分母，无关联任务时进度 0 且标"待关联"）。
-- 关联任务只存计数与状态，不建任务表、不直连 task_*（任务侧由 M03 通过事件回写，本期未接入）。

CREATE TABLE IF NOT EXISTS okr_period (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '周期 ID',
    period_name VARCHAR(100) NOT NULL COMMENT '周期名称，如 2026-Q4',
    period_type VARCHAR(16) NOT NULL DEFAULT 'quarter' COMMENT '周期类型：quarter 季度，month 月度',
    start_date DATE NOT NULL COMMENT '周期开始日期',
    end_date DATE NOT NULL COMMENT '周期结束日期',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '周期状态：draft 未开启，active 进行中，archived 已归档',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_okr_period_name (period_name),
    KEY idx_okr_period_status (status),
    KEY idx_okr_period_date (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKR 目标周期表';

CREATE TABLE IF NOT EXISTS okr_objective (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '目标 ID',
    period_id BIGINT NOT NULL COMMENT '所属周期 ID（逻辑关联 okr_period.id）',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级目标 ID，0 表示顶层目标',
    objective_title VARCHAR(200) NOT NULL COMMENT '目标标题',
    objective_desc VARCHAR(1000) DEFAULT NULL COMMENT '目标描述',
    level VARCHAR(16) NOT NULL DEFAULT 'company' COMMENT '归属层级：company 公司，dept 部门，personal 个人',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（dept 层级必填，只读引用 sys_dept）',
    owner_id BIGINT DEFAULT NULL COMMENT '承接人用户 ID（状态为进行中时必填，只读引用 sys_user）',
    weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '权重百分比，同周期同父级下合计 100',
    progress DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '目标进度百分比 0-100，由下级 KR 进度归集',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '目标状态：draft 草稿，active 进行中，done 已完成',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_okr_objective_period (period_id),
    KEY idx_okr_objective_parent (parent_id),
    KEY idx_okr_objective_owner (owner_id),
    KEY idx_okr_objective_dept (dept_id),
    KEY idx_okr_objective_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKR 目标表';

CREATE TABLE IF NOT EXISTS okr_key_result (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关键结果 ID',
    objective_id BIGINT NOT NULL COMMENT '所属目标 ID（逻辑关联 okr_objective.id）',
    kr_title VARCHAR(200) NOT NULL COMMENT '关键结果标题',
    kr_type VARCHAR(16) NOT NULL DEFAULT 'quantitative' COMMENT 'KR 类型：quantitative 量化型，task_driven 任务驱动型',
    metric_unit VARCHAR(32) DEFAULT NULL COMMENT '指标单位（如 %、个、万元、天）',
    target_value DECIMAL(18,4) DEFAULT NULL COMMENT '目标值（量化型必填且大于 0）',
    current_value DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '当前值',
    direction VARCHAR(8) NOT NULL DEFAULT 'positive' COMMENT '指标方向：positive 正向，negative 反向（成本/耗时）',
    progress DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '进度百分比 0-100',
    progress_source VARCHAR(16) NOT NULL DEFAULT 'quantitative' COMMENT '进度来源：quantitative 量化公式，task_writeback 任务回写，manual 人工调整',
    task_link_status VARCHAR(16) NOT NULL DEFAULT 'unlinked' COMMENT '任务关联状态：unlinked 待关联，linked 已关联（M03 接入后维护）',
    task_count INT NOT NULL DEFAULT 0 COMMENT '关联顶层任务总数（子任务不计入；M03 接入后维护）',
    done_task_count INT NOT NULL DEFAULT 0 COMMENT '已完成关联顶层任务数（M03 接入后维护）',
    owner_id BIGINT DEFAULT NULL COMMENT 'KR 负责人用户 ID（只读引用 sys_user）',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_okr_key_result_objective (objective_id),
    KEY idx_okr_key_result_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKR 关键结果表';

CREATE TABLE IF NOT EXISTS okr_align (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '对齐关系 ID',
    child_objective_id BIGINT NOT NULL COMMENT '子目标 ID（对齐发起方）',
    parent_objective_id BIGINT NOT NULL COMMENT '父目标 ID（对齐承接方）',
    align_type VARCHAR(16) NOT NULL DEFAULT 'parent' COMMENT '对齐类型：parent 上级对齐，support 协同支持',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_okr_align_pair (child_objective_id, parent_objective_id),
    KEY idx_okr_align_parent (parent_objective_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKR 目标对齐关系表（单向：子目标 → 父目标）';

CREATE TABLE IF NOT EXISTS okr_progress_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '进度留痕 ID',
    kr_id BIGINT NOT NULL COMMENT '关键结果 ID（逻辑关联 okr_key_result.id）',
    objective_id BIGINT DEFAULT NULL COMMENT '所属目标 ID（冗余便于按目标检索）',
    from_progress DECIMAL(6,2) DEFAULT NULL COMMENT '变更前进度百分比，首次记录为空',
    to_progress DECIMAL(6,2) NOT NULL COMMENT '变更后进度百分比',
    progress_source VARCHAR(16) NOT NULL COMMENT '进度来源：quantitative 量化公式，task_writeback 任务回写，manual 人工调整',
    reason VARCHAR(500) DEFAULT NULL COMMENT '变更原因（人工调整必填）',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人账号',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_okr_progress_log_kr (kr_id),
    KEY idx_okr_progress_log_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKR 进度留痕表（只追加，不修改历史）';
