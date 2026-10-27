-- M12 集成与开放 · Webhook 与导入导出表结构（V40；只追加不改 V1–V39）
--
-- 依据：L0 §2.4.3 的 2.4.4 / 2.4.5；M12 RULE §四（前缀 `open_`）。
-- 范围：Webhook 订阅与投递留痕、导入导出批次审计。
-- 安全约定：
--   · Webhook 签名密钥由使用方在创建时提供，服务端用对称加密存储（复用 M12 凭据加密），
--     任何接口都不返回明文，列表只显示末 4 位提示位；
--   · 投递记录保存事件内容（载荷由领域事件构成），不额外查询业务表拼装；
--   · 导入导出批次只记录统计与错误摘要，不保存导入原文与导出全量数据。
-- 表注释刻意不写既有模块的表名/关键词。

CREATE TABLE IF NOT EXISTS open_webhook (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    webhook_code VARCHAR(64) NOT NULL COMMENT '订阅编码（唯一，用于识别接入方）',
    webhook_name VARCHAR(100) NOT NULL COMMENT '订阅名称',
    target_url VARCHAR(500) NOT NULL COMMENT '回调地址（仅 http/https）',
    event_types VARCHAR(500) NOT NULL COMMENT '订阅事件类型（逗号分隔；* 表示全部）',
    secret_cipher TEXT COMMENT '签名密钥密文（对称加密存储；明文不落库、不回读）',
    secret_hint VARCHAR(32) DEFAULT NULL COMMENT '密钥提示（仅末 4 位）',
    timeout_seconds INT NOT NULL DEFAULT 3 COMMENT '投递超时（秒）',
    status VARCHAR(16) NOT NULL DEFAULT 'enabled' COMMENT '状态：enabled 启用，disabled 停用',
    last_delivery_time DATETIME DEFAULT NULL COMMENT '最近一次投递时间',
    last_delivery_status VARCHAR(16) DEFAULT NULL COMMENT '最近一次投递结果：success / failed',
    last_delivery_message VARCHAR(500) DEFAULT NULL COMMENT '最近一次投递说明（失败时写原因）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_webhook_code (webhook_code),
    KEY idx_open_webhook_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Webhook 事件订阅表（签名密钥加密存储，失败可重试）';

CREATE TABLE IF NOT EXISTS open_webhook_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    webhook_id BIGINT NOT NULL COMMENT '订阅 ID',
    event_id VARCHAR(64) NOT NULL COMMENT '事件幂等键（同一订阅同一事件只投递一次）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    payload TEXT COMMENT '投递载荷（JSON，由领域事件内容构成）',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '投递状态：success 成功，failed 失败，pending 处理中',
    http_status INT DEFAULT NULL COMMENT '目标响应状态码',
    attempts INT NOT NULL DEFAULT 1 COMMENT '投递尝试次数',
    next_retry_at DATETIME DEFAULT NULL COMMENT '建议下次重试时间（指数退避）',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（不记录密钥）',
    response_snippet VARCHAR(500) DEFAULT NULL COMMENT '目标响应摘要（截断）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_webhook_delivery (webhook_id, event_id),
    KEY idx_open_webhook_delivery_status (status, next_retry_at),
    KEY idx_open_webhook_delivery_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Webhook 投递留痕表（幂等键唯一，失败可重试）';

CREATE TABLE IF NOT EXISTS open_transfer_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    direction VARCHAR(16) NOT NULL COMMENT '方向：import 导入，export 导出',
    biz_type VARCHAR(32) NOT NULL COMMENT '业务对象（如 tasks）',
    file_name VARCHAR(200) DEFAULT NULL COMMENT '文件名（导入的上传名 / 导出的下载名）',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '总行数',
    success_rows INT NOT NULL DEFAULT 0 COMMENT '成功行数',
    failed_rows INT NOT NULL DEFAULT 0 COMMENT '失败行数',
    status VARCHAR(16) NOT NULL DEFAULT 'success' COMMENT '批次结果：success / partial / failed',
    detail VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要（逐行失败原因，截断；不保存原文）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_open_transfer_log_direction (direction, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入导出批次审计表（逐行结果统计，不保存原文）';
