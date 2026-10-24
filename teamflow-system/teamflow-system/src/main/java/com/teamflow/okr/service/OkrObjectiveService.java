package com.teamflow.okr.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.okr.domain.dto.OkrObjectiveWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.mapper.OkrAlignMapper;
import com.teamflow.okr.mapper.OkrKeyResultMapper;
import com.teamflow.okr.mapper.OkrObjectiveMapper;
import com.teamflow.okr.mapper.OkrPlatformMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M02 目标服务：三层归属（公司 / 部门 / 个人）、树查询、权重约束与进度归集。
 *
 * <p>规则落地：
 * <ul>
 *   <li>R2 承接人非空：{@code owner_id} 为空时只能存草稿，不得置为进行中/已完成；</li>
 *   <li>R4 权重约束：同周期同父级下子目标权重合计不得超过 100%，进入进行中前必须恰好为 100%；</li>
 *   <li>R5 归档只读：周期归档后目标全部只读；</li>
 *   <li>1.1.5 对齐度检查：输出无承接人目标、无 KR 的目标；无目标归属的任务依赖 M03，本期返回空列表。</li>
 * </ul>
 */
@Service
public class OkrObjectiveService extends OkrServiceSupport {

    private static final Set<String> LEVELS = Set.of("company", "dept", "personal");
    private static final Set<String> STATUSES = Set.of("draft", "active", "done");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TREE_GUARD = 100;

    private final OkrObjectiveMapper objectiveMapper;
    private final OkrKeyResultMapper keyResultMapper;
    private final OkrAlignMapper alignMapper;
    private final OkrPlatformMapper platformMapper;
    private final OkrPeriodService periodService;

    public OkrObjectiveService(
            OkrObjectiveMapper objectiveMapper,
            OkrKeyResultMapper keyResultMapper,
            OkrAlignMapper alignMapper,
            OkrPlatformMapper platformMapper,
            OkrPeriodService periodService
    ) {
        this.objectiveMapper = objectiveMapper;
        this.keyResultMapper = keyResultMapper;
        this.alignMapper = alignMapper;
        this.platformMapper = platformMapper;
        this.periodService = periodService;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(
            PageQuery query, Long periodId, String level, Long ownerId, String status, String keyword
    ) {
        Page page = pageOf(query);
        long total = objectiveMapper.countObjectives(periodId, level, ownerId, status, keyword, DataScopeContext.current());
        List<Map<String, Object>> rows = objectiveMapper.selectObjectives(
                periodId, level, ownerId, status, keyword, DataScopeContext.current(), page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public List<Map<String, Object>> tree(Long periodId) {
        List<Map<String, Object>> rows = objectiveMapper.selectObjectiveTreeRows(periodId, DataScopeContext.current());
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put("children", new ArrayList<Map<String, Object>>());
            byId.put(numberAsLong(node.get("id")), node);
        }
        for (Map<String, Object> node : byId.values()) {
            Long parentId = numberAsLong(node.get("parentId"));
            Map<String, Object> parent = parentId == null ? null : byId.get(parentId);
            if (parent == null || parent == node) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        }
        return roots;
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = visibleObjective(id);
        if (row == null) {
            throw new BusinessException("目标不存在或无权访问");
        }
        return row;
    }

    /**
     * 按当前数据范围读取目标（不抛异常）；调用方需已处于 {@code @DataScope} 上下文中，
     * 不能自行再加注解，否则嵌套切面会提前清理外层数据范围上下文。
     */
    public Map<String, Object> visibleObjective(Long id) {
        if (id == null) {
            return null;
        }
        return objectiveMapper.selectObjective(id, DataScopeContext.current());
    }

    @Transactional
    public OkrIdResponse create(OkrObjectiveWriteRequest request) {
        Long periodId = request.getPeriodId();
        if (periodId == null) {
            throw new BusinessException("所属周期不能为空");
        }
        periodService.requireEditable(periodId);
        String title = required(request.getObjectiveTitle(), "目标标题不能为空");
        String level = defaultString(request.getLevel(), "company");
        if (!LEVELS.contains(level)) {
            throw new BusinessException("目标归属层级只能为 company、dept 或 personal");
        }
        String status = defaultString(request.getStatus(), "draft");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("目标状态只能为 draft、active 或 done");
        }
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        if (parentId > 0) {
            requireParentObjective(parentId, periodId, null);
        }
        Long deptId = request.getDeptId();
        validateDept(level, deptId);
        Long ownerId = request.getOwnerId();
        validateOwner(status, ownerId);
        BigDecimal weight = request.getWeight() == null ? BigDecimal.ZERO : request.getWeight();
        validateWeight(periodId, parentId, null, weight, status);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodId", periodId);
        row.put("parentId", parentId);
        row.put("objectiveTitle", title);
        row.put("objectiveDesc", trimToNull(request.getObjectiveDesc()));
        row.put("level", level);
        row.put("deptId", deptId);
        row.put("ownerId", ownerId);
        row.put("weight", weight);
        row.put("progress", BigDecimal.ZERO);
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        objectiveMapper.insertObjective(row, currentUsername());
        Long id = numberAsLong(row.get("id"));
        if (parentId > 0) {
            refreshProgress(parentId);
        }
        return new OkrIdResponse(id);
    }

    @Transactional
    public void update(Long id, OkrObjectiveWriteRequest request) {
        Map<String, Object> existing = requireObjective(id);
        Long periodId = numberAsLong(existing.get("periodId"));
        periodService.requireEditable(periodId);

        String title = request.getObjectiveTitle() == null
                ? String.valueOf(existing.get("objectiveTitle"))
                : required(request.getObjectiveTitle(), "目标标题不能为空");
        String level = request.getLevel() == null ? String.valueOf(existing.get("level")) : defaultString(request.getLevel(), "company");
        if (!LEVELS.contains(level)) {
            throw new BusinessException("目标归属层级只能为 company、dept 或 personal");
        }
        String status = request.getStatus() == null ? String.valueOf(existing.get("status")) : defaultString(request.getStatus(), "draft");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("目标状态只能为 draft、active 或 done");
        }
        Long oldParentId = numberAsLong(existing.get("parentId"));
        Long parentId = request.getParentId() == null ? oldParentId : request.getParentId();
        if (parentId == null) {
            parentId = 0L;
        }
        if (parentId > 0) {
            requireParentObjective(parentId, periodId, id);
        }
        Long deptId = request.getDeptId() == null ? numberAsLong(existing.get("deptId")) : request.getDeptId();
        validateDept(level, deptId);
        Long ownerId = request.getOwnerId() == null ? numberAsLong(existing.get("ownerId")) : request.getOwnerId();
        validateOwner(status, ownerId);
        BigDecimal weight = request.getWeight() == null ? numberAsBigDecimal(existing.get("weight")) : request.getWeight();
        if (weight == null) {
            weight = BigDecimal.ZERO;
        }
        validateWeight(periodId, parentId, id, weight, status);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodId", periodId);
        row.put("parentId", parentId);
        row.put("objectiveTitle", title);
        row.put("objectiveDesc", request.getObjectiveDesc() == null
                ? existing.get("objectiveDesc") : trimToNull(request.getObjectiveDesc()));
        row.put("level", level);
        row.put("deptId", deptId);
        row.put("ownerId", ownerId);
        row.put("weight", weight);
        row.put("status", status);
        row.put("sort", request.getSort() == null
                ? (existing.get("sort") == null ? 0 : numberAsLong(existing.get("sort")).intValue())
                : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        objectiveMapper.updateObjective(id, row, currentUsername());
        if (oldParentId != null && oldParentId > 0 && !oldParentId.equals(parentId)) {
            refreshProgress(oldParentId);
        }
        refreshProgress(id);
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requireObjective(id);
        Long periodId = numberAsLong(existing.get("periodId"));
        periodService.requireEditable(periodId);
        if (objectiveMapper.countChildObjectives(id) > 0) {
            throw new BusinessException("目标下存在子目标，不能删除");
        }
        if (keyResultMapper.countKeyResults(id) > 0) {
            throw new BusinessException("目标下存在关键结果，请先删除关键结果");
        }
        alignMapper.softDeleteAlignsOfObjective(id, currentUsername());
        objectiveMapper.softDeleteObjective(id, currentUsername());
        Long parentId = numberAsLong(existing.get("parentId"));
        if (parentId != null && parentId > 0) {
            refreshProgress(parentId);
        }
    }

    /**
     * 1.1.5 对齐度检查（骨架）：列出断点清单。
     *
     * <p>「无目标归属的任务」依赖 M03 任务数据（且需 G-3 领域事件接入），本期固定返回空列表并注明待接入。</p>
     */
    @DataScope
    public Map<String, Object> alignCheck(Long periodId) {
        List<Map<String, Object>> unowned = objectiveMapper.selectUnownedObjectives(periodId, DataScopeContext.current());
        List<Map<String, Object>> withoutKr = objectiveMapper.selectObjectivesWithoutKr(periodId, DataScopeContext.current());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodId", periodId);
        result.put("unownedObjectives", unowned);
        result.put("objectivesWithoutKr", withoutKr);
        result.put("tasksWithoutObjective", List.of());
        result.put("tasksWithoutObjectiveNote", "依赖 M03 任务数据，本期未接入（待 M03 提供任务查询与 G-3 领域事件），当前返回空列表");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("unownedObjectiveCount", unowned.size());
        summary.put("objectivesWithoutKrCount", withoutKr.size());
        summary.put("tasksWithoutObjectiveCount", 0);
        result.put("summary", summary);
        result.put("generatedAt", LocalDateTime.now().format(TIME_FORMATTER));
        return result;
    }

    /**
     * 目标进度归集：取所属 KR 的平均进度；无 KR 时取下级目标平均进度；随后向上回溯刷新父目标。
     *
     * <p>供 KR 变更、目标任务关联变化后调用，保证 {@code okr_objective.progress} 可复算。</p>
     */
    public void refreshProgress(Long objectiveId) {
        Long current = objectiveId;
        int guard = 0;
        while (current != null && current > 0 && guard++ < TREE_GUARD) {
            Map<String, Object> row = objectiveMapper.selectObjectiveById(current);
            if (row == null) {
                return;
            }
            BigDecimal progress = keyResultMapper.selectProgressAvg(current);
            if (progress == null) {
                progress = objectiveMapper.selectChildProgressAvg(current);
            }
            if (progress == null) {
                progress = BigDecimal.ZERO;
            }
            objectiveMapper.updateObjectiveProgress(current, progress.setScale(2, RoundingMode.HALF_UP), currentUsername());
            current = numberAsLong(row.get("parentId"));
        }
    }

    /** 写操作前的目标归属校验（不受数据范围限制）。 */
    public Map<String, Object> requireObjective(Long id) {
        if (id == null) {
            throw new BusinessException("目标 ID 不能为空");
        }
        Map<String, Object> row = objectiveMapper.selectObjectiveById(id);
        if (row == null) {
            throw new BusinessException("目标不存在");
        }
        return row;
    }

    private Map<String, Object> requireParentObjective(Long parentId, Long periodId, Long selfId) {
        Map<String, Object> parent = objectiveMapper.selectObjectiveById(parentId);
        if (parent == null) {
            throw new BusinessException("上级目标不存在");
        }
        if (!periodId.equals(numberAsLong(parent.get("periodId")))) {
            throw new BusinessException("上级目标必须与当前目标属于同一周期");
        }
        if (selfId != null) {
            if (parentId.equals(selfId)) {
                throw new BusinessException("上级目标不能是目标自身");
            }
            Long cursor = parentId;
            int guard = 0;
            while (cursor != null && cursor > 0 && guard++ < TREE_GUARD) {
                if (cursor.equals(selfId)) {
                    throw new BusinessException("上级目标不能是目标的层级下级，目标层级不允许环形引用");
                }
                Map<String, Object> row = objectiveMapper.selectObjectiveById(cursor);
                if (row == null) {
                    break;
                }
                cursor = numberAsLong(row.get("parentId"));
            }
        }
        return parent;
    }

    private void validateDept(String level, Long deptId) {
        if ("dept".equals(level) && deptId == null) {
            throw new BusinessException("部门层级的目标必须填写归属部门");
        }
        if (deptId != null && platformMapper.countDept(deptId) == 0) {
            throw new BusinessException("归属部门不存在");
        }
    }

    private void validateOwner(String status, Long ownerId) {
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("承接人不存在或已停用");
        }
        if (!"draft".equals(status) && ownerId == null) {
            throw new BusinessException("目标无承接人时只能保存为草稿，不能置为「" + statusText(status) + "」");
        }
    }

    /**
     * R4 权重约束：同周期同父级下权重合计不得超过 100%；
     * 目标进入进行中/已完成前，合计必须恰好为 100%。
     */
    private void validateWeight(
            Long periodId, Long parentId, Long excludeId, BigDecimal weight, String status
    ) {
        if (weight.signum() < 0 || weight.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("目标权重必须在 0 到 100 之间");
        }
        BigDecimal siblingWeight = zeroIfNull(objectiveMapper.selectSiblingWeight(periodId, parentId, excludeId));
        BigDecimal total = siblingWeight.add(weight);
        String totalText = total.stripTrailingZeros().toPlainString();
        if (total.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("同一父级下子目标权重合计不能超过 100%（当前 " + totalText + "%）");
        }
        boolean mustBeExactlyFull = "active".equals(status) || "done".equals(status)
                || objectiveMapper.countActiveSiblings(periodId, parentId, excludeId) > 0;
        if (mustBeExactlyFull && total.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("同一父级下所有子目标权重之和必须为 100%（当前 " + totalText + "%）");
        }
    }
}
