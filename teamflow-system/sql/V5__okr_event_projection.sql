-- G-3 领域事件派生投影（M02 订阅 M03 的 TaskStatusChangedEvent）
-- 依据 L0 §9.1 D-2026-10-25-05：
--   权威关联在 M03（task_task.kr_id）；本表仅为 M02 侧「派生投影」，
--   **只由事件驱动写入/更新，不手工维护，可随时由事件重放重建**。
-- 进度口径依据 L0 §9.1 D-2026-10-25-03：
--   任务驱动型 = 已完成顶层任务数 / 关联顶层任务总数；子任务（is_root=0）只入投影、不计入分母。
-- 幂等设计：
--   ① okr_task_event_log.event_id 唯一键 → 同一事件重复投递直接跳过；
--   ② okr_kr_task_link 唯一键 (kr_id, task_id) → 同一任务对同一 KR 只有一行，状态覆盖而非累加；
--   ③ KR 进度按投影表 COUNT 重算（计算式而非累加式），天然幂等。
-- 说明：本脚本为 V5 追加脚本（G-4 规则：只追加 V5+，禁改 V1–V4）。

CREATE TABLE IF NOT EXISTS okr_kr_task_link (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    kr_id BIGINT NOT NULL COMMENT '关键结果 ID（逻辑关联 okr_key_result.id）',
    task_id BIGINT NOT NULL COMMENT '任务 ID（权威数据在 M03 task_task，本表只存引用）',
    is_root TINYINT NOT NULL DEFAULT 1 COMMENT '是否顶层任务：1 顶层（计入进度分母），0 子任务（不计入）',
    task_status VARCHAR(32) DEFAULT NULL COMMENT '任务当前状态（来自 M03 事件）',
    completed_at DATETIME DEFAULT NULL COMMENT '任务完成时间（非完成态为 NULL）',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人（事件驱动固定 event）',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人（事件驱动固定 event）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_okr_kr_task_link_pair (kr_id, task_id),
    KEY idx_okr_kr_task_link_task (task_id),
    KEY idx_okr_kr_task_link_progress (kr_id, is_root, task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='KR↔任务派生投影表（仅由 M03 领域事件写入，可重放重建）';

CREATE TABLE IF NOT EXISTS okr_task_event_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id VARCHAR(64) NOT NULL COMMENT '事件幂等键（UUID）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（类名）',
    task_id BIGINT NOT NULL COMMENT '任务 ID',
    kr_id BIGINT DEFAULT NULL COMMENT '关联 KR ID（可空）',
    root_task_id BIGINT DEFAULT NULL COMMENT '顶层任务 ID',
    is_root TINYINT NOT NULL DEFAULT 1 COMMENT '是否顶层任务',
    from_status VARCHAR(32) DEFAULT NULL COMMENT '变更前状态',
    to_status VARCHAR(32) DEFAULT NULL COMMENT '变更后状态',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    occurred_at DATETIME DEFAULT NULL COMMENT '事件发生时间',
    process_result VARCHAR(32) NOT NULL DEFAULT 'received'
        COMMENT '处理结果：applied/skipped_no_kr/skipped_kr_missing/skipped_not_task_driven/failed',
    progress_after DECIMAL(6,2) DEFAULT NULL COMMENT '处理后的 KR 进度',
    reason VARCHAR(500) DEFAULT NULL COMMENT '处理说明（跳过/失败原因）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_okr_task_event_log_event (event_id),
    KEY idx_okr_task_event_log_task (task_id),
    KEY idx_okr_task_event_log_kr (kr_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M02 任务事件幂等日志（event_id 唯一去重；日志表仅保留 create_time，不设审计四字段）';
