-- M13 统计与洞察 · 指标字典（V20；只追加不改 V1–V19）
--
-- 口径依据：
--   L0 §2.4.3 需求 7.1「指标字典维护：定义、口径、数据来源模块、刷新频率；未登记口径的指标不得上线；
--                     与 M05 指标字典保持同一口径」
--   M13 RULE §六 R1（未登记不得上线）/ R2（口径与业务模块一致）/ R3（数据范围强制生效）/
--                R4（只读原则）/ R5（大数据量不实时扫全表）/ R6（趋势时区 +08:00）/ R7（空数据呈现）
--
-- 边界（M13 RULE §二/§四）：M13 是纯读模型，**不建业务明细表、不落看板明细**。
--   本表只存「指标字典与口径」：定义、公式、数据来源、时间口径、缺失值策略、刷新频率与缓存失效时长。
--   看板数据一律实时聚合（可随时重建），不物化、不复制 task_*/okr_*/perf_* 的任何明细。
--
-- 与 M05 指标字典同码同口径：kr_achievement / task_completion / time_compliance 三项的
--   formula / time_grain（对应 M05 timeRange）/ missing_policy（对应 M05 missingPolicy）与
--   M05 的 PerfMetricDictionary 逐字一致（验收测试逐项断言相等）。
--
-- 未登记口径的指标不得上线：
--   ① 本表 status 只有 online 参与看板出数；draft/offline 由看板接口拒绝并提示；
--   ② 新增/上线时还必须命中代码侧 InsightMetricRegistry 的计算实现（未实现的口径不能上线）。
--
-- 幂等：metric_code 唯一键 + INSERT IGNORE，重复执行不新增。

CREATE TABLE IF NOT EXISTS insight_metric (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指标 ID',
    metric_code VARCHAR(64) NOT NULL COMMENT '指标编码（全局唯一；与 M05 指标字典同码）',
    metric_name VARCHAR(100) NOT NULL COMMENT '指标名称',
    metric_category VARCHAR(16) NOT NULL DEFAULT 'task'
        COMMENT '指标类别：task 任务统计，team 团队效能，cross 跨域（M3 排期，本期不实现）',
    metric_definition VARCHAR(500) NOT NULL COMMENT '指标定义（业务含义，给使用者看）',
    formula VARCHAR(1000) NOT NULL COMMENT '计算公式（口径唯一来源；与业务模块同名指标必须一致）',
    source_module VARCHAR(64) NOT NULL COMMENT '数据来源模块（如 M02/M03/M04）',
    source_detail VARCHAR(500) NOT NULL COMMENT '数据来源接口/Service 方法（禁止直连业务表）',
    time_grain VARCHAR(500) NOT NULL COMMENT '时间口径（统计区间、时区与边界规则）',
    missing_policy VARCHAR(500) NOT NULL COMMENT '缺失值处理（无数据取 0 还是 null、如何呈现）',
    refresh_frequency VARCHAR(32) NOT NULL DEFAULT 'realtime'
        COMMENT '刷新频率：realtime 实时 / 5min / 1h / daily',
    cache_ttl_seconds INT NOT NULL DEFAULT 0
        COMMENT '缓存失效时长（秒）：0 表示不缓存；缓存只允许存聚合结果，可随时重建，不得成为第二数据源',
    owner_name VARCHAR(64) NOT NULL DEFAULT 'system' COMMENT '口径责任人',
    status VARCHAR(16) NOT NULL DEFAULT 'draft'
        COMMENT '状态：draft 草稿 / online 已上线 / offline 已下线；仅 online 参与看板出数',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_insight_metric_code (metric_code),
    KEY idx_insight_metric_category (metric_category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M13 指标字典（只存口径，不存看板明细；未登记口径不得上线）';

-- ============ 种子：9 个已上线指标（与代码侧 InsightMetricRegistry 一一对应） ============

-- ① KR 达成率：与 M05 同码同口径（M05 PerfMetricDictionary 逐字一致）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('kr_achievement', 'KR 达成率', 'task',
     '员工在统计周期内承接的全部关键结果（KR）的进度算术平均，衡量目标达成度。',
     'min(100, AVG(KR.progress))：取该员工在取数范围内承接的全部 KR，进度算术平均；KR 进度口径遵循 L0 D-2026-10-25-03（量化型 min(1,current/target)；任务驱动型=已完成顶层任务数/关联顶层任务总数）；D-06.1：任务逾期不扣减 KR 进度，逾期由「时限达标率」单独承接',
     'M02 目标与 OKR（经 OkrKeyResultService 只读取数，禁止直连 okr_* 表）',
     'M02 OkrKeyResultService#perfFacts(ownerId, startDate, endDate)（M02 提供的只读端口）',
     '绩效周期 [start_date, end_date] 内：目标所属 okr_period 与绩效周期日期区间重叠，且 KR.owner_id=被评人',
     'D-06.5：无 KR（有目标无任务/完全无目标）默认取 0；仅经 HR/管理者显式标记 not_applicable 的例外状态（新入职当年/长假/借调/试用期未定目标）按剩余权重归一化',
     'realtime', 0, 'system', 'online',
     'M13 以同一 [startDate,endDate] 调用 M02 同一只读端口，与 M05 归集预览逐项一致（验收测试断言）',
     'system', 'system');

-- ② 任务完成率：与 M05 同码同口径
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('task_completion', '任务完成率', 'task',
     '统计周期内顶层任务的完成比例，衡量执行落地程度（子任务不计入）。',
     '已完成顶层任务数 / 顶层任务总数 × 100；按归集时点 task.status 快照判定（D-06.1：未完成不计入分子）',
     'M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）',
     'M03 TaskPerfQueryService#topLevelTaskFacts(assigneeId, startDate, endDate)（M03 提供的只读端口）',
     '绩效周期内截止日期（due_date）落在周期内的顶层任务；due_date 为空时按 create_time 落在周期内判定；子任务（有父任务关系）不计入分子分母',
     'D-06.5：无任何顶层任务默认取 0；有任务但未启动/未完成同样取 0；仅显式标记 not_applicable 的例外状态按剩余权重归一化',
     'realtime', 0, 'system', 'online',
     'M13 以同一 [startDate,endDate] 调用 M03 同一只读端口，与 M05 归集预览逐项一致（验收测试断言）',
     'system', 'system');

-- ③ 时限达标率：与 M05 同码同口径（D-06.1 逾期另立指标）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('time_compliance', '时限达标率', 'task',
     '已完成顶层任务中按截止日期完成的比例，承接逾期质量维度（不影响 KR 进度）。',
     '按时完成顶层任务数 / 已完成顶层任务数 × 100；按时 = done_time 的日期 ≤ due_date；due_date 为空视为按时；D-06.1：逾期另立本指标，不影响 KR 进度',
     'M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）',
     'M03 TaskPerfQueryService#topLevelTaskFacts(assigneeId, startDate, endDate)（M03 提供的只读端口）',
     '与「任务完成率」同一顶层任务集合（取数范围一致），仅统计其中已完成部分',
     'D-06.5：无已完成顶层任务时取 0；仅显式标记 not_applicable 的例外状态按剩余权重归一化',
     'realtime', 0, 'system', 'online',
     'M13 以同一 [startDate,endDate] 调用 M03 同一只读端口，与 M05 归集预览逐项一致（验收测试断言）',
     'system', 'system');

-- ④ 逾期率（M13 任务看板新增口径）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('task_overdue_rate', '逾期率', 'task',
     '统计周期内顶层任务处于逾期状态的比例，用来暴露执行质量风险。',
     '逾期顶层任务数 / 顶层任务总数 × 100；逾期判定：未完成且 due_date < 统计截止日（当天），或已完成且 DATE(done_time) > due_date；due_date 为空不判逾期',
     'M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）',
     'M03 TaskPerfQueryService#topLevelTaskFacts(assigneeId, startDate, endDate)（M03 提供的只读端口）',
     '与「任务完成率」同一顶层任务集合（due_date 落在区间，due_date 为空按 create_time）；统计截止日取服务器时区 +08:00 的当天',
     '无顶层任务时取 0 并标记 no_data（看板显示「无数据」，不显示 0% 误导）',
     'realtime', 0, 'system', 'online',
     '逾期是执行质量维度：D-06.1 明确 KR 进度不因逾期扣减，故本指标独立统计',
     'system', 'system');

-- ⑤ 周期趋势（明细型指标）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('task_period_trend', '任务周期趋势', 'task',
     '按自然日展开的顶层任务趋势，用于观察到期与完成节奏。',
     '逐日统计：dueCount=当日到期顶层任务数（due_date=当日）；doneCount=当日完成顶层任务数（DATE(done_time)=当日）；lateDueCount=当日到期且未按期完成的顶层任务数；趋势数组不做累计',
     'M03 任务与工作流（经 TaskPerfQueryService 只读取数，禁止直连 task_* 表）',
     'M03 TaskPerfQueryService#topLevelTaskFacts(assigneeId, startDate, endDate)（M03 提供的只读端口）',
     '统计区间 [startDate, endDate] 按自然日展开，时区固定 +08:00；单次最多展开 366 天',
     '区间内无任务时返回空数组 trend=[]（前端显示空态），不回填 0',
     'realtime', 0, 'system', 'online',
     '明细型指标，主值 value 返回 null，数据见 trend 数组',
     'system', 'system');

-- ⑥ 成员分布（明细型指标）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('task_member_distribution', '任务成员分布', 'task',
     '按任务责任人分摊的顶层任务完成情况，用于定位成员间执行差异。',
     '每位责任人：taskTotal 顶层任务总数、taskDone 已完成数、taskOverdue 逾期数、taskCompletionRate 完成率、timeComplianceRate 时限达标率、krCount / krAchievement；数据范围受当前用户 DataScope 限制',
     'M03 + M02（任务事实与 KR 事实均经对方只读端口）',
     'M03 TaskPerfQueryService#topLevelTaskFacts + M02 OkrKeyResultService#perfFacts；成员展示信息读 M00 sys_user/sys_dept',
     '与任务完成率同一区间；成员维度按 assignee_id 聚合',
     '无任务且无 KR 的成员不出现在成员分布中（不补 0）；显式指定 assigneeId 时即使无数据也返回该成员行并标记 no_data',
     'realtime', 0, 'system', 'online',
     '明细型指标，主值 value 返回 null，数据见 members 数组',
     'system', 'system');

-- ⑦ 团队承接量（M04 维度 + M03 只读端口）
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('team_member_load', '团队承接量与在办', 'team',
     '团队在册成员的顶层任务承接量（taskTotal）与进行中数量（taskInProgress）。',
     '每位成员：taskTotal=该成员全部未删除顶层任务数；taskInProgress=其中 status=in_progress 的数量；团队合计为成员求和',
     'M04 团队与成员（成员集合）+ M03 任务与工作流（负载数据）',
     'M04 TeamMemberService#listVisible(teamId,false)（成员集合与负载；M04 内部经 M03 TaskPerfQueryService#memberTaskLoads 取数）',
     '负载为当前时点快照（不受统计区间限制，与 M04 团队详情 loadSummary 同口径）；统计区间仅约束响应时长',
     '无任务成员 taskTotal/taskInProgress 取 0（成员仍列出，便于看负载均衡）',
     'realtime', 0, 'system', 'online',
     '与 M04 团队详情 loadSummary 同源同口径，验收测试逐成员比对',
     'system', 'system');

-- ⑧ 团队响应时长
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('team_response_time', '团队响应时长', 'team',
     '团队成员已完成顶层任务的平均处理时长（创建到完成），用小时表示，衡量响应速度。',
     'Σ(DATE(done_time) - create_time 的时长) ÷ 已完成顶层任务数，单位小时保留 2 位；只统计统计区间内完成的顶层任务（DATE(done_time) 落在区间）',
     'M03 任务与工作流（经 TaskService 只读列表接口；禁止直连 task_* 表）',
     'M03 TaskService#list(pageQuery, keyword, status, assigneeId, krId, priority)（M03 既有只读查询；本指标按成员分页读取）',
     '统计区间 [startDate, endDate]，按 done_time 的自然日（+08:00）判定；创建时间取 task.create_time',
     '无已完成任务时返回 null 并标记 no_completed_task（不显示 0 小时）',
     'realtime', 0, 'system', 'online',
     '采样上限：每位成员最多读取 500 条已完成任务（5 页 × 100），超出时 sampled=true、以样本均值近似',
     'system', 'system');

-- ⑨ 负载均衡
INSERT IGNORE INTO insight_metric
    (metric_code, metric_name, metric_category, metric_definition, formula, source_module, source_detail,
     time_grain, missing_policy, refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
     create_by, update_by)
VALUES
    ('team_load_balance', '团队负载均衡度', 'team',
     '团队任务负载在成员之间的分布均衡程度，用于识别忙闲不均。',
     '成员承接量序列：avg=均值、max/min=最大最小、range=max-min、cv=标准差/均值（均值>0）；均衡度 level：cv<0.3 均衡(balanced)、0.3≤cv≤0.6 一般(moderate)、cv>0.6 失衡(unbalanced)',
     'M04 团队与成员 + M03 任务与工作流',
     'M04 TeamMemberService#listVisible(teamId,false)（其 load 数据经 M03 TaskPerfQueryService#memberTaskLoads）',
     '当前时点快照（承接量含全部状态顶层任务），不受统计区间影响',
     '在册成员数 < 2 或平均承接量为 0 时返回 null 并标记 insufficient_data',
     'realtime', 0, 'system', 'online',
     '只做分布描述，不做人员评价；busiestUserId 仅用于定位负载最高成员',
     'system', 'system');
