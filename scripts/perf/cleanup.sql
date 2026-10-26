-- M5-A 清理性能压测数据（按 PERF-/perf_ 标记删除，可重复执行）
-- 用法：mysql -uroot -p teamflow_perf < scripts/perf/cleanup.sql
-- 注意：建任务压测按 PERF-<runId>- 前缀写入，这里统一清理 task_task 中所有 PERF-% 行。

DELETE FROM perf_result WHERE cycle_id IN (SELECT id FROM perf_cycle WHERE cycle_code = 'PERF-CYCLE');
DELETE FROM perf_cycle WHERE cycle_code = 'PERF-CYCLE';
UPDATE perf_template SET deleted = 1 WHERE template_code = 'PERF-TPL';

DELETE FROM knowledge_acl WHERE resource_type = 'page'
  AND resource_id IN (SELECT id FROM knowledge_page WHERE page_title LIKE 'PERF-%');
DELETE FROM knowledge_page_version WHERE page_id IN (SELECT id FROM knowledge_page WHERE page_title LIKE 'PERF-%');
DELETE FROM knowledge_file WHERE page_id IN (SELECT id FROM knowledge_page WHERE page_title LIKE 'PERF-%');
DELETE FROM knowledge_page WHERE page_title LIKE 'PERF-%';

DELETE FROM okr_key_result WHERE objective_id IN (SELECT id FROM okr_objective WHERE objective_title LIKE 'PERF-%');
DELETE FROM okr_align WHERE child_objective_id IN (SELECT id FROM okr_objective WHERE objective_title LIKE 'PERF-%')
   OR parent_objective_id IN (SELECT id FROM okr_objective WHERE objective_title LIKE 'PERF-%');
DELETE FROM okr_objective WHERE objective_title LIKE 'PERF-%';
DELETE FROM okr_period WHERE period_name = 'PERF-2026-Q4';

DELETE FROM task_task WHERE task_title LIKE 'PERF-%';

DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE 'perf_user_%');
DELETE FROM sys_user WHERE username LIKE 'perf_user_%';

SELECT 'cleanup done' AS status;
