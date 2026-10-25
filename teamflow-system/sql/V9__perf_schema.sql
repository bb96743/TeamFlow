-- M05 绩效模块表结构（追加脚本，不修改 V1–V8；依据 M05 RULE §四 数据归属）
-- 依据 L0 §9.1 D-2026-10-25-06「绩效归集口径包」（D-06.1～D-06.5）：
--   D-06.1 逾期：KR 进度不因逾期扣减，逾期另立 perf 的「时限达标率」可归集指标；
--   D-06.2 返工：进度随状态重算、归集取「周期末快照」而非历史峰值（perf_score 重算覆盖，不累加）；
--   D-06.3 校准限额：校准只发生在 M02 的 KR 进度（±20 个百分点内），本模块归集值禁止手工覆盖；
--   D-06.4 并发：M02 订阅端对 okr_key_result 行加悲观锁（在 M02 代码内实现，本脚本不涉及）；
--   D-06.5 缺失值：默认取 0；仅经 HR/管理者显式标记 not_applicable 的例外状态按剩余权重归一化。
-- 数据边界：M05 只写 perf_* 表；KR 达成率经 M02 Service、任务事实经 M03 Service 取数，
--          **禁止直连 okr_* / task_* 表**（L0 §4.3、§6.2；M05 RULE §四）。
-- 审计：所有业务表均带 create_by/update_by/create_time/update_time + 逻辑删除 deleted。

CREATE TABLE IF NOT EXISTS perf_cycle (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '绩效周期 ID',
    cycle_code VARCHAR(64) NOT NULL COMMENT '周期编码（唯一，如 2026-Q4）',
    cycle_name VARCHAR(100) NOT NULL COMMENT '周期名称',
    cycle_type VARCHAR(16) NOT NULL DEFAULT 'quarter' COMMENT '周期类型：quarter 季度，month 月度',
    start_date DATE NOT NULL COMMENT '周期开始日期（归集取数下界）',
    end_date DATE NOT NULL COMMENT '周期结束日期（归集取数上界）',
    template_id BIGINT DEFAULT NULL COMMENT '指标模板 ID（逻辑关联 perf_template.id；开启周期前必填）',
    status VARCHAR(16) NOT NULL DEFAULT 'draft'
        COMMENT '周期状态：draft 草稿，active 进行中，scoring 评分中，archived 已归档，locked 已锁定（锁定不可逆）',
    collect_time DATETIME DEFAULT NULL COMMENT '最近一次归集（重算）时间',
    archive_time DATETIME DEFAULT NULL COMMENT '结果归档时间',
    lock_time DATETIME DEFAULT NULL COMMENT '锁定时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_cycle_code (cycle_code),
    KEY idx_perf_cycle_status (status),
    KEY idx_perf_cycle_date (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绩效周期表（状态机：draft→active→scoring→archived→locked，锁定不可逆）';

CREATE TABLE IF NOT EXISTS perf_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指标模板 ID',
    template_code VARCHAR(64) NOT NULL COMMENT '模板编码（唯一）',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '模板说明',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '模板状态：draft 草稿，enabled 启用，disabled 停用',
    total_weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '指标项权重合计（R6：启用时必须恰好 100）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_template_code (template_code),
    KEY idx_perf_template_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绩效指标模板表（R6 权重校验：草稿 ≤100，启用/开周期必须 =100）';

CREATE TABLE IF NOT EXISTS perf_template_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指标项 ID',
    template_id BIGINT NOT NULL COMMENT '所属模板 ID（逻辑关联 perf_template.id）',
    item_code VARCHAR(64) NOT NULL COMMENT '指标项编码（模板内唯一）',
    item_name VARCHAR(100) NOT NULL COMMENT '指标项名称',
    item_category VARCHAR(16) NOT NULL DEFAULT 'collectable'
        COMMENT '指标类别：collectable 可归集（系统计算，只读），subjective 主观（人工评分，必填评语）',
    metric_code VARCHAR(64) DEFAULT NULL
        COMMENT '可归集指标编码：必须已在指标字典（PerfMetricDictionary）登记四要素；主观类为空',
    weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '权重百分比（>0）',
    max_score DECIMAL(6,2) NOT NULL DEFAULT 100.00 COMMENT '分值上限（默认 100）',
    score_standard VARCHAR(500) DEFAULT NULL COMMENT '评分标准说明',
    require_comment TINYINT NOT NULL DEFAULT 0 COMMENT '是否必填评语：1 必填（主观类恒为 1）',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_template_item_code (template_id, item_code),
    KEY idx_perf_template_item_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绩效指标项表（可归集类必须登记指标字典四要素，R2 硬门槛）';

CREATE TABLE IF NOT EXISTS perf_score (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评分记录 ID',
    cycle_id BIGINT NOT NULL COMMENT '绩效周期 ID（逻辑关联 perf_cycle.id）',
    employee_id BIGINT NOT NULL COMMENT '被评人用户 ID（只读引用 sys_user）',
    template_item_id BIGINT NOT NULL COMMENT '指标项 ID（逻辑关联 perf_template_item.id）',
    item_code VARCHAR(64) NOT NULL COMMENT '指标项编码（冗余快照）',
    item_name VARCHAR(100) NOT NULL COMMENT '指标项名称（冗余快照）',
    item_category VARCHAR(16) NOT NULL COMMENT '指标类别：collectable / subjective（冗余快照）',
    weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '权重快照',
    rater_type VARCHAR(16) NOT NULL DEFAULT 'system'
        COMMENT '评分人类型：system 系统归集，self 自评，manager 上级评',
    rater_id BIGINT DEFAULT NULL COMMENT '评分人用户 ID（system 为空）',
    rater_name VARCHAR(64) DEFAULT NULL COMMENT '评分人账号（system 为 event/system）',
    auto_score DECIMAL(6,2) DEFAULT NULL COMMENT '归集自动值（可归集类；0-100）',
    score DECIMAL(6,2) DEFAULT NULL COMMENT '本次分值（0-100）',
    previous_score DECIMAL(6,2) DEFAULT NULL COMMENT '上一次分值（R4 留痕：记录前后值）',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号：system 恒为 1（重算覆盖），self/manager 每次保存 +1（只追加留痕）',
    comment VARCHAR(1000) DEFAULT NULL COMMENT '评语（主观类必填）',
    not_applicable TINYINT NOT NULL DEFAULT 0
        COMMENT '是否不适用：1 不适用（D-06.5：经 HR/管理者显式标记的例外状态，按剩余权重归一化）',
    na_reason VARCHAR(500) DEFAULT NULL COMMENT '不适用原因（标记时必填）',
    na_marked_by VARCHAR(64) DEFAULT NULL COMMENT '标记人账号',
    na_marked_time DATETIME DEFAULT NULL COMMENT '标记时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_score_version (cycle_id, employee_id, template_item_id, rater_type, version),
    KEY idx_perf_score_employee (cycle_id, employee_id),
    KEY idx_perf_score_item (template_item_id),
    KEY idx_perf_score_rater (rater_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评分与归集明细表（system 行重算覆盖=幂等；self/manager 行只追加=R4 留痕；含 not_applicable 标记）';

CREATE TABLE IF NOT EXISTS perf_result (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '绩效结果 ID',
    cycle_id BIGINT NOT NULL COMMENT '绩效周期 ID（逻辑关联 perf_cycle.id）',
    employee_id BIGINT NOT NULL COMMENT '被评人用户 ID（只读引用 sys_user）',
    template_id BIGINT NOT NULL COMMENT '指标模板 ID（逻辑关联 perf_template.id）',
    total_score DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '加权总分（可归集取归集值，主观取上级评分；缺失/不适用按剩余权重归一化）',
    self_score DECIMAL(6,2) DEFAULT NULL COMMENT '自评口径加权总分（参考，不参与最终结果）',
    collect_score DECIMAL(6,2) DEFAULT NULL COMMENT '可归集项加权得分',
    subjective_score DECIMAL(6,2) DEFAULT NULL COMMENT '主观项加权得分（上级评分）',
    effective_weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '参与计算的权重合计（100 - 不适用权重）',
    na_weight DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '标记为不适用的权重合计',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '结果状态：draft 草稿，archived 已归档，locked 已锁定',
    archive_time DATETIME DEFAULT NULL COMMENT '归档时间',
    lock_time DATETIME DEFAULT NULL COMMENT '锁定时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_result_employee (cycle_id, employee_id),
    KEY idx_perf_result_employee (employee_id),
    KEY idx_perf_result_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绩效结果表（归档固化；锁定后只读，修改只能走 perf_appeal）';

CREATE TABLE IF NOT EXISTS perf_appeal (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '申诉记录 ID',
    cycle_id BIGINT NOT NULL COMMENT '绩效周期 ID（逻辑关联 perf_cycle.id）',
    result_id BIGINT NOT NULL COMMENT '绩效结果 ID（逻辑关联 perf_result.id）',
    employee_id BIGINT NOT NULL COMMENT '申诉人（被评人）用户 ID',
    appeal_reason VARCHAR(1000) NOT NULL COMMENT '申诉理由（必填）',
    appeal_status VARCHAR(16) NOT NULL DEFAULT 'pending'
        COMMENT '申诉状态：pending 待处理，approved 已通过，rejected 已驳回',
    before_score DECIMAL(6,2) DEFAULT NULL COMMENT '申诉前总分',
    after_score DECIMAL(6,2) DEFAULT NULL COMMENT '申诉通过后的调整分（可选）',
    handler_id BIGINT DEFAULT NULL COMMENT '处理人用户 ID',
    handler_name VARCHAR(64) DEFAULT NULL COMMENT '处理人账号',
    handle_comment VARCHAR(1000) DEFAULT NULL COMMENT '处理意见',
    handle_time DATETIME DEFAULT NULL COMMENT '处理时间',
    applied TINYINT NOT NULL DEFAULT 0 COMMENT '是否已应用到结果：1 已应用（锁定后正式修改的唯一通道）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_perf_appeal_cycle (cycle_id),
    KEY idx_perf_appeal_result (result_id),
    KEY idx_perf_appeal_status (appeal_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绩效申诉表（R4/R5：锁定后任何修改必须走申诉流程并留痕）';
