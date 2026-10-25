-- G-1 统一文件上传（M00 平台能力）：sys_file 扩展 4 列 + 必要索引
-- 依据：L0 §5.3.1 G-1、docs/M1前置能力缺口工单.md G-1、M00 RULE §九之二
-- 说明：
--   · 追加脚本，不改 V1–V10（G-4 规则：只追加 V11+，禁改已发布脚本）。
--   · MySQL 8.0 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema
--     判断 + 动态 SQL 实现幂等：列/索引已存在时执行 SELECT 1 空操作，重复执行不报错。
--   · 既有 `sys_file` 行由列默认值自动回填：ref_count=0、storage_provider='local'，旧数据不丢。
--   · storage_provider 预留：将来切对象存储时新增实现，不改业务表结构。

-- ============ 1/4 biz_type ============
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_file ADD COLUMN biz_type VARCHAR(32) DEFAULT NULL COMMENT ''业务类型（如 task / kb_page），便于业务模块按对象检索附件''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND column_name = 'biz_type');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============ 2/4 biz_id ============
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_file ADD COLUMN biz_id BIGINT DEFAULT NULL COMMENT ''业务对象 ID（与 biz_type 组合定位附件归属）''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND column_name = 'biz_id');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============ 3/4 ref_count ============
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_file ADD COLUMN ref_count INT NOT NULL DEFAULT 0 COMMENT ''引用计数：业务模块 incrementRef/decrementRef 维护，>0 时禁止删除''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND column_name = 'ref_count');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============ 4/4 storage_provider ============
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_file ADD COLUMN storage_provider VARCHAR(16) NOT NULL DEFAULT ''local'' COMMENT ''存储提供方：local 本地磁盘；预留 oss/s3 等''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND column_name = 'storage_provider');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============ 索引：按业务对象检索附件（biz_type, biz_id） ============
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_file ADD INDEX idx_sys_file_biz (biz_type, biz_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND index_name = 'idx_sys_file_biz');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
