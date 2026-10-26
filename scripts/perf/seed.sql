-- M5-A 性能压测数据集（仅在独立性能库执行，例如 teamflow_perf；不要对开发库执行）
-- 设计：20 个压测用户 × 50 条任务 = 1000 条任务；20 用户 × 10 目标 = 200 目标 + 400 KR；
--       200 个知识页面；1 个绩效周期 + 20 条绩效结果。
-- 幂等：先执行 cleanup.sql 再 seed；所有数据带 PERF- 前缀或 perf_ 账号，便于清理。
-- 用法：mysql -uroot -p teamflow_perf < scripts/perf/seed.sql

SET @today := CURDATE();

-- 压测用户（密码复用 admin 的哈希，仅用于数据归属，不用于登录压测）
INSERT INTO sys_user (username, password, nickname, dept_id, status, create_by, update_by)
SELECT CONCAT('perf_user_', n), (SELECT password FROM sys_user WHERE id = 1),
       CONCAT('压测用户', n), 5, 1, 'system', 'system'
FROM (
    WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 20)
    SELECT n FROM seq
) nums;

-- 20 用户 × 50 条任务 = 1000 条（状态/日期/优先级有分布，便于统计口径压测）
INSERT INTO task_task (task_title, task_type, priority, status, assignee_id, creator_id, dept_id,
                       start_date, due_date, done_time, sort, create_by, update_by)
SELECT CONCAT('PERF-任务-', u.username, '-', n.n),
       'general',
       CASE MOD(n.n, 4) WHEN 0 THEN 'urgent' WHEN 1 THEN 'high' WHEN 2 THEN 'normal' ELSE 'low' END,
       CASE MOD(n.n, 5) WHEN 0 THEN 'done' WHEN 1 THEN 'in_progress' WHEN 2 THEN 'todo' ELSE 'done' END,
       u.id, 1, 5,
       DATE_SUB(@today, INTERVAL MOD(n.n, 40) DAY),
       DATE_ADD(DATE_SUB(@today, INTERVAL MOD(n.n, 40) DAY), INTERVAL 5 DAY),
       CASE WHEN MOD(n.n, 5) IN (0, 3, 4) THEN DATE_SUB(NOW(), INTERVAL MOD(n.n, 25) DAY) ELSE NULL END,
       n.n, 'system', 'system'
FROM (
    WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 50)
    SELECT n FROM seq
) n
CROSS JOIN (SELECT id, username FROM sys_user WHERE username LIKE 'perf_user_%') u;

-- 目标周期（唯一，避免重复）
INSERT IGNORE INTO okr_period (period_name, period_type, start_date, end_date, status, sort, create_by, update_by)
VALUES ('PERF-2026-Q4', 'quarter', DATE_SUB(@today, INTERVAL 60 DAY), DATE_ADD(@today, INTERVAL 30 DAY),
        'active', 99, 'system', 'system');
SET @period_id := (SELECT id FROM okr_period WHERE period_name = 'PERF-2026-Q4' LIMIT 1);

-- 20 用户 × 10 目标 = 200 条
INSERT INTO okr_objective (period_id, parent_id, objective_title, objective_desc, level, dept_id, owner_id,
                           weight, progress, status, sort, create_by, update_by)
SELECT @period_id, 0, CONCAT('PERF-目标-', u.username, '-', n.n), '性能压测目标', 'personal', 5, u.id,
       10, MOD(n.n * 7 + 15, 100), 'active', n.n, 'system', 'system'
FROM (
    WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 10)
    SELECT n FROM seq
) n
CROSS JOIN (SELECT id, username FROM sys_user WHERE username LIKE 'perf_user_%') u;

-- 每个目标 2 个 KR = 400 条
INSERT INTO okr_key_result (objective_id, kr_title, kr_type, metric_unit, target_value, current_value,
                            progress, progress_source, task_link_status, owner_id, sort, create_by, update_by)
SELECT o.id, CONCAT('PERF-KR-', o.id, '-', k.n), 'quantitative', '%', 100,
       MOD(o.id * 3 + k.n * 5, 100), MOD(o.id * 7 + k.n * 3, 100),
       'quantitative', 'unlinked', o.owner_id, k.n, 'system', 'system'
FROM okr_objective o
CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2 AS n) k
WHERE o.objective_title LIKE 'PERF-%';

-- 200 个知识页面（正文含「性能测试」关键词，用于全文检索压测）
INSERT INTO knowledge_page (space_id, node_id, page_title, page_type, tags, summary, content,
                            content_format, visibility, status, current_version, owner_id, create_by, update_by)
SELECT 1, 0, CONCAT('PERF-知识-', n.n), 'wiki', '性能测试', '压测页面',
       CONCAT('性能测试页面 ', n.n, '：联调窗口提前到本周五，目标对齐检查下周复盘。',
              REPEAT('压测内容用于全文检索与引用拼接。', 20)),
       'markdown', 'internal', 'published', 1, 1, 'system', 'system'
FROM (
    WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
    SELECT n FROM seq
) n;

-- 绩效夹具：模板 + 周期 + 20 条已归档结果
INSERT IGNORE INTO perf_template (template_code, template_name, description, status, total_weight, create_by, update_by)
VALUES ('PERF-TPL', '性能压测模板', 'M5-A 压测夹具', 'enabled', 100.00, 'system', 'system');
SET @perf_template_id := (SELECT id FROM perf_template WHERE template_code = 'PERF-TPL' LIMIT 1);

INSERT IGNORE INTO perf_cycle (cycle_code, cycle_name, cycle_type, start_date, end_date, template_id,
                               status, archive_time, create_by, update_by)
VALUES ('PERF-CYCLE', '性能压测周期', 'quarter', DATE_SUB(@today, INTERVAL 90 DAY), @today,
        @perf_template_id, 'archived', NOW(), 'system', 'system');
SET @perf_cycle_id := (SELECT id FROM perf_cycle WHERE cycle_code = 'PERF-CYCLE' LIMIT 1);

INSERT INTO perf_result (cycle_id, employee_id, template_id, total_score, self_score, collect_score,
                         subjective_score, effective_weight, na_weight, status, archive_time, create_by, update_by)
SELECT @perf_cycle_id, u.id, @perf_template_id, 80 + MOD(u.id, 15), 78 + MOD(u.id, 12),
       80 + MOD(u.id, 15), 0.00, 100.00, 0.00, 'archived', NOW(), 'system', 'system'
FROM sys_user u
WHERE u.username LIKE 'perf_user_%';

-- 汇总（供压测报告引用）
SELECT 'users' AS item, COUNT(*) AS total FROM sys_user WHERE username LIKE 'perf_user_%'
UNION ALL SELECT 'tasks', COUNT(*) FROM task_task WHERE task_title LIKE 'PERF-%'
UNION ALL SELECT 'objectives', COUNT(*) FROM okr_objective WHERE objective_title LIKE 'PERF-%'
UNION ALL SELECT 'knowledge_pages', COUNT(*) FROM knowledge_page WHERE page_title LIKE 'PERF-%'
UNION ALL SELECT 'perf_results', COUNT(*) FROM perf_result WHERE cycle_id = @perf_cycle_id;
