-- M12 集成与开放 · 表结构（V38；只追加不改 V1–V37）
--
-- 依据：L0 §9.1 D-2026-10-27-12 结论四/五/六；M12 RULE §四（表前缀 `open_`）。
-- 范围：2.4.1 第三方集成中心（配置 + 凭据加密存储 + 连通性检测框架）、
--       2.4.3 开放 API（API Key 管理，Key 只存哈希与前缀）。
-- 不建表：Webhook 订阅与外发记录（2.4.4 排 M5）；导入导出（2.4.5 排 M5）。
-- 安全约定：
--   · 凭据只存 AES-GCM 密文（credential_cipher），明文不回读、不落日志；credential_hint 仅存末 4 位用于展示。
--   · API Key 只存 SHA-256 哈希（key_hash）与前缀（key_prefix），明文只在创建响应返回一次。
-- 表注释刻意不写既有模块的表名/前缀，避免被代码生成器的表名字段检索误命中。

CREATE TABLE IF NOT EXISTS open_app (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_code VARCHAR(64) NOT NULL COMMENT '接入应用编码（唯一）',
    app_name VARCHAR(100) NOT NULL COMMENT '接入应用名称',
    app_type VARCHAR(16) NOT NULL DEFAULT 'open_client'
        COMMENT '接入类型：dingtalk / wecom / feishu / github / jira / open_client',
    endpoint_url VARCHAR(500) DEFAULT NULL COMMENT '接入地址（连通性检测的目标地址）',
    config_json TEXT COMMENT '非敏感接入配置（JSON；不得写入任何密钥/令牌）',
    credential_cipher TEXT COMMENT '凭据密文（对称加密后存储；明文不落库、不回读）',
    credential_hint VARCHAR(32) DEFAULT NULL COMMENT '凭据提示（仅末 4 位）',
    status VARCHAR(16) NOT NULL DEFAULT 'enabled' COMMENT '状态：enabled 启用，disabled 停用',
    last_check_time DATETIME DEFAULT NULL COMMENT '最近一次连通性检测时间',
    last_check_result VARCHAR(16) DEFAULT NULL COMMENT '最近一次检测结果：success / failed',
    last_check_message VARCHAR(500) DEFAULT NULL COMMENT '最近一次检测说明（失败时写明原因）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_app_code (app_code),
    KEY idx_open_app_type (app_type),
    KEY idx_open_app_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放平台接入应用（第三方集成配置与开放调用方登记）';

CREATE TABLE IF NOT EXISTS open_api_key (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_id BIGINT DEFAULT NULL COMMENT '所属接入应用 ID（可空）',
    key_name VARCHAR(100) NOT NULL COMMENT '密钥名称（用途说明）',
    key_prefix VARCHAR(16) NOT NULL COMMENT '密钥前缀（仅用于展示与识别，非敏感）',
    key_hash VARCHAR(128) NOT NULL COMMENT '密钥哈希（SHA-256；禁止明文存储）',
    user_id BIGINT NOT NULL COMMENT '绑定用户 ID（调用时以该用户身份与权限执行）',
    scope VARCHAR(1000) DEFAULT NULL COMMENT '权限范围（逗号分隔权限码；为空表示仅受绑定用户权限约束）',
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间（空表示长期有效）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '状态：active 有效，revoked 已撤销',
    last_used_at DATETIME DEFAULT NULL COMMENT '最近一次使用时间',
    call_count BIGINT NOT NULL DEFAULT 0 COMMENT '累计调用次数',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_api_key_hash (key_hash),
    KEY idx_open_api_key_user (user_id),
    KEY idx_open_api_key_app (app_id),
    KEY idx_open_api_key_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放 API 密钥（只存哈希与前缀，明文仅在创建时返回一次）';

CREATE TABLE IF NOT EXISTS open_event_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    channel VARCHAR(16) NOT NULL COMMENT '调用渠道：open_api 开放接口，mcp 智能体工具',
    key_id BIGINT DEFAULT NULL COMMENT '调用所用密钥 ID',
    app_id BIGINT DEFAULT NULL COMMENT '接入应用 ID',
    user_id BIGINT DEFAULT NULL COMMENT '实际执行用户 ID',
    method VARCHAR(8) DEFAULT NULL COMMENT '请求方法（开放接口）或工具调用标记',
    path VARCHAR(255) DEFAULT NULL COMMENT '请求路径或工具名称',
    status INT DEFAULT NULL COMMENT '响应状态（HTTP 状态码或工具结果码）',
    cost_ms INT DEFAULT NULL COMMENT '耗时（毫秒）',
    client_ip VARCHAR(64) DEFAULT NULL COMMENT '调用方地址',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '请求追踪号',
    detail VARCHAR(500) DEFAULT NULL COMMENT '附加说明（失败原因等；不记录任何密钥/凭据内容）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_open_event_log_key (key_id, id),
    KEY idx_open_event_log_channel (channel, id),
    KEY idx_open_event_log_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放平台调用审计日志（每次开放接口与工具调用记录一条）';
