-- M07 知识与文档（M3-B）表结构（V30；只追加不改 V1–V29）
-- 依据：L0 §9.1 D-2026-10-26-11（结论三 归档只存引用 / 结论六 检索能力边界）、M07 RULE §四。
-- 结构：空间 → 目录 → 页面 三层 + 页面版本快照 + 附件引用 + 访问控制链。
--
-- 跨模块契约：
--   · 会议纪要、项目复盘等业务正文**只**存放在 knowledge_page；业务模块只存 page_id；
--   · 附件本体由平台统一上传能力维护，本模块只存文件 ID 并按引用计数增减，不复制文件；
--   · 页面评论不在本模块建表（统一走 M09 的评论能力，按业务类型 + 业务 ID 挂载）。
--
-- 全文检索（D-11 结论六）：InnoDB FULLTEXT + ngram 分词（服务器 ngram_token_size=2），
--   中文双字及以上可走索引；单字或索引不可用时由服务层回落 LIKE。
--   索引为 (page_title, content, tags) 联合全文索引；不引入外部搜索引擎。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS；注释不含其他模块既有表名，避免代码生成器误命中。

-- ============ 1. 知识空间（三层的第一层） ============
CREATE TABLE IF NOT EXISTS knowledge_space (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '空间 ID',
    space_name VARCHAR(100) NOT NULL COMMENT '空间名称',
    space_code VARCHAR(64) NOT NULL COMMENT '空间编码（唯一，供跨模块归档定位，如 archive）',
    space_desc VARCHAR(500) DEFAULT NULL COMMENT '空间说明',
    visibility VARCHAR(16) NOT NULL DEFAULT 'internal'
        COMMENT '默认可见性：internal 已登录可读，restricted 仅授权可读',
    owner_id BIGINT NOT NULL COMMENT '空间所有者用户 ID（只读引用平台用户）',
    status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '空间状态：active 正常，archived 已归档',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_space_code (space_code),
    KEY idx_knowledge_space_owner (owner_id),
    KEY idx_knowledge_space_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识空间表（知识与文档三层结构的第一层）';

-- ============ 2. 目录（三层的第二层，空间内树形） ============
CREATE TABLE IF NOT EXISTS knowledge_node (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '目录 ID',
    space_id BIGINT NOT NULL COMMENT '所属空间 ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级目录 ID，0 表示空间根',
    node_name VARCHAR(100) NOT NULL COMMENT '目录名称',
    node_desc VARCHAR(500) DEFAULT NULL COMMENT '目录说明',
    visibility VARCHAR(16) NOT NULL DEFAULT 'inherit'
        COMMENT '可见性覆盖：inherit 继承上级，internal 已登录可读，restricted 仅授权可读',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_knowledge_node_space (space_id, parent_id),
    KEY idx_knowledge_node_visibility (visibility)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识目录表（空间内的树形目录）';

-- ============ 3. 页面（三层的第三层；正文唯一存放处） ============
CREATE TABLE IF NOT EXISTS knowledge_page (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '页面 ID',
    space_id BIGINT NOT NULL COMMENT '所属空间 ID',
    node_id BIGINT NOT NULL DEFAULT 0 COMMENT '所属目录 ID，0 表示挂在空间根',
    page_title VARCHAR(200) NOT NULL COMMENT '页面标题',
    page_type VARCHAR(16) NOT NULL DEFAULT 'wiki'
        COMMENT '页面类型：wiki 普通页面，sop 流程说明，decision 决策记录，minutes 会议纪要，retro 项目复盘',
    tags VARCHAR(500) DEFAULT NULL COMMENT '标签（逗号分隔；SOP 与决策记录用页面 + 标签表达，不另建表）',
    summary VARCHAR(500) DEFAULT NULL COMMENT '摘要（归档场景可存结项结论等短摘要）',
    content MEDIUMTEXT NOT NULL COMMENT '正文（Markdown；会议纪要与项目复盘的唯一正文存放处）',
    content_format VARCHAR(16) NOT NULL DEFAULT 'markdown' COMMENT '正文格式：统一 markdown，渲染在前端完成',
    visibility VARCHAR(16) NOT NULL DEFAULT 'inherit'
        COMMENT '可见性覆盖：inherit 继承上级，internal 已登录可读，restricted 仅授权可读',
    status VARCHAR(16) NOT NULL DEFAULT 'published' COMMENT '页面状态：draft 草稿，published 已发布，archived 已归档',
    current_version INT NOT NULL DEFAULT 1 COMMENT '当前版本号（每次保存 +1）',
    owner_id BIGINT NOT NULL COMMENT '页面所有者用户 ID（只读引用平台用户）',
    source_module VARCHAR(32) DEFAULT NULL COMMENT '归档来源模块（如 M06、M14；手工页面为空）',
    source_biz_type VARCHAR(32) DEFAULT NULL COMMENT '归档来源业务类型（如 meeting_minutes、project_retro）',
    source_biz_id BIGINT DEFAULT NULL COMMENT '归档来源业务单据 ID（业务模块只存本页面的 ID，正文不复制）',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_page_source (source_module, source_biz_type, source_biz_id),
    KEY idx_knowledge_page_space (space_id, node_id),
    KEY idx_knowledge_page_type (page_type, status),
    KEY idx_knowledge_page_owner (owner_id),
    KEY idx_knowledge_page_update (update_time),
    FULLTEXT KEY ft_knowledge_page_text (page_title, content, tags) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识页面表（正文唯一存放处，支持全文检索与版本化）';

-- ============ 4. 页面版本快照（append-only，保存即版本化） ============
CREATE TABLE IF NOT EXISTS knowledge_page_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '版本 ID',
    page_id BIGINT NOT NULL COMMENT '页面 ID',
    version_no INT NOT NULL COMMENT '版本号（从 1 递增）',
    page_title VARCHAR(200) NOT NULL COMMENT '该版本的标题快照',
    content MEDIUMTEXT NOT NULL COMMENT '该版本的正文快照',
    content_format VARCHAR(16) NOT NULL DEFAULT 'markdown' COMMENT '正文格式',
    tags VARCHAR(500) DEFAULT NULL COMMENT '该版本的标签快照',
    change_summary VARCHAR(500) DEFAULT NULL COMMENT '变更摘要',
    editor_id BIGINT DEFAULT NULL COMMENT '编辑人用户 ID',
    editor_name VARCHAR(64) DEFAULT NULL COMMENT '编辑人账号',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_page_version (page_id, version_no),
    KEY idx_knowledge_version_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识页面版本表（保存即留快照，可回滚）';

-- ============ 5. 文档中心附件引用（只存文件 ID，不复制文件本体） ============
CREATE TABLE IF NOT EXISTS knowledge_file (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '附件引用 ID',
    space_id BIGINT NOT NULL COMMENT '所属空间 ID',
    page_id BIGINT DEFAULT NULL COMMENT '关联页面 ID（可空，表示仅挂在空间）',
    file_id BIGINT NOT NULL COMMENT '平台统一上传返回的文件 ID（本模块不复制文件本体）',
    file_title VARCHAR(200) NOT NULL COMMENT '附件展示标题（业务标签，可编辑）',
    category VARCHAR(32) NOT NULL DEFAULT 'attachment' COMMENT '附件分类：attachment 附件，sop_doc 流程文档，image 图片，other 其他',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_knowledge_file_space (space_id),
    KEY idx_knowledge_file_page (page_id),
    KEY idx_knowledge_file_ref (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识附件引用表（只保存文件 ID 与业务标签，文件本体由平台统一维护）';

-- ============ 6. 访问控制链（子级继承父级，可覆盖） ============
CREATE TABLE IF NOT EXISTS knowledge_acl (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '授权 ID',
    resource_type VARCHAR(16) NOT NULL COMMENT '资源类型：space 空间，node 目录，page 页面',
    resource_id BIGINT NOT NULL COMMENT '资源 ID',
    subject_type VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT '授权对象类型：user 用户（本期仅支持用户）',
    subject_id BIGINT NOT NULL COMMENT '授权对象 ID（用户 ID）',
    permission VARCHAR(16) NOT NULL COMMENT '权限：read 只读，write 可编辑，manage 可管理，deny 显式拒绝（覆盖上级授权）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '授权说明',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_acl_subject (resource_type, resource_id, subject_type, subject_id),
    KEY idx_knowledge_acl_resource (resource_type, resource_id),
    KEY idx_knowledge_acl_subject_id (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识访问控制表（沿空间→目录→页面逐级继承，子级授权可覆盖父级）';
