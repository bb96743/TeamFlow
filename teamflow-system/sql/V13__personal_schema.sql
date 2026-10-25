-- M10 个人效率模块表结构（追加脚本，不修改 V1–V12；依据 M10 RULE §四 数据归属）
-- 依据 L0 §9.1：
--   D-2026-10-26-07：M10 定义扩展点 TodoBizHandler（bizType → 处理方法），业务模块自行实现并注册 Bean，
--                    M10 只依赖接口；投递统一走 internal/push（R1），禁止直写 todo_item；
--                    未登记 handler 的 bizType 允许入库、处理时拒绝。
-- 表前缀：todo_（待办）、time_（工时）、review_（复盘），属 L0 §2.1 表前缀例外清单已登记项；
--        focus_session 沿用 M10 RULE §四 规划表名（前缀 focus_ 未列入 §2.1 例外清单，
--        已在交付 CR 中建议领导者补登或改名，见 CR 说明）。
-- 审计：所有业务表均带 create_by/update_by/create_time/update_time + 逻辑删除 deleted。
-- 幂等（R2）：todo_item 唯一键 (biz_type, biz_id, user_id, action_type) —— 同一业务事项对同一用户
--            同一动作终身只有一行；重复投递只刷新**未处理**行的展示字段，已处理行既不改也不重开（R4）。

CREATE TABLE IF NOT EXISTS todo_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '待办 ID',
    biz_type VARCHAR(64) NOT NULL COMMENT '业务类型（集中登记：task_assign / task_approval / meeting_invite）',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（回源到所属模块；本表只存索引，不存业务快照）',
    user_id BIGINT NOT NULL COMMENT '待办所属用户（只读引用 sys_user）',
    action_type VARCHAR(32) NOT NULL DEFAULT 'handle' COMMENT '待办要求的动作类型（幂等键组成部分）：start 开始 / approve 审批 / handle 通用',
    title VARCHAR(200) NOT NULL COMMENT '展示用标题（投递方提供，可变，禁止作为业务判断依据）',
    action_url VARCHAR(500) NOT NULL COMMENT '回源前端路由（R5：由投递方提供，本模块不猜测路径）',
    due_time DATETIME DEFAULT NULL COMMENT '截止时间（可空）',
    priority VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '优先级：low 低，normal 普通，high 高，urgent 紧急',
    status VARCHAR(16) NOT NULL DEFAULT 'open' COMMENT '待办状态：open 未处理，handled 已完成，ignored 已忽略',
    handle_time DATETIME DEFAULT NULL COMMENT '处理时间（未处理为 NULL）',
    handle_by BIGINT DEFAULT NULL COMMENT '处理人用户 ID',
    handle_result VARCHAR(32) DEFAULT NULL COMMENT '处理结果：closed 原模块已回写并关闭 / rejected 原模块拒绝',
    source_event_id VARCHAR(64) DEFAULT NULL COMMENT '投递方追踪号/事件 ID（仅追溯，不参与去重）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人（投递方）',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（响应时长口径起点）',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_todo_item_biz (biz_type, biz_id, user_id, action_type),
    KEY idx_todo_item_user_status (user_id, status, due_time),
    KEY idx_todo_item_biz (biz_type, biz_id),
    KEY idx_todo_item_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一待办索引表（跨模块聚合；只存索引 + 回源链接，禁止作为业务数据源）';

CREATE TABLE IF NOT EXISTS todo_handle_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '处理日志 ID',
    todo_id BIGINT NOT NULL COMMENT '待办 ID（逻辑关联 todo_item.id）',
    biz_type VARCHAR(64) NOT NULL COMMENT '业务类型',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID',
    user_id BIGINT NOT NULL COMMENT '操作用户 ID',
    action_type VARCHAR(32) NOT NULL COMMENT '待办动作类型',
    action VARCHAR(16) NOT NULL COMMENT '用户动作：complete 完成，ignore 忽略',
    result VARCHAR(16) NOT NULL COMMENT '处理结果：closed 已回写并关闭，rejected 原模块拒绝，failed 原模块异常',
    handler VARCHAR(128) DEFAULT NULL COMMENT '命中的扩展点实现类（未登记时为空）',
    reason VARCHAR(500) DEFAULT NULL COMMENT '原因/异常摘要',
    cost_ms INT DEFAULT NULL COMMENT '处理耗时（毫秒）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_todo_handle_log_todo (todo_id),
    KEY idx_todo_handle_log_biz (biz_type, biz_id),
    KEY idx_todo_handle_log_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待办处理日志（R3：记录每次处理的回写结果，失败也留痕）';

CREATE TABLE IF NOT EXISTS time_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '工时记录 ID',
    task_id BIGINT NOT NULL COMMENT '关联任务 ID（R6：工时以任务为粒度，禁止裸工时；逻辑关联 M03 task_task.id）',
    user_id BIGINT NOT NULL COMMENT '填报人用户 ID（只读引用 sys_user）',
    work_date DATE NOT NULL COMMENT '工作日期',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间（可选）',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间（可选）',
    duration_minutes INT NOT NULL COMMENT '工时分钟数（必须大于 0）',
    description VARCHAR(500) DEFAULT NULL COMMENT '工作内容说明',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_time_log_task (task_id),
    KEY idx_time_log_user_date (user_id, work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务工时记录表（任务级计时填报，供成本核算；必须关联 task_id）';

CREATE TABLE IF NOT EXISTS focus_session (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '专注会话 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID（只读引用 sys_user）',
    task_id BIGINT DEFAULT NULL COMMENT '关联任务 ID（可选；逻辑关联 M03 task_task.id）',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间（进行中为空）',
    duration_minutes INT NOT NULL DEFAULT 0 COMMENT '实际专注分钟数',
    interrupt_count INT NOT NULL DEFAULT 0 COMMENT '打断次数',
    status VARCHAR(16) NOT NULL DEFAULT 'running' COMMENT '会话状态：running 进行中，finished 已完成，abandoned 已放弃',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_focus_session_user_time (user_id, start_time),
    KEY idx_focus_session_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专注会话表（番茄钟后端留痕；免打扰前端交互不在本期）';

CREATE TABLE IF NOT EXISTS review_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '复盘记录 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID（只读引用 sys_user）',
    review_type VARCHAR(16) NOT NULL DEFAULT 'week' COMMENT '复盘类型：day 日，week 周，month 月，custom 自定义',
    review_title VARCHAR(200) NOT NULL COMMENT '复盘标题',
    period_start DATE DEFAULT NULL COMMENT '复盘周期开始日期',
    period_end DATE DEFAULT NULL COMMENT '复盘周期结束日期',
    content TEXT DEFAULT NULL COMMENT '复盘正文（默认私有；公开归档 M07 时只存 kb_page_id）',
    tags VARCHAR(500) DEFAULT NULL COMMENT '标签（逗号分隔，便于检索）',
    visibility VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT '可见性：private 私有，archived 已归档公开（正文归 M07）',
    kb_page_id BIGINT DEFAULT NULL COMMENT '归档到 M07 的页面 ID（逻辑关联 kb_page.id；本期不写）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_review_log_user_time (user_id, create_time),
    KEY idx_review_log_type (review_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复盘与成长日志表（默认私有存 personal_*；公开时正文归 M07，本表只存 kb_page_id）';
