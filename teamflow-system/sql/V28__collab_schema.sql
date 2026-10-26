-- M09 协作与消息（M3-A）表结构（V28；只追加不改 V1–V27）
-- 依据 L0 §9.1：
--   D-2026-10-25-02：本模块持有**全站唯一评论存储**（多态挂载业务对象），其他模块不得自建评论表；
--   D-2026-10-26-11：结论二评论归属、结论四通知只投递不代劳、结论五动态流只由领域事件投影生成。
-- 前缀：msg_（消息/公告/通知）、collab_（评论/动态，属 §2.1 已登记的跨域特例）。
-- 审计：业务表均带 create_by/update_by/create_time/update_time + 逻辑删除 deleted。
-- 约束说明：通知投递只写本模块消息与通知；进入个人待办收件箱必须经 M10 的统一投递入口。
-- 表注释刻意不写既有表名，避免代码生成器的表名检索被误命中。

CREATE TABLE IF NOT EXISTS msg_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话 ID',
    conversation_type VARCHAR(16) NOT NULL DEFAULT 'direct' COMMENT '会话类型：direct 单聊，group 群组',
    title VARCHAR(100) DEFAULT NULL COMMENT '群组名称（单聊为空，展示时由对方昵称决定）',
    creator_id BIGINT DEFAULT NULL COMMENT '创建人用户 ID',
    member_count INT NOT NULL DEFAULT 0 COMMENT '在册成员数（冗余计数，成员变更时维护）',
    last_message_id BIGINT DEFAULT NULL COMMENT '最近一条消息 ID',
    last_message_time DATETIME DEFAULT NULL COMMENT '最近消息时间（会话列表排序用）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '会话状态：active 正常，closed 已关闭',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_msg_conversation_time (last_message_time),
    KEY idx_msg_conversation_type (conversation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表（单聊/群组）';

CREATE TABLE IF NOT EXISTS msg_conversation_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '成员用户 ID',
    member_role VARCHAR(16) NOT NULL DEFAULT 'member' COMMENT '会话内角色：owner 群主，member 成员',
    join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    leave_time DATETIME DEFAULT NULL COMMENT '离开时间（为空表示在册）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_conversation_member (conversation_id, user_id),
    KEY idx_msg_conversation_member_user (user_id, leave_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话成员表（在册判定以离开时间为空为准）';

CREATE TABLE IF NOT EXISTS msg_read (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    last_read_message_id BIGINT NOT NULL DEFAULT 0 COMMENT '已读到的消息 ID（按会话+用户记录，避免逐条写放大）',
    read_time DATETIME DEFAULT NULL COMMENT '最近一次已读时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_read (conversation_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话已读游标表';

CREATE TABLE IF NOT EXISTS msg_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
    conversation_id BIGINT NOT NULL COMMENT '会话 ID',
    sender_id BIGINT NOT NULL COMMENT '发送人用户 ID',
    content_type VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '内容类型：text 文本，system 系统提示',
    content VARCHAR(2000) NOT NULL COMMENT '消息正文',
    action_url VARCHAR(500) DEFAULT NULL COMMENT '回源链接（可为空）',
    biz_type VARCHAR(64) DEFAULT NULL COMMENT '关联业务类型（可为空，只存标识不复制业务数据）',
    biz_id BIGINT DEFAULT NULL COMMENT '关联业务单据 ID（可为空）',
    mentioned_user_ids VARCHAR(500) DEFAULT NULL COMMENT '提及的用户 ID 列表（逗号分隔）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_msg_message_conversation (conversation_id, id),
    KEY idx_msg_message_sender (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内消息表（全站唯一消息存储）';

CREATE TABLE IF NOT EXISTS msg_mention (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_type VARCHAR(16) NOT NULL COMMENT '来源类型：message 消息，comment 评论',
    source_id BIGINT NOT NULL COMMENT '来源 ID（消息 ID 或评论 ID）',
    conversation_id BIGINT DEFAULT NULL COMMENT '会话 ID（消息来源时有值）',
    mentioned_user_id BIGINT NOT NULL COMMENT '被提及用户 ID',
    biz_type VARCHAR(64) DEFAULT NULL COMMENT '业务类型（评论来源时有值）',
    biz_id BIGINT DEFAULT NULL COMMENT '业务单据 ID（评论来源时有值）',
    action_url VARCHAR(500) DEFAULT NULL COMMENT '回源链接',
    notify_status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '通知状态：pending 待投递，notified 已投递，skipped 跳过',
    idempotency_key VARCHAR(160) NOT NULL COMMENT '幂等键（来源+用户，保证同一提及只处理一次）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_mention_idem (idempotency_key),
    KEY idx_msg_mention_user (mentioned_user_id, notify_status),
    KEY idx_msg_mention_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提及记录表（@ 提及解析结果与投递状态）';

CREATE TABLE IF NOT EXISTS msg_notification (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知 ID',
    receiver_id BIGINT NOT NULL COMMENT '接收人用户 ID',
    notify_type VARCHAR(24) NOT NULL COMMENT '通知类型：mention 提及，announcement 公告，message 私信提醒，system 系统',
    title VARCHAR(200) NOT NULL COMMENT '通知标题（由模板渲染，不在业务代码里拼字符串）',
    content VARCHAR(1000) DEFAULT NULL COMMENT '通知正文（由模板渲染）',
    biz_type VARCHAR(64) DEFAULT NULL COMMENT '业务类型（可为空）',
    biz_id BIGINT DEFAULT NULL COMMENT '业务单据 ID（可为空）',
    action_url VARCHAR(500) DEFAULT NULL COMMENT '回源链接（可为空）',
    read_time DATETIME DEFAULT NULL COMMENT '读取时间（为空表示未读）',
    idempotency_key VARCHAR(160) NOT NULL COMMENT '幂等键（同一来源同一接收人只入一条）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_notification_idem (idempotency_key),
    KEY idx_msg_notification_receiver (receiver_id, read_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一通知表（全站唯一通知出口的存储侧）';

CREATE TABLE IF NOT EXISTS msg_announcement (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '公告 ID',
    title VARCHAR(200) NOT NULL COMMENT '公告标题',
    content VARCHAR(4000) NOT NULL COMMENT '公告正文',
    scope_type VARCHAR(16) NOT NULL DEFAULT 'all' COMMENT '可见范围：all 全员，dept 指定部门',
    dept_id BIGINT DEFAULT NULL COMMENT '指定部门 ID（dept 范围必填，与平台数据权限口径一致）',
    publisher_id BIGINT DEFAULT NULL COMMENT '发布人用户 ID',
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间（草稿为空）',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '公告状态：draft 草稿，published 已发布，archived 已归档',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_msg_announcement_scope (scope_type, dept_id),
    KEY idx_msg_announcement_status (status, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表（全员或指定部门可见）';

CREATE TABLE IF NOT EXISTS msg_announcement_read (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    announcement_id BIGINT NOT NULL COMMENT '公告 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    read_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '读取时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_announcement_read (announcement_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告已读表';

CREATE TABLE IF NOT EXISTS msg_channel_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投递日志 ID',
    channel VARCHAR(16) NOT NULL COMMENT '投递通道：inapp 站内，todo 待办收件箱，email 邮件，sms 短信',
    target_user_id BIGINT DEFAULT NULL COMMENT '目标用户 ID',
    biz_type VARCHAR(64) DEFAULT NULL COMMENT '业务类型（可为空）',
    biz_id BIGINT DEFAULT NULL COMMENT '业务单据 ID（可为空）',
    idempotency_key VARCHAR(160) DEFAULT NULL COMMENT '幂等键（可为空；用于排查重复投递）',
    status VARCHAR(16) NOT NULL COMMENT '投递结果：ok 成功，failed 失败，skipped 跳过',
    reason VARCHAR(500) DEFAULT NULL COMMENT '失败或跳过的原因',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_msg_channel_log_target (target_user_id, create_time),
    KEY idx_msg_channel_log_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知投递日志表（投递失败只留痕，不回滚业务）';

CREATE TABLE IF NOT EXISTS collab_comment (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论 ID',
    biz_type VARCHAR(64) NOT NULL COMMENT '业务类型（多态挂载：任务/文档/会议等由调用方给出）',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（多态挂载，本表只存引用）',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父评论 ID，0 表示顶层评论',
    content VARCHAR(2000) NOT NULL COMMENT '评论正文',
    author_id BIGINT NOT NULL COMMENT '评论人用户 ID',
    mentioned_user_ids VARCHAR(500) DEFAULT NULL COMMENT '提及的用户 ID 列表（逗号分隔）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_collab_comment_biz (biz_type, biz_id, id),
    KEY idx_collab_comment_parent (parent_id),
    KEY idx_collab_comment_author (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全站统一评论表（按业务类型与业务单据多态挂载）';

CREATE TABLE IF NOT EXISTS collab_feed (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '动态 ID',
    event_id VARCHAR(64) NOT NULL COMMENT '领域事件幂等键（同一事件只投影一条动态）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    actor_id BIGINT DEFAULT NULL COMMENT '动作人用户 ID',
    actor_name VARCHAR(64) DEFAULT NULL COMMENT '动作人展示名（读取时由平台用户资料解析）',
    action VARCHAR(32) NOT NULL COMMENT '动作：task_completed 完成，task_status_changed 状态变更，task_deleted 删除，task_kr_changed 目标关联变更',
    biz_type VARCHAR(32) NOT NULL DEFAULT 'task' COMMENT '业务类型',
    biz_id BIGINT DEFAULT NULL COMMENT '业务单据 ID',
    summary VARCHAR(500) NOT NULL COMMENT '动态摘要（由事件字段生成，不读取业务表）',
    occurred_at DATETIME DEFAULT NULL COMMENT '事件发生时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_collab_feed_event (event_id),
    KEY idx_collab_feed_time (occurred_at),
    KEY idx_collab_feed_actor (actor_id),
    KEY idx_collab_feed_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态流投影表（仅由领域事件生成，业务模块不得直写）';
