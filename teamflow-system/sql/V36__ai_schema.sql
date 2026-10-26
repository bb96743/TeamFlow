-- M11 AI 助手表结构（追加脚本，不改 V1–V35；依据 M11 RULE §四 与 L0 §9.1 D-2026-10-27-12）
--
-- 设计要点：
--   1) M11 只落「会话 / 消息 / 工具调用轨迹 / 报告草稿 / 提示词版本」，不落任何业务数据副本；
--   2) 知识问答的引用溯源以 JSON 数组存在消息行上（页面 ID + 标题 + 片段 + 偏移），本模块不建检索索引；
--   3) 报告只有草稿状态：本期无发布接口，避免 AI 产出未经人工确认就进入流转；
--   4) 提示词按 code + 版本号管理，历史版本保留，启用版由应用按最大启用版本读取。
-- 说明：表注释不引用其它模块的表名，避免生成器导入用例误判。

CREATE TABLE IF NOT EXISTS ai_session (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话 ID',
    session_title VARCHAR(200) NOT NULL COMMENT '会话标题（默认取首条用户消息摘要）',
    session_type VARCHAR(16) NOT NULL DEFAULT 'chat' COMMENT '会话类型：chat 对话，qa 知识问答，report 报告生成',
    owner_id BIGINT NOT NULL COMMENT '会话归属用户 ID（只读引用用户主数据）',
    owner_name VARCHAR(64) DEFAULT NULL COMMENT '会话归属用户账号（冗余展示）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '会话状态：active 进行中，archived 已归档',
    message_count INT NOT NULL DEFAULT 0 COMMENT '消息条数',
    last_message_time DATETIME DEFAULT NULL COMMENT '最近一条消息时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ai_session_owner (owner_id, status),
    KEY idx_ai_session_last_time (last_message_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话表（对话式工作助手的会话上下文索引）';

CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
    session_id BIGINT NOT NULL COMMENT '所属会话 ID（逻辑关联会话表）',
    role VARCHAR(16) NOT NULL COMMENT '角色：user 用户，assistant 助手，system 系统提示，tool 工具结果',
    content MEDIUMTEXT NOT NULL COMMENT '消息正文（只存本会话可见内容，不复制无权业务数据）',
    intent_code VARCHAR(64) DEFAULT NULL COMMENT '识别出的意图编码，如 task_query / perf_query',
    intent_label VARCHAR(100) DEFAULT NULL COMMENT '意图中文说明',
    tool_name VARCHAR(64) DEFAULT NULL COMMENT '本次调用的工具名（未调用则为空）',
    tool_params TEXT DEFAULT NULL COMMENT '工具入参（JSON 文本，已脱敏）',
    tool_result MEDIUMTEXT DEFAULT NULL COMMENT '工具返回摘要（JSON 文本，已按当前用户可见范围生成）',
    citations MEDIUMTEXT DEFAULT NULL COMMENT '引用溯源（JSON 数组：页面 ID、标题、命中片段、片段偏移）',
    model_name VARCHAR(64) DEFAULT NULL COMMENT '产出该消息的模型或规则实现标识',
    status VARCHAR(16) NOT NULL DEFAULT 'ok' COMMENT '消息状态：ok 正常，denied 无权限，error 执行失败',
    elapsed_ms INT DEFAULT NULL COMMENT '本次处理耗时（毫秒）',
    token_estimate INT DEFAULT NULL COMMENT '粗略 token 估算（用于成本控制，不做计费）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ai_message_session (session_id, id),
    KEY idx_ai_message_intent (intent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息表（对话上下文与引用溯源）';

CREATE TABLE IF NOT EXISTS ai_tool_call (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '工具调用 ID',
    session_id BIGINT DEFAULT NULL COMMENT '所属会话 ID',
    message_id BIGINT DEFAULT NULL COMMENT '关联助手消息 ID',
    tool_name VARCHAR(64) NOT NULL COMMENT '工具名，如 task.query / perf.query',
    tool_label VARCHAR(100) DEFAULT NULL COMMENT '工具中文说明',
    arguments TEXT DEFAULT NULL COMMENT '调用入参（JSON 文本）',
    permission_code VARCHAR(100) DEFAULT NULL COMMENT '执行所需权限码（为空表示登录即可）',
    allowed TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许执行：1 允许，0 被拒绝（无权限或参数不合法）',
    deny_reason VARCHAR(500) DEFAULT NULL COMMENT '拒绝原因（可读文案）',
    result_summary VARCHAR(2000) DEFAULT NULL COMMENT '返回摘要（只记录当前用户可见范围内信息）',
    elapsed_ms INT DEFAULT NULL COMMENT '执行耗时（毫秒）',
    operator_id BIGINT DEFAULT NULL COMMENT '调用者用户 ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '调用者账号',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ai_tool_call_session (session_id, id),
    KEY idx_ai_tool_call_tool (tool_name, create_time),
    KEY idx_ai_tool_call_operator (operator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 工具调用轨迹表（审计用途：谁、以什么权限、调用了什么）';

CREATE TABLE IF NOT EXISTS ai_report_draft (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '报告草稿 ID',
    report_type VARCHAR(16) NOT NULL COMMENT '报告类型：daily 日报，weekly 周报',
    report_date DATE DEFAULT NULL COMMENT '日报归属日期（周报为空）',
    period_start DATE DEFAULT NULL COMMENT '统计区间开始日期',
    period_end DATE DEFAULT NULL COMMENT '统计区间结束日期',
    report_title VARCHAR(200) NOT NULL COMMENT '报告标题',
    content MEDIUMTEXT NOT NULL COMMENT '报告正文（Markdown 草稿，可编辑）',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态：仅 draft 草稿（本期不提供发布接口）',
    source_summary VARCHAR(2000) DEFAULT NULL COMMENT '取数摘要（任务/目标/会议各取了多少条，便于核对）',
    owner_id BIGINT NOT NULL COMMENT '报告归属用户 ID',
    owner_name VARCHAR(64) DEFAULT NULL COMMENT '报告归属用户账号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ai_report_owner (owner_id, report_type, report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 报告草稿表（日报/周报，只产草稿不发布）';

CREATE TABLE IF NOT EXISTS ai_prompt_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '提示词 ID',
    prompt_code VARCHAR(64) NOT NULL COMMENT '提示词编码，如 intent_system / daily_report_system',
    prompt_name VARCHAR(100) NOT NULL COMMENT '提示词名称',
    version_no INT NOT NULL DEFAULT 1 COMMENT '版本号（同一编码下递增）',
    content TEXT NOT NULL COMMENT '提示词正文（支持 {{变量}} 占位）',
    scope VARCHAR(64) NOT NULL DEFAULT 'global' COMMENT '生效范围：global 全局',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '状态：active 生效，disabled 停用',
    builtin TINYINT NOT NULL DEFAULT 1 COMMENT '是否内置：1 内置，0 自定义',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_prompt_code_version (prompt_code, version_no),
    KEY idx_ai_prompt_code_status (prompt_code, status, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 提示词版本表（版本化、可回滚，避免散落在代码里）';
