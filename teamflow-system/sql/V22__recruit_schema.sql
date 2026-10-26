-- M08 招聘 · 表结构（V22；只追加不改 V1–V21）
--
-- 需求依据：L0 §2.4.3 M08 2.1.1–2.1.5
--   · 2.1.1 职位管理：JD、需求审批流（**复用 M03 流程实例**，不另建引擎）、招聘负责人
--   · 2.1.2 候选人库：简历入库（**文件走 G-1 sys_file，本模块只存 file_id**）、标签、版本、查重
--   · 2.1.3 面试安排与评价：轮次、面试官、结构化评价（每维度独立评分+评语）
--   · 2.1.4 Offer 与入职：模板、审批链、入职转 sys_user（**不建用户表**）
--   · 2.1.5 招聘看板：指标口径须在 M13 指标字典登记（运行时经 M13 Service 登记，本脚本不写 insight_*）
--
-- 硬约束落地：
--   ❌ 不建用户表：候选人 `name_snapshot` 是**外部候选人报名姓名快照**（候选人不是 sys_user），
--      入职只写 `recruit_candidate.hired_user_id` 引用 sys_user.id；不复制昵称/账号/部门。
--   ❌ 不做文件存储：`recruit_resume.file_id` 只存 sys_file.id，简历正文/文件永远在 M00。
--   ❌ 不自建流程引擎：本脚本**不建任何流程表**；需求审批与 Offer 审批复用 M03 的
--      flow_definition / flow_instance / flow_task / flow_node_instance（实例类型 job_approval / offer_approval）。
--   ❌ 简历解析/智能筛查（依赖 M11）本期不做：`recruit_resume` 预留 parse_status / parse_result /
--      screening_score / screening_reason 字段，本期只置 pending，不参与任何决策。
--
-- 表前缀：`recruit_`（L0 §6.2：业务表前缀 = 模块代号；M08 RULE 早期写的 hr_ 与 L0 冲突，按 L0 §1.3 以 L0 为准，
--   已在 M08 RULE §四 更正并登记）。
-- 审计：所有业务表均带 create_by/update_by/create_time/update_time + 逻辑删除 deleted。

CREATE TABLE IF NOT EXISTS recruit_job (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '职位 ID',
    job_code VARCHAR(64) NOT NULL COMMENT '职位编码（唯一）',
    job_title VARCHAR(150) NOT NULL COMMENT '职位名称',
    job_desc TEXT DEFAULT NULL COMMENT '职位描述 JD',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（只读引用 sys_dept）',
    headcount INT NOT NULL DEFAULT 1 COMMENT '需求人数',
    job_level VARCHAR(32) DEFAULT NULL COMMENT '职级（如 P6/M2）',
    salary_range VARCHAR(64) DEFAULT NULL COMMENT '薪资范围（展示文案，不做结构化）',
    city VARCHAR(64) DEFAULT NULL COMMENT '工作城市',
    owner_id BIGINT DEFAULT NULL COMMENT '招聘负责人用户 ID（只读引用 sys_user）',
    status VARCHAR(24) NOT NULL DEFAULT 'draft'
        COMMENT '职位状态：draft 草稿 / pending_approval 审批中 / recruiting 招聘中 / paused 暂停 / closed 已关闭',
    approval_state VARCHAR(24) NOT NULL DEFAULT 'none'
        COMMENT '需求审批状态：none 未提交 / pending 审批中 / approved 已通过 / rejected 已驳回',
    approval_instance_id BIGINT DEFAULT NULL COMMENT '需求审批流程实例 ID（M03 flow_instance.id，本模块只存引用）',
    publish_time DATETIME DEFAULT NULL COMMENT '发布（审批通过可招聘）时间',
    close_time DATETIME DEFAULT NULL COMMENT '关闭时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_job_code (job_code),
    KEY idx_recruit_job_status (status),
    KEY idx_recruit_job_dept (dept_id),
    KEY idx_recruit_job_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='招聘职位表（需求审批复用 M03 流程实例，本表只存实例引用）';

CREATE TABLE IF NOT EXISTS recruit_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '候选人 ID',
    candidate_code VARCHAR(64) NOT NULL COMMENT '候选人编码（唯一）',
    job_id BIGINT NOT NULL COMMENT '应聘职位 ID（逻辑关联 recruit_job.id）',
    name_snapshot VARCHAR(100) NOT NULL COMMENT '报名姓名快照（外部候选人；不是 sys_user 副本，入职只引用 hired_user_id）',
    phone VARCHAR(32) DEFAULT NULL COMMENT '手机号（敏感，列表默认脱敏）',
    email VARCHAR(128) DEFAULT NULL COMMENT '邮箱（敏感，列表默认脱敏）',
    phone_norm VARCHAR(32) DEFAULT NULL COMMENT '归一化手机号（查重键组成部分）',
    email_norm VARCHAR(128) DEFAULT NULL COMMENT '归一化邮箱（查重键组成部分）',
    dedup_key VARCHAR(200) DEFAULT NULL COMMENT '查重键 = phone_norm|email_norm（唯一；两者都空时为 NULL 不参与唯一约束）',
    source_channel VARCHAR(32) NOT NULL DEFAULT 'other'
        COMMENT '来源渠道：referral 内推 / website 官网 / social 社交招聘 / agency 猎头 / internal 内部转岗 / other 其他',
    tags VARCHAR(500) DEFAULT NULL COMMENT '标签（逗号分隔，快速筛选）',
    current_stage VARCHAR(24) NOT NULL DEFAULT 'applied'
        COMMENT '当前阶段：applied 投递 / screening 初筛 / interview 面试 / offer Offer / hired 入职 / rejected 淘汰',
    stage_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '进入当前阶段时间',
    reject_reason VARCHAR(500) DEFAULT NULL COMMENT '淘汰原因（淘汰时必填）',
    hired_user_id BIGINT DEFAULT NULL COMMENT '入职后生成的用户 ID（M00 sys_user.id，唯一幂等）',
    hire_time DATETIME DEFAULT NULL COMMENT '入职时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_candidate_code (candidate_code),
    UNIQUE KEY uk_recruit_candidate_dedup (dedup_key),
    KEY idx_recruit_candidate_job (job_id),
    KEY idx_recruit_candidate_stage (current_stage),
    KEY idx_recruit_candidate_channel (source_channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人表（外部候选人档案；查重键 phone+email 归一化唯一）';

CREATE TABLE IF NOT EXISTS recruit_resume (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '简历版本 ID',
    candidate_id BIGINT NOT NULL COMMENT '候选人 ID（逻辑关联 recruit_candidate.id）',
    file_id BIGINT NOT NULL COMMENT '简历文件 ID（**只存 M00 sys_file.id，本模块不存文件正文/路径**）',
    file_name VARCHAR(255) DEFAULT NULL COMMENT '上传时文件名快照（仅用于展示）',
    version INT NOT NULL DEFAULT 1 COMMENT '简历版本号（同一候选人递增，唯一）',
    parse_status VARCHAR(16) NOT NULL DEFAULT 'pending'
        COMMENT '解析状态：pending 待解析 / parsed 已解析 / failed 失败（解析能力由 M11 提供，本期不做，恒为 pending）',
    parse_result TEXT DEFAULT NULL COMMENT '预留：M11 简历解析结果（结构化 JSON），本期不写',
    screening_score DECIMAL(6,2) DEFAULT NULL COMMENT '预留：M11 JD 匹配分（占位）',
    screening_reason TEXT DEFAULT NULL COMMENT '预留：M11 匹配命中原因（可解释筛查；本期不写）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_resume_version (candidate_id, version),
    KEY idx_recruit_resume_file (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历版本表（文件在 M00 统一文件存储，只存 file_id；解析/筛查字段为 M11 预留）';

CREATE TABLE IF NOT EXISTS recruit_interview (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '面试轮次 ID',
    candidate_id BIGINT NOT NULL COMMENT '候选人 ID（逻辑关联 recruit_candidate.id）',
    job_id BIGINT NOT NULL COMMENT '职位 ID（冗余便于按职位检索）',
    round_no INT NOT NULL DEFAULT 1 COMMENT '面试轮次序号（同一候选人递增）',
    round_name VARCHAR(64) DEFAULT NULL COMMENT '轮次名称（如 技术面 / HR 面）',
    interviewer_id BIGINT NOT NULL COMMENT '面试官用户 ID（只读引用 sys_user）',
    scheduled_time DATETIME NOT NULL COMMENT '安排时间（与 M06 日历互通属 M3/M6 排期，本期只存时间不建日程）',
    duration_minutes INT NOT NULL DEFAULT 60 COMMENT '时长（分钟）',
    location VARCHAR(200) DEFAULT NULL COMMENT '地点 / 线上会议链接',
    status VARCHAR(16) NOT NULL DEFAULT 'scheduled'
        COMMENT '面试状态：scheduled 已安排 / completed 已完成 / cancelled 已取消',
    result VARCHAR(16) NOT NULL DEFAULT 'pending'
        COMMENT '面试结论：pending 待定 / pass 通过 / fail 不通过（由人操作并留痕，R3）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_interview_round (candidate_id, round_no),
    KEY idx_recruit_interview_job (job_id),
    KEY idx_recruit_interview_interviewer (interviewer_id),
    KEY idx_recruit_interview_time (scheduled_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试轮次表（轮次编排 + 面试官分配；日程能力归 M06，本期只存时间）';

CREATE TABLE IF NOT EXISTS recruit_interview_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '面试评价维度 ID',
    interview_id BIGINT NOT NULL COMMENT '面试轮次 ID（逻辑关联 recruit_interview.id）',
    dimension_code VARCHAR(64) NOT NULL COMMENT '评价维度编码（如 skill / communication / potential）',
    dimension_name VARCHAR(100) NOT NULL COMMENT '评价维度名称',
    score DECIMAL(5,2) NOT NULL COMMENT '该维度评分（0-100）',
    comment VARCHAR(1000) NOT NULL COMMENT '该维度评语（R4：禁止只填总分，每维度必须评语）',
    interviewer_id BIGINT DEFAULT NULL COMMENT '评价人用户 ID',
    submit_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_feedback_dimension (interview_id, dimension_code),
    KEY idx_recruit_feedback_interview (interview_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试结构化评价表（每维度独立评分与评语，R4）';

CREATE TABLE IF NOT EXISTS recruit_offer_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Offer 模板 ID',
    template_code VARCHAR(64) NOT NULL COMMENT '模板编码（唯一）',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    content TEXT NOT NULL COMMENT '模板正文（含占位符说明，发放时人工确认）',
    status VARCHAR(16) NOT NULL DEFAULT 'enabled' COMMENT '状态：enabled 启用 / disabled 停用',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_offer_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Offer 模板表';

CREATE TABLE IF NOT EXISTS recruit_offer (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Offer ID',
    candidate_id BIGINT NOT NULL COMMENT '候选人 ID（逻辑关联 recruit_candidate.id；同一候选人仅一份 Offer）',
    job_id BIGINT NOT NULL COMMENT '职位 ID',
    template_id BIGINT DEFAULT NULL COMMENT 'Offer 模板 ID（逻辑关联 recruit_offer_template.id）',
    salary DECIMAL(12,2) DEFAULT NULL COMMENT 'offer 薪资（月薪，单位元）',
    expected_onboard_date DATE DEFAULT NULL COMMENT '期望入职日期',
    status VARCHAR(24) NOT NULL DEFAULT 'draft'
        COMMENT 'Offer 状态：draft 草稿 / pending_approval 审批中 / approved 已批准 / rejected 已驳回 / sent 已发送 / accepted 已接受 / declined 已拒绝 / onboarded 已入职',
    approval_state VARCHAR(24) NOT NULL DEFAULT 'none'
        COMMENT 'Offer 审批状态：none / pending / approved / rejected',
    approval_instance_id BIGINT DEFAULT NULL COMMENT 'Offer 审批流程实例 ID（M03 flow_instance.id）',
    sent_time DATETIME DEFAULT NULL COMMENT '发送时间',
    accepted_time DATETIME DEFAULT NULL COMMENT '候选人答复时间',
    declined_reason VARCHAR(500) DEFAULT NULL COMMENT '拒绝原因',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recruit_offer_candidate (candidate_id),
    KEY idx_recruit_offer_job (job_id),
    KEY idx_recruit_offer_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Offer 表（审批链复用 M03 流程实例；入职转平台用户，不建用户表）';

-- 默认 Offer 模板（幂等：固定 template_code + INSERT IGNORE）
INSERT IGNORE INTO recruit_offer_template (template_code, template_name, content, status, remark, create_by, update_by)
VALUES ('std_offer_v1', '标准 Offer 模板',
        '【Offer 通知】\n{{候选人姓名}} 您好：\n经面试评估，我们诚挚邀请您加入 {{公司部门}}，担任 {{职位名称}}。\n薪资：{{月薪}} 元/月；期望入职日期：{{入职日期}}。\n如接受，请回复确认；本 Offer 以最终审批通过版本为准。',
        'enabled', 'M08 种子模板（V22）：占位符由业务在发放前人工确认，本期不做模板引擎',
        'system', 'system');
