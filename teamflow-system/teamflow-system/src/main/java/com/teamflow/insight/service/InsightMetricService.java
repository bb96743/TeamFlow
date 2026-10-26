package com.teamflow.insight.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.insight.domain.dto.InsightMetricWriteRequest;
import com.teamflow.insight.domain.vo.InsightIdResponse;
import com.teamflow.insight.mapper.InsightMetricMapper;
import com.teamflow.insight.metric.InsightMetricRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M13 指标字典服务（7.1）：定义、口径、数据来源模块、刷新频率的登记与维护。
 *
 * <p><b>R1 未登记口径不得上线</b>——本服务用两侧闸门保证：</p>
 * <ol>
 *   <li><b>登记完整性</b>：上线时定义/公式/来源模块/来源接口/时间口径/缺失值策略必须齐全；</li>
 *   <li><b>实现可追溯</b>：上线时指标编码必须命中 {@link InsightMetricRegistry}（M13 真的有该口径的计算实现），
 *       未实现的口径不能通过改库或接口"混上线"；</li>
 *   <li><b>出数闸门</b>：看板接口只认 {@code status=online} 的字典行（见 {@link #loadOnlineMetrics}），
 *       下线即停止出数并给出明确提示。</li>
 * </ol>
 *
 * <p><b>缓存策略</b>：本期所有指标 {@code cacheTtlSeconds=0}（不缓存），看板实时聚合；字典字段保留刷新频率与
 * 缓存失效时长，用于将来启用缓存时说明"只缓存聚合结果、可随时重建、不成为第二数据源"。</p>
 */
@Service
public class InsightMetricService extends InsightServiceSupport {

    private static final Set<String> CATEGORIES = Set.of("task", "team", "cross");
    private static final Set<String> STATUSES = Set.of("draft", "online", "offline");
    private static final Set<String> REFRESH_FREQUENCIES = Set.of("realtime", "5min", "1h", "daily");

    private final InsightMetricMapper metricMapper;

    public InsightMetricService(InsightMetricMapper metricMapper) {
        this.metricMapper = metricMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String category, String status) {
        Page page = pageOf(query);
        long total = metricMapper.countMetrics(trimToNull(keyword), trimToNull(category), trimToNull(status));
        List<Map<String, Object>> rows = metricMapper.selectMetrics(
                trimToNull(keyword), trimToNull(category), trimToNull(status), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = requireMetric(id);
        row.put("implemented", InsightMetricRegistry.isRegistered(String.valueOf(row.get("metricCode"))));
        return decorate(row);
    }

    @Transactional
    public InsightIdResponse create(InsightMetricWriteRequest request) {
        if (request == null) {
            throw new BusinessException("指标登记内容不能为空");
        }
        String code = required(request.getMetricCode(), "指标编码不能为空");
        if (metricMapper.countCode(code, null) > 0) {
            throw new BusinessException("指标编码已存在：" + code);
        }
        Map<String, Object> row = buildRow(request, code);
        requireOnlineGate(row);
        metricMapper.insertMetric(row, currentUsername());
        return new InsightIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, InsightMetricWriteRequest request) {
        if (request == null) {
            throw new BusinessException("指标登记内容不能为空");
        }
        Map<String, Object> existing = requireMetric(id);
        String existingCode = String.valueOf(existing.get("metricCode"));
        if (trimToNull(request.getMetricCode()) != null && !existingCode.equals(trimToNull(request.getMetricCode()))) {
            throw new BusinessException("指标编码是不可变标识，不能修改（当前：" + existingCode + "）");
        }
        Map<String, Object> row = buildRow(request, existingCode);
        requireOnlineGate(row);
        metricMapper.updateMetric(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requireMetric(id);
        if ("online".equals(String.valueOf(existing.get("status")))) {
            throw new BusinessException("已上线指标不能删除：请先下线（status=offline）再删除，避免看板口径凭空消失");
        }
        metricMapper.softDeleteMetric(id, currentUsername());
    }

    public Map<String, Object> requireMetric(Long id) {
        if (id == null) {
            throw new BusinessException("指标 ID 不能为空");
        }
        Map<String, Object> row = metricMapper.selectMetricById(id);
        if (row == null) {
            throw new BusinessException("指标不存在或已删除");
        }
        return row;
    }

    /**
     * 看板出数闸门：按编码取「已上线」字典行；任一必需指标未登记口径 / 未上线 / 代码侧未实现，直接拒绝出数。
     *
     * @param requiredCodes 本次统计依赖的指标编码（见 {@link InsightMetricRegistry} 的 TASK_BOARD_METRICS 等）
     * @return 编码 → 字典行
     */
    public Map<String, Map<String, Object>> loadOnlineMetrics(List<String> requiredCodes) {
        Map<String, Map<String, Object>> online = new LinkedHashMap<>();
        for (Map<String, Object> row : metricMapper.selectOnlineMetrics()) {
            online.put(String.valueOf(row.get("metricCode")), row);
        }
        for (String code : requiredCodes) {
            if (!InsightMetricRegistry.isRegistered(code)) {
                throw new BusinessException("指标「" + code + "」未在 M13 指标实现登记，未登记口径的指标不得上线（R1）");
            }
            Map<String, Object> row = online.get(code);
            if (row == null) {
                throw new BusinessException("指标「" + code + "」未在指标字典登记口径或未上线（status 不为 online），"
                        + "看板拒绝出数（R1：未登记口径的指标不得上线）");
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Object> buildRow(InsightMetricWriteRequest request, String code) {
        String status = defaultString(request.getStatus(), "draft");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("指标状态只能为 draft / online / offline");
        }
        String category = defaultString(request.getMetricCategory(),
                defaultString(InsightMetricRegistry.categoryOf(code), "task"));
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("指标类别只能为 task / team / cross");
        }
        String refresh = defaultString(request.getRefreshFrequency(), "realtime");
        if (!REFRESH_FREQUENCIES.contains(refresh)) {
            throw new BusinessException("刷新频率只能为 realtime / 5min / 1h / daily");
        }
        int cacheTtl = request.getCacheTtlSeconds() == null ? 0 : request.getCacheTtlSeconds();
        if (cacheTtl < 0) {
            throw new BusinessException("缓存失效时长不能为负数（0 表示不缓存）");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metricCode", code);
        row.put("metricName", required(request.getMetricName(), "指标名称不能为空"));
        row.put("metricCategory", category);
        row.put("metricDefinition", required(request.getMetricDefinition(), "指标定义不能为空（R1 口径登记）"));
        row.put("formula", required(request.getFormula(), "计算公式不能为空（R1 口径登记）"));
        row.put("sourceModule", required(request.getSourceModule(), "数据来源模块不能为空（R1 口径登记）"));
        row.put("sourceDetail", required(request.getSourceDetail(), "数据来源接口不能为空（R1 口径登记）"));
        row.put("timeGrain", required(request.getTimeGrain(), "时间口径不能为空（R1 口径登记）"));
        row.put("missingPolicy", required(request.getMissingPolicy(), "缺失值处理不能为空（R1 口径登记）"));
        row.put("refreshFrequency", refresh);
        row.put("cacheTtlSeconds", cacheTtl);
        row.put("ownerName", defaultString(request.getOwnerName(), currentUsername()));
        row.put("status", status);
        row.put("remark", trimToNull(request.getRemark()));
        return row;
    }

    /** 上线闸门：未在 M13 指标实现登记的口径不得上线。 */
    private void requireOnlineGate(Map<String, Object> row) {
        if (!"online".equals(String.valueOf(row.get("status")))) {
            return;
        }
        String code = String.valueOf(row.get("metricCode"));
        if (!InsightMetricRegistry.isRegistered(code)) {
            throw new BusinessException("指标「" + code + "」未在 M13 指标实现登记，未登记口径的指标不得上线（R1）");
        }
    }

    private Map<String, Object> decorate(Map<String, Object> row) {
        row.put("statusLabel", statusText(String.valueOf(row.get("status"))));
        row.put("categoryLabel", categoryText(String.valueOf(row.get("metricCategory"))));
        row.put("refreshFrequencyLabel", switch (String.valueOf(row.get("refreshFrequency"))) {
            case "realtime" -> "实时";
            case "5min" -> "每 5 分钟";
            case "1h" -> "每小时";
            case "daily" -> "每日";
            default -> String.valueOf(row.get("refreshFrequency"));
        });
        row.put("cacheStrategy", intOf(row.get("cacheTtlSeconds")) == 0
                ? "不缓存（实时聚合）"
                : "聚合结果缓存 " + row.get("cacheTtlSeconds") + " 秒后可重建");
        return row;
    }
}
