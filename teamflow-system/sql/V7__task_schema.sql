-- M03 任务与工作流模块表结构（追加脚本，不修改 V1–V6；依据 M03 RULE §四 数据归属）
-- 依据 L0 §9.1：
--   D-2026-10-25-01：自研轻量状态机，状态枚举与允许迁移表只允许一份且外置为数据（见 Java 侧 TaskStatusMachine），
--                    本脚本只建表，不把迁移规则散落进 SQL；
--   D-2026-10-25-02：评论归 M09（collab_comment），本模块不建 task_comment 等任何 *_comment 表；
--   D-2026-10-25-05：任务↔KR 权威关联在本模块 task_task.kr_id；M03 只发事件，不写 okr_* 表。
-- 表前缀：task_（任务域）、flow_（流程域），属 L0 §2.1 表前缀例外清单已登记项。
-- 审计四字段 + 逻辑删除：所有业务表均带 create_by/update_by/create_time/update_time + deleted。

CREATE TABLE IF NOT EXISTS task_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
    task_title VARCHAR(200) NOT NULL COMMENT '任务标题',
    task_desc VARCHAR(2000) DEFAULT NULL COMMENT '任务描述',
    task_type VARCHAR(16) NOT NULL DEFAULT 'general' COMMENT '任务类型：general 普通，subtask 子任务',
    kr_id BIGINT DEFAULT NULL COMMENT '关联关键结果 ID（权威关联在本模块，逻辑关联 okr_key_result.id；不读 okr_* 表）',
    priority VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '优先级：low 低，normal 普通，high 高，urgent 紧急',
    status VARCHAR(32) NOT NULL DEFAULT 'todo' COMMENT '任务状态：pool 待认领，todo 待办，in_progress 进行中，done 已完成，closed 已关闭',
    assignee_id BIGINT DEFAULT NULL COMMENT '当前责任人用户 ID（NULL 表示未指派，只允许存在于待认领池；只读引用 sys_user）',
    creator_id BIGINT DEFAULT NULL COMMENT '创建人用户 ID（只读引用 sys_user）',
    dept_id BIGINT DEFAULT NULL COMMENT '归属部门 ID（数据范围过滤用，只读引用 sys_dept）',
    start_date DATE DEFAULT NULL COMMENT '计划开始日期',
    due_date DATE DEFAULT NULL COMMENT '截止日期',
    done_time DATETIME DEFAULT NULL COMMENT '完成时间（非完成态为 NULL）',
    sub_task_count INT NOT NULL DEFAULT 0 COMMENT '直接子任务总数（由子任务创建/删除维护）',
    done_sub_task_count INT NOT NULL DEFAULT 0 COMMENT '已完成直接子任务数（由子任务状态流转维护）',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_task_task_kr (kr_id),
    KEY idx_task_task_assignee (assignee_id),
    KEY idx_task_task_status (status),
    KEY idx_task_task_dept (dept_id),
    KEY idx_task_task_creator (creator_id),
    KEY idx_task_task_due (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表（M03 权威数据；kr_id 为任务↔KR 权威关联）';

CREATE TABLE IF NOT EXISTS task_assignee (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id BIGINT NOT NULL COMMENT '任务 ID（逻辑关联 task_task.id）',
    user_id BIGINT NOT NULL COMMENT '用户 ID（只读引用 sys_user）',
    assign_type VARCHAR(16) NOT NULL DEFAULT 'owner' COMMENT '指派类型：owner 主责人，participant 参与人',
    assigned_by BIGINT DEFAULT NULL COMMENT '指派操作人用户 ID',
    assign_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '指派时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_assignee_pair (task_id, user_id),
    KEY idx_task_assignee_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务指派关系表（主责人/参与人，含指派历史）';

CREATE TABLE IF NOT EXISTS task_relation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id BIGINT NOT NULL COMMENT '主体任务 ID（逻辑关联 task_task.id）',
    related_task_id BIGINT NOT NULL COMMENT '关联任务 ID（逻辑关联 task_task.id）',
    relation_type VARCHAR(16) NOT NULL DEFAULT 'subtask' COMMENT '关系类型：subtask 表示 task_id 是 related_task_id 的子任务；depends_on 表示 task_id 依赖 related_task_id',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_relation_triple (task_id, related_task_id, relation_type),
    KEY idx_task_relation_related (related_task_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务关系表（父子/依赖；父子的单一来源，顶层判定据此）';

CREATE TABLE IF NOT EXISTS flow_definition (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流程定义 ID',
    def_code VARCHAR(64) NOT NULL COMMENT '流程定义编码（全局唯一，程序按编码引用）',
    def_name VARCHAR(100) NOT NULL COMMENT '流程定义名称',
    business_type VARCHAR(32) NOT NULL COMMENT '业务类型：task_status 任务状态流转，task_approve 任务单节点审批',
    nodes_json TEXT DEFAULT NULL COMMENT '线性节点定义（JSON 数组，自研引擎的节点清单；复杂编排不在本期范围）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 停用',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_definition_code (def_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义表（自研轻量引擎，线性节点；D-2026-10-25-01）';

CREATE TABLE IF NOT EXISTS flow_instance (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流程实例 ID',
    definition_id BIGINT NOT NULL COMMENT '流程定义 ID（逻辑关联 flow_definition.id）',
    def_code VARCHAR(64) NOT NULL COMMENT '流程定义编码（冗余，便于按编码检索）',
    instance_type VARCHAR(16) NOT NULL DEFAULT 'status' COMMENT '实例类型：status 任务状态流转实例，approval 单节点审批实例',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（本模块为任务 ID，逻辑关联 task_task.id）',
    biz_title VARCHAR(200) DEFAULT NULL COMMENT '业务单据标题（冗余便于列表展示）',
    target_status VARCHAR(32) DEFAULT NULL COMMENT '审批实例的目标状态（审批通过后要流转到的任务状态）',
    status VARCHAR(16) NOT NULL DEFAULT 'running' COMMENT '实例状态：running 进行中，completed 已完成，terminated 已终止',
    current_node VARCHAR(64) DEFAULT NULL COMMENT '当前节点编码',
    start_by BIGINT DEFAULT NULL COMMENT '发起人用户 ID',
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_flow_instance_biz (biz_id, instance_type),
    KEY idx_flow_instance_status (status),
    KEY idx_flow_instance_def (def_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程实例表（任务状态实例 + 单节点审批实例）';

CREATE TABLE IF NOT EXISTS flow_node_instance (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '节点实例 ID',
    instance_id BIGINT NOT NULL COMMENT '流程实例 ID（逻辑关联 flow_instance.id）',
    node_key VARCHAR(64) NOT NULL COMMENT '节点编码（状态码 / create / assign 等留痕节点）',
    node_name VARCHAR(100) DEFAULT NULL COMMENT '节点名称',
    from_status VARCHAR(32) DEFAULT NULL COMMENT '变更前任务状态（创建节点为空）',
    to_status VARCHAR(32) DEFAULT NULL COMMENT '变更后任务状态',
    node_status VARCHAR(16) NOT NULL DEFAULT 'completed' COMMENT '节点状态：completed 已通过，rejected 已驳回，pending 待处理',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人账号',
    opinion VARCHAR(500) DEFAULT NULL COMMENT '审批/流转意见',
    operated_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_flow_node_instance_instance (instance_id),
    KEY idx_flow_node_instance_time (operated_time),
    KEY idx_flow_node_instance_operator (operator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程节点实例表（R2：每次流转留痕：操作人/前后状态/时间/意见）';

CREATE TABLE IF NOT EXISTS flow_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流程待办 ID',
    instance_id BIGINT NOT NULL COMMENT '流程实例 ID（逻辑关联 flow_instance.id）',
    biz_id BIGINT NOT NULL COMMENT '业务单据 ID（本模块为任务 ID）',
    node_key VARCHAR(64) NOT NULL DEFAULT 'approve' COMMENT '节点编码',
    task_title VARCHAR(200) DEFAULT NULL COMMENT '待办标题',
    assignee_id BIGINT DEFAULT NULL COMMENT '审批人用户 ID（只要一人，不做会签/加签）',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '待办状态：pending 待审批，approved 已通过，rejected 已驳回',
    opinion VARCHAR(500) DEFAULT NULL COMMENT '审批意见',
    operate_time DATETIME DEFAULT NULL COMMENT '处理时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_flow_task_instance (instance_id),
    KEY idx_flow_task_assignee (assignee_id, status),
    KEY idx_flow_task_biz (biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程待办表（本期仅线性单节点审批；会签/并行/加签属 P2 的 3.1 审批中心）';

-- 默认流程定义（幂等：固定主键 + INSERT IGNORE）。
-- 说明：任务状态机的「状态枚举 + 允许迁移表」唯一来源在 Java 侧 com.teamflow.task.status.TaskStatusMachine
--       （D-2026-10-25-01 要求单一来源且外置为数据）；此处 flow_definition 只登记引擎用的节点清单，
--       不重复定义允许迁移关系，避免出现第二份真相。
INSERT IGNORE INTO flow_definition (id, def_code, def_name, business_type, nodes_json, status, remark, create_by, update_by)
VALUES
    (1, 'task_status_linear', '任务状态线性流转', 'task_status',
     '[{"key":"create","name":"创建"},{"key":"pool","name":"待认领"},{"key":"todo","name":"待办"},{"key":"in_progress","name":"进行中"},{"key":"done","name":"已完成"},{"key":"closed","name":"已关闭"},{"key":"assign","name":"指派"}]',
     1, '自研轻量状态机（D-2026-10-25-01），只做线性流转，复杂审批不在本期', 'system', 'system'),
    (2, 'task_approve_single', '任务单节点审批', 'task_approve',
     '[{"key":"approve","name":"审批"}]',
     1, '线性单节点审批骨架；会签/并行/加签属 P2 的 3.1 审批中心', 'system', 'system');
