package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.insight.domain.dto.InsightMetricWriteRequest;
import com.teamflow.insight.service.InsightMetricService;
import com.teamflow.recruit.mapper.RecruitDashboardMapper;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M08 招聘看板服务（2.1.5）：渠道效果、漏斗转化、周期分析。
 *
 * <p><b>指标口径登记（硬要求）</b>：本模块 4 个看板指标的**定义/公式/数据来源/时间口径/缺失值策略**
 * 必须在 M13 指标字典登记后才允许出数（L0 §2.4.3 2.1.5「指标口径须在 M13 登记」）。登记走
 * M13 的 {@link InsightMetricService}（同模块 Spring Bean，等价于 `/api/insight/metrics` 的写入口），
 * 类目为 {@code cross}（跨域指标）。</p>
 *
 * <p><b>为什么是 {@code draft}</b>：M13 的「未登记口径不得上线」闸门要求指标编码命中其代码侧实现登记表；
 * 招聘指标属 M13 需求 7.4（跨域指标，M3 排期），本期 M13 尚未实现其计算，故登记为 {@code draft}（已登记、
 * 未上线）。M08 看板自身以「已登记」为出数前提，并在响应中回传字典状态，避免"无口径数字"。
 * 待 M13 7.4 落地并登记实现后，可一键把状态改为 online（M13 侧操作）。</p>
 *
 * <p><b>数据来源</b>：只查本模块自有 {@code recruit_*} 表（{@link RecruitDashboardMapper}），
 * 不直连其他模块业务表；数据范围按职位负责人/归属部门过滤（{@code @DataScope}）。</p>
 */
@Service
public class RecruitDashboardService extends RecruitServiceSupport {

    private static final Set<String> METRIC_CODES = Set.of(
            "recruit_funnel", "recruit_channel_effect", "recruit_time_to_hire", "recruit_offer_acceptance_rate");

    private static final List<MetricDef> METRIC_DEFS = List.of(
            new MetricDef("recruit_funnel", "招聘漏斗转化",
                    "招聘漏斗各阶段人数与相邻阶段转化率，用于观察「投递 → 面试 → Offer → 入职」的流失。",
                    "投递=区间内入库候选人数；进入面试=有面试记录的候选人数；Offer=有Offer记录的候选人数；"
                            + "入职=当前阶段为「已入职」的候选人数；转化率=下一层/上一层×100",
                    "候选入库时间 [startDate 00:00:00, endDate 23:59:59]（服务器时区 +08:00）",
                    "任一层无数据时该层取 0 并标记 no_data；转化率分母为 0 时返回 null（不显示 0%）",
                    true),
            new MetricDef("recruit_channel_effect", "渠道效果",
                    "按候选人来源渠道统计候选人数 / 拿到 Offer 人数 / 入职人数与转化率，用于评估渠道质量。",
                    "按 source_channel 分组：candidateCount=候选人数；offerCount=有 Offer 的候选人数；"
                            + "hiredCount=已入职人数；offerRate=offerCount/candidateCount×100；hiredRate=hiredCount/candidateCount×100",
                    "候选入库时间 [startDate, endDate]（+08:00）",
                    "渠道无数据时不返回该渠道行（不补 0）；比率分母为 0 时返回 null",
                    true),
            new MetricDef("recruit_time_to_hire", "平均招聘周期",
                    "已入职候选人从「入库」到「入职」的平均自然日天数，衡量招聘效率。",
                    "AVG(DATEDIFF(hire_time, create_time))，仅统计 hire_time 落在统计区间的已入职候选人；同时给出 min/max",
                    "入职时间 [startDate, endDate]（+08:00）",
                    "区间内无入职时返回 null 并标记 no_hired（不显示 0 天）",
                    false),
            new MetricDef("recruit_offer_acceptance_rate", "Offer 接受率",
                    "已答复 Offer 中被候选人接受的比例，衡量 Offer 竞争力。",
                    "已接受 Offer 数（含已入职）/（已接受 + 已拒绝）×100；仅统计区间内创建的 Offer",
                    "Offer 创建时间 [startDate, endDate]（+08:00）",
                    "无任何答复（分母为 0）时返回 null 并标记 no_response（不显示 0%）",
                    false)
    );

    private final RecruitDashboardMapper dashboardMapper;
    private final InsightMetricService insightMetricService;

    public RecruitDashboardService(
            RecruitPlatformMapper platformMapper,
            RecruitDashboardMapper dashboardMapper,
            InsightMetricService insightMetricService
    ) {
        super(platformMapper);
        this.dashboardMapper = dashboardMapper;
        this.insightMetricService = insightMetricService;
    }

    /**
     * 登记 4 个招聘看板指标的口径到 M13 指标字典（幂等：已存在则跳过，不覆盖人工维护的口径）。
     */
    public Map<String, Object> registerMetrics() {
        Map<String, Map<String, Object>> existing = dictionaryByName();
        List<Map<String, Object>> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (MetricDef def : METRIC_DEFS) {
            Map<String, Object> row = existing.get(def.code());
            if (row != null) {
                skipped.add(def.code());
                registered.add(registrationView(def, row));
                continue;
            }
            InsightMetricWriteRequest request = new InsightMetricWriteRequest();
            request.setMetricCode(def.code());
            request.setMetricName(def.name());
            request.setMetricCategory("cross");
            request.setMetricDefinition(def.definition());
            request.setFormula(def.formula());
            request.setSourceModule("M08 招聘（只读本模块 recruit_* 自有表）");
            request.setSourceDetail("M08 RecruitDashboardMapper#count*/select*（招聘看板 2.1.5 聚合）");
            request.setTimeGrain(def.timeGrain());
            request.setMissingPolicy(def.missingPolicy());
            request.setRefreshFrequency("realtime");
            request.setCacheTtlSeconds(0);
            request.setOwnerName("M08 招聘");
            request.setStatus("draft");
            request.setRemark("M08 2.1.5 招聘看板指标；完整上线依赖 M13 7.4 跨域指标（M3 排期），"
                    + "当前登记为 draft（已登记、未上线），由 M08 看板按「已登记」前提出数");
            insightMetricService.create(request);
            Map<String, Object> created = dictionaryByName().get(def.code());
            registered.add(registrationView(def, created));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registeredCount", registered.size());
        result.put("createdCodes", METRIC_DEFS.stream().map(MetricDef::code).filter(code -> !skipped.contains(code)).toList());
        result.put("skippedCodes", skipped);
        result.put("metrics", registered);
        result.put("dictionary", "M13 指标字典（insight_metric，category=cross）；查询：GET /api/insight/metrics");
        return result;
    }

    /** 指标登记状态视图（不含统计值）。 */
    public Map<String, Object> metrics() {
        Map<String, Map<String, Object>> existing = dictionaryByName();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricDef def : METRIC_DEFS) {
            rows.add(registrationView(def, existing.get(def.code())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics", rows);
        result.put("allRegistered", rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("registered"))));
        return result;
    }

    /**
     * 招聘看板总览：渠道效果、漏斗转化、周期分析。
     *
     * <p>出数前置：4 个指标必须已在 M13 指标字典登记（未登记返回 400 并提示先调用登记接口）。</p>
     */
    @DataScope
    public Map<String, Object> overview(String startDate, String endDate) {
        if (trimToNull(startDate) == null || trimToNull(endDate) == null) {
            throw new BusinessException("统计开始日期与结束日期不能为空");
        }
        java.time.LocalDate start = parseDate(startDate, "统计开始日期");
        java.time.LocalDate end = parseDate(endDate, "统计结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("统计结束日期不能早于开始日期");
        }
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        if (days > 366) {
            throw new BusinessException("统计区间最长 366 天");
        }
        Map<String, Map<String, Object>> dictionary = dictionaryByName();
        for (MetricDef def : METRIC_DEFS) {
            if (dictionary.get(def.code()) == null) {
                throw new BusinessException("指标「" + def.name() + "」(" + def.code()
                        + ") 未在 M13 指标字典登记口径，看板拒绝出数（2.1.5：指标口径须在 M13 登记）。"
                        + "请先调用 POST /api/recruit/dashboard/register-metrics 登记口径");
            }
        }

        String startTime = start + " 00:00:00";
        String endTime = end + " 23:59:59";
        long jobCount = dashboardMapper.countJobs(DataScopeContext.current(), startTime, endTime);
        long candidateCount = dashboardMapper.countCandidates(DataScopeContext.current(), startTime, endTime);
        long interviewCandidates = dashboardMapper.countCandidatesWithInterview(
                DataScopeContext.current(), startTime, endTime);
        long offerCandidates = dashboardMapper.countCandidatesWithOffer(
                DataScopeContext.current(), startTime, endTime);
        long hiredCount = dashboardMapper.countHired(DataScopeContext.current(), startTime, endTime);

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(funnelStage("candidates", "投递 / 入库", candidateCount, null, candidateCount == 0));
        funnel.add(funnelStage("interview", "进入面试", interviewCandidates, candidateCount, candidateCount == 0));
        funnel.add(funnelStage("offer", "拿到 Offer", offerCandidates, interviewCandidates, interviewCandidates == 0));
        funnel.add(funnelStage("hired", "入职", hiredCount, offerCandidates, offerCandidates == 0));

        List<Map<String, Object>> channels = new ArrayList<>();
        for (Map<String, Object> row : dashboardMapper.selectChannelStats(
                DataScopeContext.current(), startTime, endTime)) {
            Map<String, Object> channel = new LinkedHashMap<>();
            channel.put("channel", row.get("channel"));
            channel.put("channelLabel", channelLabel(String.valueOf(row.get("channel"))));
            channel.put("candidateCount", longOf(row.get("candidateCount")));
            channel.put("offerCount", longOf(row.get("offerCount")));
            channel.put("hiredCount", longOf(row.get("hiredCount")));
            channel.put("offerRate", percent(BigDecimal.valueOf(longOf(row.get("offerCount"))),
                    BigDecimal.valueOf(longOf(row.get("candidateCount")))));
            channel.put("hiredRate", percent(BigDecimal.valueOf(longOf(row.get("hiredCount"))),
                    BigDecimal.valueOf(longOf(row.get("candidateCount")))));
            channels.add(channel);
        }

        Map<String, Object> offerRaw = dashboardMapper.selectOfferStats(
                DataScopeContext.current(), startTime, endTime);
        long offerCount = longOf(offerRaw == null ? null : offerRaw.get("offerCount"));
        long accepted = longOf(offerRaw == null ? null : offerRaw.get("acceptedCount"));
        long declined = longOf(offerRaw == null ? null : offerRaw.get("declinedCount"));
        long responded = longOf(offerRaw == null ? null : offerRaw.get("respondedCount"));
        BigDecimal acceptanceRate = percent(BigDecimal.valueOf(accepted), BigDecimal.valueOf(responded));
        Map<String, Object> offerStats = new LinkedHashMap<>();
        offerStats.put("offerCount", offerCount);
        offerStats.put("acceptedCount", accepted);
        offerStats.put("declinedCount", declined);
        offerStats.put("respondedCount", responded);
        offerStats.put("acceptanceRate", acceptanceRate);
        offerStats.put("missing", responded == 0);
        offerStats.put("note", responded == 0 ? "区间内无候选人答复 Offer（no_response，不显示 0%）" : null);

        Map<String, Object> cycleRaw = dashboardMapper.selectHireCycle(
                DataScopeContext.current(), startTime, endTime);
        long cycleHired = longOf(cycleRaw == null ? null : cycleRaw.get("hiredCount"));
        BigDecimal avgDays = cycleRaw == null ? null : numberAsBigDecimal(cycleRaw.get("avgDays"));
        Map<String, Object> hireCycle = new LinkedHashMap<>();
        hireCycle.put("hiredCount", cycleHired);
        hireCycle.put("avgDays", scale2(avgDays));
        hireCycle.put("minDays", cycleRaw == null ? null : numberAsLong(cycleRaw.get("minDays")));
        hireCycle.put("maxDays", cycleRaw == null ? null : numberAsLong(cycleRaw.get("maxDays")));
        hireCycle.put("missing", cycleHired == 0);
        hireCycle.put("note", cycleHired == 0 ? "区间内无入职（no_hired，不显示 0 天）" : null);

        Map<String, Object> interviewRaw = dashboardMapper.selectInterviewStats(
                DataScopeContext.current(), startTime, endTime);
        Map<String, Object> interviewStats = new LinkedHashMap<>();
        interviewStats.put("avgRounds", interviewRaw == null ? null
                : scale2(numberAsBigDecimal(interviewRaw.get("avgRounds"))));
        interviewStats.put("interviewedCandidates", longOf(interviewRaw == null ? null
                : interviewRaw.get("interviewedCandidates")));

        List<Map<String, Object>> monthlyHires = dashboardMapper.selectHiredByMonth(
                DataScopeContext.current(), startTime, endTime);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobCount", jobCount);
        summary.put("candidateCount", candidateCount);
        summary.put("interviewCandidateCount", interviewCandidates);
        summary.put("offerCandidateCount", offerCandidates);
        summary.put("hiredCount", hiredCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recruit_funnel", metricPayload(dictionary.get("recruit_funnel"),
                null, null, candidateCount == 0, "明细型指标：数据见 funnel 数组"));
        metrics.put("recruit_channel_effect", metricPayload(dictionary.get("recruit_channel_effect"),
                null, null, channels.isEmpty(), "明细型指标：数据见 channels 数组"));
        metrics.put("recruit_time_to_hire", metricPayload(dictionary.get("recruit_time_to_hire"),
                scale2(avgDays), "天", cycleHired == 0,
                cycleHired == 0 ? "区间内无入职：招聘周期无数据（no_hired）" : "按 " + cycleHired + " 名入职候选人计算"));
        metrics.put("recruit_offer_acceptance_rate", metricPayload(dictionary.get("recruit_offer_acceptance_rate"),
                acceptanceRate, "%", responded == 0,
                responded == 0 ? "区间内无 Offer 答复：接受率无数据（no_response）" : "已答复 " + responded + " 份 Offer"));

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("timezone", "+08:00");
        period.put("rangeDays", days);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("summary", summary);
        result.put("funnel", funnel);
        result.put("channels", channels);
        result.put("monthlyHires", monthlyHires);
        result.put("hireCycle", hireCycle);
        result.put("offerStats", offerStats);
        result.put("interviewStats", interviewStats);
        result.put("metrics", metrics);
        result.put("metricRegistered", true);
        result.put("readOnly", true);
        result.put("cache", "不缓存（实时聚合 recruit_* 自有表；字典 cache_ttl_seconds=0）");
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Map<String, Object>> dictionaryByName() {
        PageResult<Map<String, Object>> page = insightMetricService.list(
                new PageQuery(1, 100, null, null), null, "cross", null);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : page.rows()) {
            String code = String.valueOf(row.get("metricCode"));
            if (METRIC_CODES.contains(code)) {
                result.put(code, row);
            }
        }
        return result;
    }

    private Map<String, Object> registrationView(MetricDef def, Map<String, Object> dictionaryRow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metricCode", def.code());
        row.put("metricName", def.name());
        row.put("definition", def.definition());
        row.put("formula", def.formula());
        row.put("timeGrain", def.timeGrain());
        row.put("missingPolicy", def.missingPolicy());
        row.put("sourceModule", "M08 招聘");
        row.put("registered", dictionaryRow != null);
        row.put("dictionaryId", dictionaryRow == null ? null : dictionaryRow.get("id"));
        row.put("dictionaryStatus", dictionaryRow == null ? null : dictionaryRow.get("status"));
        row.put("dictionaryStatusLabel", dictionaryRow == null ? null : dictionaryRow.get("statusLabel"));
        row.put("note", dictionaryRow == null
                ? "未登记：看板拒绝出数（请调用 register-metrics）"
                : "已登记于 M13 指标字典；完整上线依赖 M13 7.4（M3 排期）");
        return row;
    }

    private Map<String, Object> funnelStage(String stage, String label, long value, Long previous, boolean missing) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("label", label);
        row.put("value", value);
        row.put("conversionRate", previous == null ? null
                : percent(BigDecimal.valueOf(value), BigDecimal.valueOf(previous)));
        row.put("missing", missing);
        return row;
    }

    private Map<String, Object> metricPayload(Map<String, Object> dictionaryRow, Object value, String unit,
                                              boolean missing, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", dictionaryRow.get("metricCode"));
        payload.put("name", dictionaryRow.get("metricName"));
        payload.put("definition", dictionaryRow.get("metricDefinition"));
        payload.put("formula", dictionaryRow.get("formula"));
        payload.put("sourceModule", dictionaryRow.get("sourceModule"));
        payload.put("sourceDetail", dictionaryRow.get("sourceDetail"));
        payload.put("timeGrain", dictionaryRow.get("timeGrain"));
        payload.put("missingPolicy", dictionaryRow.get("missingPolicy"));
        payload.put("refreshFrequency", dictionaryRow.get("refreshFrequency"));
        payload.put("dictionaryStatus", dictionaryRow.get("status"));
        payload.put("dictionaryStatusLabel", dictionaryRow.get("statusLabel"));
        payload.put("value", value);
        payload.put("unit", unit);
        payload.put("missing", missing);
        payload.put("note", note);
        return payload;
    }

    private String channelLabel(String channel) {
        return switch (channel == null ? "" : channel) {
            case "referral" -> "内推";
            case "website" -> "官网";
            case "social" -> "社交招聘";
            case "agency" -> "猎头";
            case "internal" -> "内部转岗";
            default -> "其他";
        };
    }

    /** 指标口径定义（登记与状态视图用）。 */
    private record MetricDef(
            String code,
            String name,
            String definition,
            String formula,
            String timeGrain,
            String missingPolicy,
            boolean detail
    ) {
    }
}
