-- M11 AI 助手 · 待办建议与智能提醒表结构（V42；只追加不改 V1–V41）
--
-- 依据：L0 §2.4.3 的 1.3.5（AI 待办建议，只建议不派发）/ 1.3.6（智能提醒，触达由 M09 执行）。
-- 说明：
--   · ai_suggestion 只存「建议 + 可解释依据」，本模块没有任何自动派发路径；
--   · ai_reminder 只存「判定结果 + 理由 + M09 通知 ID」，实际触达由 M09 统一通知入口完成；
--   · 两张表都不保存业务数据副本，任务/用户信息按需实时经各模块 Service 读取。
-- 表注释刻意不写既有模块的表名/关键词。

CREATE TABLE IF NOT EXISTS ai_suggestion (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '建议 ID',
    suggestion_type VARCHAR(32) NOT NULL COMMENT '建议类型：task_assign 任务分配建议',
    biz_type VARCHAR(32) NOT NULL COMMENT '业务类型：task',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（任务 ID）',
    current_assignee_id BIGINT DEFAULT NULL COMMENT '当前负责人用户 ID（可空=待认领）',
    suggested_user_id BIGINT NOT NULL COMMENT '建议负责人用户 ID',
    reason VARCHAR(1000) NOT NULL COMMENT '人类可读理由（可解释性要求）',
    evidence_json TEXT COMMENT '判定依据（候选负载等结构化数据，供审计与前端展示）',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态：pending 待处理，expired 已过期',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_suggestion_biz (suggestion_type, biz_type, biz_id),
    KEY idx_ai_suggestion_user (suggested_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 待办建议表（只存建议与理由，不自动派发）';

CREATE TABLE IF NOT EXISTS ai_reminder (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '提醒 ID',
    reminder_type VARCHAR(32) NOT NULL COMMENT '提醒类型：task_due 任务到期提醒',
    biz_type VARCHAR(32) NOT NULL COMMENT '业务类型：task',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（任务 ID）',
    receiver_id BIGINT NOT NULL COMMENT '提醒接收人用户 ID（任务责任人）',
    remind_date DATE NOT NULL COMMENT '提醒日期（用于每日去重）',
    due_date DATE DEFAULT NULL COMMENT '业务到期日期',
    reason VARCHAR(1000) NOT NULL COMMENT '人类可读判定理由（为什么提醒、提醒谁）',
    notify_status VARCHAR(16) NOT NULL DEFAULT 'sent' COMMENT '触达状态：sent 已投递，duplicated 已存在，failed 失败',
    notification_id BIGINT DEFAULT NULL COMMENT '统一通知记录 ID（触达由通知模块执行，本表只存引用）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_reminder_daily (reminder_type, biz_type, biz_id, receiver_id, remind_date),
    KEY idx_ai_reminder_receiver (receiver_id, remind_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能提醒判定表（触达由通知模块执行，本表只存判定与引用）';
