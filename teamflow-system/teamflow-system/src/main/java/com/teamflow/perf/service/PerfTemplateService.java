package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.perf.domain.dto.PerfTemplateItemRequest;
import com.teamflow.perf.domain.dto.PerfTemplateWriteRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.mapper.PerfScoreMapper;
import com.teamflow.perf.mapper.PerfTemplateItemMapper;
import com.teamflow.perf.mapper.PerfTemplateMapper;
import com.teamflow.perf.metric.PerfMetric;
import com.teamflow.perf.metric.PerfMetricDictionary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M05 指标模板服务：模板与指标项的增删改查、权重校验（R6）与指标字典登记校验（R2）。
 *
 * <p><b>R6 权重校验</b>：与 L0 §9.1 {@code D-2026-10-25-04} 同构的两档校验——草稿态允许合计 ≤ 100
 * （可先存半成品），**启用（{@code enabled}）与开周期前必须恰好 = 100**。</p>
 *
 * <p><b>R2 硬门槛</b>：可归集指标项必须引用指标字典已登记的 {@code metricCode}，
 * 否则保存即被拒绝（"未登记口径的指标不得上线"）。</p>
 */
@Service
public class PerfTemplateService extends PerfServiceSupport {

    private static final Set<String> STATUSES = Set.of("draft", "enabled", "disabled");
    private static final Set<String> CATEGORIES = Set.of("collectable", "subjective");

    private final PerfTemplateMapper templateMapper;
    private final PerfTemplateItemMapper itemMapper;
    private final PerfScoreMapper scoreMapper;
    private final com.teamflow.perf.mapper.PerfCycleMapper cycleMapper;

    public PerfTemplateService(
            PerfTemplateMapper templateMapper,
            PerfTemplateItemMapper itemMapper,
            PerfScoreMapper scoreMapper,
            com.teamflow.perf.mapper.PerfCycleMapper cycleMapper
    ) {
        this.templateMapper = templateMapper;
        this.itemMapper = itemMapper;
        this.scoreMapper = scoreMapper;
        this.cycleMapper = cycleMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status) {
        Page page = pageOf(query);
        long total = templateMapper.countTemplates(trimToNull(keyword), trimToNull(status));
        List<Map<String, Object>> rows = templateMapper.selectTemplates(
                trimToNull(keyword), trimToNull(status), page.offset(), page.pageSize());
        rows.forEach(row -> row.put("statusLabel", statusLabel(String.valueOf(row.get("status")))));
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> template = requireTemplate(id);
        template.put("statusLabel", statusLabel(String.valueOf(template.get("status"))));
        template.put("items", items(id));
        return template;
    }

    @Transactional
    public PerfIdResponse create(PerfTemplateWriteRequest request) {
        String code = required(request.getTemplateCode(), "模板编码不能为空");
        String name = required(request.getTemplateName(), "模板名称不能为空");
        String status = defaultString(request.getStatus(), "draft");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("模板状态只能为 draft、enabled 或 disabled");
        }
        if (templateMapper.countTemplateCode(code, null) > 0) {
            throw new BusinessException("模板编码已存在：" + code);
        }
        List<Map<String, Object>> items = validateAndBuildItems(request.getItems(), status);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateCode", code);
        row.put("templateName", name);
        row.put("description", trimToNull(request.getDescription()));
        row.put("status", status);
        row.put("totalWeight", totalWeight(items));
        row.put("remark", trimToNull(request.getRemark()));
        templateMapper.insertTemplate(row, currentUsername());
        Long templateId = numberAsLong(row.get("id"));
        for (Map<String, Object> item : items) {
            item.put("templateId", templateId);
            itemMapper.insertItem(item, currentUsername());
        }
        return new PerfIdResponse(templateId);
    }

    @Transactional
    public void update(Long id, PerfTemplateWriteRequest request) {
        Map<String, Object> existing = requireTemplate(id);
        if (scoreMapper.countScoresByTemplate(id) > 0) {
            throw new BusinessException("模板已产生归集或评分数据，不能修改指标项结构（请新建模板）");
        }
        if (templateMapper.countActiveCyclesUsingTemplate(id) > 0) {
            throw new BusinessException("模板已被非草稿绩效周期使用，不能修改指标项结构（请新建模板）");
        }
        String name = request.getTemplateName() == null
                ? String.valueOf(existing.get("templateName"))
                : required(request.getTemplateName(), "模板名称不能为空");
        String status = defaultString(request.getStatus(), String.valueOf(existing.get("status")));
        if (!STATUSES.contains(status)) {
            throw new BusinessException("模板状态只能为 draft、enabled 或 disabled");
        }
        boolean replaceItems = request.getItems() != null;
        List<Map<String, Object>> items = replaceItems
                ? validateAndBuildItems(request.getItems(), status)
                : itemMapper.selectItems(id);
        if (replaceItems && !items.isEmpty()) {
            // 结构可改：先软删旧项再插入新项（仅在无评分数据、未被非草稿周期使用时允许到此处）
            itemMapper.softDeleteItemsByTemplate(id, currentUsername());
            for (Map<String, Object> item : items) {
                item.remove("id");
                item.put("templateId", id);
                itemMapper.insertItem(item, currentUsername());
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateName", name);
        row.put("description", request.getDescription() == null
                ? existing.get("description") : trimToNull(request.getDescription()));
        row.put("status", status);
        row.put("totalWeight", totalWeight(items));
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        templateMapper.updateTemplate(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        requireTemplate(id);
        if (cycleMapper.countCyclesByTemplate(id) > 0) {
            throw new BusinessException("模板已被绩效周期引用，不能删除");
        }
        templateMapper.softDeleteTemplate(id, currentUsername());
    }

    /** 列表/详情用指标项。 */
    public List<Map<String, Object>> items(Long templateId) {
        List<Map<String, Object>> rows = itemMapper.selectItems(templateId);
        rows.forEach(row -> {
            row.put("itemCategoryLabel", "subjective".equals(String.valueOf(row.get("itemCategory"))) ? "主观" : "可归集");
            row.put("requireComment", intOf(row.get("requireComment")) == 1);
        });
        return rows;
    }

    public Map<String, Object> requireTemplate(Long id) {
        if (id == null) {
            throw new BusinessException("指标模板 ID 不能为空");
        }
        Map<String, Object> row = templateMapper.selectTemplateById(id);
        if (row == null) {
            throw new BusinessException("指标模板不存在或已删除");
        }
        return row;
    }

    public List<Map<String, Object>> requireItems(Long templateId) {
        List<Map<String, Object>> rows = itemMapper.selectItems(templateId);
        if (rows.isEmpty()) {
            throw new BusinessException("指标模板尚未配置指标项，不能开启绩效周期");
        }
        return rows;
    }

    /**
     * 周期开启 / 归档前的模板完整性校验（R6 + R2）：
     * 模板存在、状态为启用、指标项非空、权重合计恰好 100、每个可归集项都已登记指标字典四要素。
     */
    public void requireLaunchReady(Long templateId) {
        Map<String, Object> template = requireTemplate(templateId);
        if (!"enabled".equals(String.valueOf(template.get("status")))) {
            throw new BusinessException("指标模板「" + template.get("templateName") + "」未启用，不能开启绩效周期");
        }
        List<Map<String, Object>> items = requireItems(templateId);
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            sum = sum.add(zeroIfNull(numberAsBigDecimal(item.get("weight"))));
            if ("collectable".equals(String.valueOf(item.get("itemCategory")))) {
                PerfMetric metric = PerfMetricDictionary.find(String.valueOf(item.get("metricCode")))
                        .orElseThrow(() -> new BusinessException(
                                "指标项「" + item.get("itemName") + "」引用的可归集指标「" + item.get("metricCode")
                                        + "」未在指标字典登记四要素（R2：未登记不得上线）"));
                if (metric.code() == null) {
                    throw new BusinessException("指标字典登记异常");
                }
            }
        }
        BigDecimal total = sum.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(HUNDRED) != 0) {
            throw new BusinessException("指标模板权重合计必须为 100%，当前为 " + total.toPlainString() + "%（R6）");
        }
    }

    // ------------------------------------------------------------------
    // 内部校验
    // ------------------------------------------------------------------

    private List<Map<String, Object>> validateAndBuildItems(List<PerfTemplateItemRequest> requests, String templateStatus) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("指标模板至少需要一个指标项");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (PerfTemplateItemRequest request : requests) {
            String itemCode = required(request.getItemCode(), "指标项编码不能为空");
            if (!codes.add(itemCode)) {
                throw new BusinessException("指标项编码重复：" + itemCode);
            }
            String itemName = required(request.getItemName(), "指标项名称不能为空");
            String category = defaultString(request.getItemCategory(), "collectable");
            if (!CATEGORIES.contains(category)) {
                throw new BusinessException("指标项「" + itemName + "」类别只能为 collectable 或 subjective");
            }
            BigDecimal weight = request.getWeight();
            if (weight == null || weight.signum() <= 0) {
                throw new BusinessException("指标项「" + itemName + "」权重必须大于 0");
            }
            weight = weight.setScale(2, RoundingMode.HALF_UP);
            BigDecimal maxScore = request.getMaxScore() == null
                    ? HUNDRED : request.getMaxScore().setScale(2, RoundingMode.HALF_UP);
            if (maxScore.signum() <= 0) {
                throw new BusinessException("指标项「" + itemName + "」分值上限必须大于 0");
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemCode", itemCode);
            item.put("itemName", itemName);
            item.put("itemCategory", category);
            item.put("weight", weight);
            item.put("maxScore", maxScore);
            item.put("scoreStandard", trimToNull(request.getScoreStandard()));
            item.put("sort", request.getSort() == null ? 0 : request.getSort());
            item.put("remark", trimToNull(request.getRemark()));
            if ("collectable".equals(category)) {
                String metricCode = required(request.getMetricCode(),
                        "可归集指标项「" + itemName + "」必须指定指标字典编码（R2）");
                if (!PerfMetricDictionary.isRegistered(metricCode)) {
                    throw new BusinessException("可归集指标「" + metricCode
                            + "」未在指标字典登记四要素（数据来源模块/计算公式/取数时间范围/缺失值处理），不得上线（R2）");
                }
                item.put("metricCode", metricCode.trim());
                item.put("requireComment", 0);
            } else {
                item.put("metricCode", null);
                // R1：主观类必须填评语，忽略外部传入的 requireComment=false
                item.put("requireComment", 1);
            }
            items.add(item);
            sum = sum.add(weight);
        }
        BigDecimal total = sum.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(HUNDRED) > 0) {
            throw new BusinessException("指标模板权重合计不得超过 100%，当前为 " + total.toPlainString() + "%（R6）");
        }
        if ("enabled".equals(templateStatus) && total.compareTo(HUNDRED) != 0) {
            throw new BusinessException("模板启用前指标权重合计必须为 100%，当前为 " + total.toPlainString()
                    + "%（R6；草稿态允许 ≤100）");
        }
        return items;
    }

    private BigDecimal totalWeight(List<Map<String, Object>> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            sum = sum.add(zeroIfNull(numberAsBigDecimal(item.get("weight"))));
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "draft" -> "草稿";
            case "enabled" -> "启用";
            case "disabled" -> "停用";
            default -> status;
        };
    }
}
