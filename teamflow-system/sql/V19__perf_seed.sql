-- M05 绩效种子（V19）：标准季度指标模板（D-06 口径，权重 100%）
--
-- 背景（M1 验收遗留 L-3）：perf_template 无种子，新环境首次使用需手工建模板 + 绑定周期才能归集，
--   否则归集为空。本脚本提供 1 个**启用态**标准模板（4 项指标，权重合计 100），
--   **不塞示例绩效周期**——周期应由业务按季度创建（避免造出假周期污染归集演示）。
--
-- 口径依据 L0 §9.1 D-2026-10-25-06（D-06.1～D-06.5）：
--   · KR 达成率      40%  —— 可归集，metric_code=kr_achievement（M02 只读取数）
--   · 任务完成率    30%  —— 可归集，metric_code=task_completion（M03 只读取数）
--   · 时限达标率    10%  —— 可归集，metric_code=time_compliance（D-06.1 逾期另立指标）
--   · 协作态度      20%  —— 主观，人工评分且**必须填评语**（require_comment=1）
--   三项可归集指标均已在 PerfMetricDictionary 登记四要素（R2 硬门槛）。
--
-- 幂等：template_code 唯一键 + (template_id, item_code) 唯一键 + INSERT IGNORE；
--   模板已存在时只补缺失项，重复执行不新增。
-- 说明：本脚本为 V19 追加脚本（只追加 V19+，禁改 V1–V18）。

INSERT IGNORE INTO perf_template (template_code, template_name, description, status, total_weight, remark, create_by, update_by)
VALUES ('std_quarter_v1',
        '标准季度绩效模板（D-06 口径）',
        '季度绩效归集标准模板：KR 达成率 40%、任务完成率 30%、时限达标率 10%、协作态度 20%（主观）。三项可归集指标口径见 L0 §9.1 D-2026-10-25-06。',
        'enabled',
        100.00,
        'M1 遗留 L-3 种子（V19）：按业务季度创建 perf_cycle 后绑定本模板即可归集',
        'system',
        'system');

INSERT IGNORE INTO perf_template_item
    (template_id, item_code, item_name, item_category, metric_code, weight, max_score, score_standard, require_comment, sort, remark, create_by, update_by)
SELECT t.id, 'kr_achievement', 'KR 达成率', 'collectable', 'kr_achievement', 40.00, 100.00,
       '取该员工在绩效周期内承接 KR 的进度算术平均（0-100）；口径见指标字典 kr_achievement（L0 D-2026-10-25-06）。',
       0, 1, NULL, 'system', 'system'
FROM perf_template t
WHERE t.template_code = 'std_quarter_v1';

INSERT IGNORE INTO perf_template_item
    (template_id, item_code, item_name, item_category, metric_code, weight, max_score, score_standard, require_comment, sort, remark, create_by, update_by)
SELECT t.id, 'task_completion', '任务完成率', 'collectable', 'task_completion', 30.00, 100.00,
       '已完成顶层任务数 / 顶层任务总数 × 100（按归集时点快照；子任务不计入分子分母）。',
       0, 2, NULL, 'system', 'system'
FROM perf_template t
WHERE t.template_code = 'std_quarter_v1';

INSERT IGNORE INTO perf_template_item
    (template_id, item_code, item_name, item_category, metric_code, weight, max_score, score_standard, require_comment, sort, remark, create_by, update_by)
SELECT t.id, 'time_compliance', '时限达标率', 'collectable', 'time_compliance', 10.00, 100.00,
       '按时完成顶层任务数 / 已完成顶层任务数 × 100；按时 = done_time 日期 ≤ due_date（D-06.1：逾期另立本指标，不扣 KR 进度）。',
       0, 3, NULL, 'system', 'system'
FROM perf_template t
WHERE t.template_code = 'std_quarter_v1';

INSERT IGNORE INTO perf_template_item
    (template_id, item_code, item_name, item_category, metric_code, weight, max_score, score_standard, require_comment, sort, remark, create_by, update_by)
SELECT t.id, 'collaboration', '协作态度', 'subjective', NULL, 20.00, 100.00,
       '由上级按季度协作表现评分（0-100），必须填写评语说明依据；主观项不参与系统自动归集。',
       1, 4, 'D-06 口径中的主观项（20%）', 'system', 'system'
FROM perf_template t
WHERE t.template_code = 'std_quarter_v1';
