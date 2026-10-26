-- M06 日程与会议（M3-C）表结构（V32；只追加不改 V1–V31）
-- 依据：L0 §9.1 D-2026-10-26-11（结论三 归档只存引用 / 结论四 通知只投递）、M06 RULE §四。
-- 结构：会议室 → 预定（冲突检测）→ 会议（参会人/议程/改期/取消）→ 纪要引用；
--       另有个人/团队日程事件与会议模板。
--
-- 跨模块契约：
--   · 纪要正文只存知识模块的页面，本模块只存 minutes_page_id（不设任何正文字段）；
--   · 纪要待办只经 M10 统一投递入口，禁止直写待办表；
--   · 邀请/改期/取消通知只经 M09 统一通知入口，不自建消息表；
--   · 会议评论走 M09 统一评论（biz_type=meeting），本模块不建评论表；
--   · 时间统一 DATETIME（服务器时区 +08:00），接口按 ISO-8601 传输（M06 RULE R2）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS；注释不含其他模块既有表名，避免代码生成器误命中。

-- ============ 1. 会议室 ============
CREATE TABLE IF NOT EXISTS meeting_room (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会议室 ID',
    room_name VARCHAR(100) NOT NULL COMMENT '会议室名称',
    room_code VARCHAR(64) NOT NULL COMMENT '会议室编码（唯一）',
    location VARCHAR(200) DEFAULT NULL COMMENT '所在位置',
    capacity INT NOT NULL DEFAULT 0 COMMENT '容纳人数',
    equipment VARCHAR(500) DEFAULT NULL COMMENT '设备清单（投影、视频会议等，逗号分隔）',
    status VARCHAR(16) NOT NULL DEFAULT 'available' COMMENT '会议室状态：available 可用，disabled 停用',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meeting_room_code (room_code),
    KEY idx_meeting_room_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室资源表（预定与冲突检测的对象）';

-- ============ 2. 会议室预定（冲突检测在服务层同一事务内完成） ============
CREATE TABLE IF NOT EXISTS meeting_booking (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '预定 ID',
    room_id BIGINT NOT NULL COMMENT '会议室 ID',
    meeting_id BIGINT DEFAULT NULL COMMENT '关联会议 ID（会议创建时自动预定，可空表示独立预定）',
    booking_title VARCHAR(200) NOT NULL COMMENT '预定用途/标题',
    booker_id BIGINT NOT NULL COMMENT '预定人用户 ID',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    status VARCHAR(16) NOT NULL DEFAULT 'booked' COMMENT '预定状态：booked 已预定，cancelled 已取消',
    cancel_reason VARCHAR(500) DEFAULT NULL COMMENT '取消原因',
    cancel_by VARCHAR(64) DEFAULT NULL COMMENT '取消人账号',
    cancel_time DATETIME DEFAULT NULL COMMENT '取消时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_meeting_booking_room_time (room_id, start_time, end_time),
    KEY idx_meeting_booking_meeting (meeting_id),
    KEY idx_meeting_booking_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室预定表（按占用区间做冲突检测，取消后释放）';

-- ============ 3. 会议 ============
-- 注意：本表**不设纪要正文字段**；纪要正文只在知识模块的页面中，本表只存 pageId 引用。
CREATE TABLE IF NOT EXISTS meeting_meeting (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会议 ID',
    meeting_title VARCHAR(200) NOT NULL COMMENT '会议标题',
    template_id BIGINT DEFAULT NULL COMMENT '会议模板 ID（可空）',
    room_id BIGINT DEFAULT NULL COMMENT '会议室 ID（可空：线上会议）',
    booking_id BIGINT DEFAULT NULL COMMENT '会议室预定 ID（会议占用会议室时生成）',
    organizer_id BIGINT NOT NULL COMMENT '主持人用户 ID',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    location VARCHAR(200) DEFAULT NULL COMMENT '会议地点（线下补充说明）',
    meeting_link VARCHAR(500) DEFAULT NULL COMMENT '线上会议链接',
    agenda TEXT DEFAULT NULL COMMENT '会议议程（纯文本/Markdown）',
    status VARCHAR(16) NOT NULL DEFAULT 'scheduled'
        COMMENT '会议状态：draft 草稿，scheduled 已排期，ongoing 进行中，ended 已结束，cancelled 已取消',
    minutes_page_id BIGINT DEFAULT NULL COMMENT '纪要页面 ID（纪要正文只在知识模块，本表仅存引用）',
    minutes_archived_time DATETIME DEFAULT NULL COMMENT '纪要归档时间',
    cancel_reason VARCHAR(500) DEFAULT NULL COMMENT '取消原因',
    cancel_by VARCHAR(64) DEFAULT NULL COMMENT '取消人账号',
    cancel_time DATETIME DEFAULT NULL COMMENT '取消时间',
    reschedule_count INT NOT NULL DEFAULT 0 COMMENT '改期次数（留痕）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_meeting_meeting_time (start_time, end_time),
    KEY idx_meeting_meeting_organizer (organizer_id),
    KEY idx_meeting_meeting_status (status),
    KEY idx_meeting_meeting_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议表（议程、参会人、改期与取消留痕；纪要只存页面引用）';

-- ============ 4. 会议参会人 ============
CREATE TABLE IF NOT EXISTS meeting_participant (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '参会记录 ID',
    meeting_id BIGINT NOT NULL COMMENT '会议 ID',
    user_id BIGINT NOT NULL COMMENT '参会人用户 ID',
    participant_role VARCHAR(16) NOT NULL DEFAULT 'required' COMMENT '参会角色：host 主持人，required 必选，optional 可选',
    response_status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '答复状态：pending 待答复，accepted 接受，declined 拒绝',
    invited_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '邀请时间',
    respond_time DATETIME DEFAULT NULL COMMENT '答复时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_meeting_participant_meeting (meeting_id),
    KEY idx_meeting_participant_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议参会人表（角色与答复状态）';

-- ============ 5. 日程事件（个人/团队日程；会议创建时同步生成会议日程） ============
CREATE TABLE IF NOT EXISTS meeting_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '日程事件 ID',
    event_title VARCHAR(200) NOT NULL COMMENT '日程标题',
    event_type VARCHAR(16) NOT NULL DEFAULT 'personal' COMMENT '日程类型：personal 个人，team 团队，meeting 会议日程',
    owner_id BIGINT NOT NULL COMMENT '日程归属用户 ID',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（团队日程用，可空）',
    meeting_id BIGINT DEFAULT NULL COMMENT '关联会议 ID（会议日程同步生成时填写）',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    all_day TINYINT NOT NULL DEFAULT 0 COMMENT '是否全天：1 是，0 否',
    location VARCHAR(200) DEFAULT NULL COMMENT '地点',
    description VARCHAR(1000) DEFAULT NULL COMMENT '日程说明',
    remind_minutes INT NOT NULL DEFAULT 15 COMMENT '提前提醒分钟数（0 表示不提醒）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '日程状态：active 正常，cancelled 已取消',
    source_type VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT '来源：manual 手工创建，meeting 由会议同步生成',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_meeting_event_owner_time (owner_id, start_time, end_time),
    KEY idx_meeting_event_meeting (meeting_id),
    KEY idx_meeting_event_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日程事件表（个人与团队日程，会议同步生成）';

-- ============ 6. 会议模板 ============
CREATE TABLE IF NOT EXISTS meeting_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会议模板 ID',
    template_code VARCHAR(64) NOT NULL COMMENT '模板编码（唯一，如 normal / okr_calibration / retro）',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    template_type VARCHAR(32) NOT NULL DEFAULT 'normal'
        COMMENT '模板类型：normal 常规会议，okr_calibration 目标校准会，retro 复盘会',
    description VARCHAR(500) DEFAULT NULL COMMENT '模板说明',
    agenda_template TEXT DEFAULT NULL COMMENT '议程模板（纯文本/Markdown）',
    default_duration_minutes INT NOT NULL DEFAULT 60 COMMENT '默认会议时长（分钟）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '模板状态：active 启用，disabled 停用',
    builtin TINYINT NOT NULL DEFAULT 0 COMMENT '是否内置模板：1 是，0 否',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meeting_template_code (template_code),
    KEY idx_meeting_template_type (template_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议模板表（常规会议、目标校准会、复盘会等专项模板）';
