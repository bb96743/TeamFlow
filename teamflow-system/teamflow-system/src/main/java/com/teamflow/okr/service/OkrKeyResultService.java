package com.teamflow.okr.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.okr.domain.dto.OkrKeyResultProgressRequest;
import com.teamflow.okr.domain.dto.OkrKeyResultWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.mapper.OkrKeyResultMapper;
import com.teamflow.okr.mapper.OkrObjectiveMapper;
import com.teamflow.okr.mapper.OkrPlatformMapper;
import com.teamflow.okr.mapper.OkrProgressLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M02 关键结果服务：KR 增删改查、进度计算与进度留痕。
 *
 * <p>进度口径严格按 L0 §9.1 {@code D-2026-10-25-03}：
 * <ul>
 *   <li>量化型正向：{@code min(1, current/target)}；反向：{@code min(1, target/current)}；</li>
 *   <li>任务驱动型：{@code 已完成顶层任务数 / 关联顶层任务总数}，无关联任务时进度 0 且标「待关联」；</li>
 *   <li>结果均落在 0–100 并保留 2 位小数；任何进度变更都写 {@code okr_progress_log} 并记录
 *       {@code progress_source}（quantitative / task_writeback / manual）。</li>
 * </ul>
 * 任务完成事件的订阅属于 G-3，本期通过 {@link #applyTaskWriteback(Long, int, int)} 预留计算入口，不开放 HTTP。</p>
 */
@Service
public class OkrKeyResultService extends OkrServiceSupport {

    private static final Set<String> KR_TYPES = Set.of("quantitative", "task_driven");
    private static final Set<String> DIRECTIONS = Set.of("positive", "negative");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int LOG_LIMIT = 100;

    private final OkrKeyResultMapper keyResultMapper;
    private final OkrProgressLogMapper progressLogMapper;
    private final OkrPlatformMapper platformMapper;
    private final OkrObjectiveService objectiveService;
    private final OkrPeriodService periodService;

    public OkrKeyResultService(
            OkrKeyResultMapper keyResultMapper,
            OkrProgressLogMapper progressLogMapper,
            OkrPlatformMapper platformMapper,
            OkrObjectiveService objectiveService,
            OkrPeriodService periodService
    ) {
        this.keyResultMapper = keyResultMapper;
        this.progressLogMapper = progressLogMapper;
        this.platformMapper = platformMapper;
        this.objectiveService = objectiveService;
        this.periodService = periodService;
    }

    @DataScope
    public List<Map<String, Object>> list(Long objectiveId) {
        requireVisibleObjective(objectiveId);
        return keyResultMapper.selectKeyResults(objectiveId, DataScopeContext.current());
    }

    @DataScope
    public Map<String, Object> detail(Long objectiveId, Long krId) {
        Map<String, Object> row = keyResultMapper.selectKeyResult(objectiveId, krId, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("关键结果不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public OkrIdResponse create(Long objectiveId, OkrKeyResultWriteRequest request) {
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        Long periodId = numberAsLong(objective.get("periodId"));
        periodService.requireEditable(periodId);

        String title = required(request.getKrTitle(), "关键结果标题不能为空");
        String type = defaultString(request.getKrType(), "quantitative");
        if (!KR_TYPES.contains(type)) {
            throw new BusinessException("关键结果类型只能为 quantitative 或 task_driven");
        }
        String direction = defaultString(request.getDirection(), "positive");
        if (!DIRECTIONS.contains(direction)) {
            throw new BusinessException("指标方向只能为 positive 或 negative");
        }
        Long ownerId = request.getOwnerId();
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("KR 负责人不存在或已停用");
        }

        BigDecimal targetValue = request.getTargetValue();
        BigDecimal currentValue = request.getCurrentValue() == null ? BigDecimal.ZERO : request.getCurrentValue();
        if (currentValue.signum() < 0) {
            throw new BusinessException("当前值不能为负数");
        }
        BigDecimal progress;
        String progressSource;
        String taskLinkStatus;
        if ("quantitative".equals(type)) {
            if (targetValue == null || targetValue.signum() <= 0) {
                throw new BusinessException("量化型关键结果必须填写大于 0 的目标值");
            }
            progress = computeQuantitative(currentValue, targetValue, direction);
            progressSource = "quantitative";
            taskLinkStatus = "unlinked";
        } else {
            targetValue = null;
            progress = BigDecimal.ZERO;
            progressSource = "task_writeback";
            taskLinkStatus = "unlinked";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("objectiveId", objectiveId);
        row.put("krTitle", title);
        row.put("krType", type);
        row.put("metricUnit", trimToNull(request.getMetricUnit()));
        row.put("targetValue", targetValue);
        row.put("currentValue", currentValue);
        row.put("direction", direction);
        row.put("progress", progress);
        row.put("progressSource", progressSource);
        row.put("taskLinkStatus", taskLinkStatus);
        row.put("taskCount", 0);
        row.put("doneTaskCount", 0);
        row.put("ownerId", ownerId);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        keyResultMapper.insertKeyResult(row, currentUsername());
        Long krId = numberAsLong(row.get("id"));
        writeProgressLog(objectiveId, krId, null, progress, progressSource, "创建关键结果初始化进度");
        objectiveService.refreshProgress(objectiveId);
        return new OkrIdResponse(krId);
    }

    @Transactional
    public void update(Long objectiveId, Long krId, OkrKeyResultWriteRequest request) {
        Map<String, Object> existing = requireKeyResult(objectiveId, krId);
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        periodService.requireEditable(numberAsLong(objective.get("periodId")));

        String title = request.getKrTitle() == null
                ? String.valueOf(existing.get("krTitle"))
                : required(request.getKrTitle(), "关键结果标题不能为空");
        String type = request.getKrType() == null ? String.valueOf(existing.get("krType"))
                : defaultString(request.getKrType(), "quantitative");
        if (!KR_TYPES.contains(type)) {
            throw new BusinessException("关键结果类型只能为 quantitative 或 task_driven");
        }
        String direction = request.getDirection() == null ? String.valueOf(existing.get("direction"))
                : defaultString(request.getDirection(), "positive");
        if (!DIRECTIONS.contains(direction)) {
            throw new BusinessException("指标方向只能为 positive 或 negative");
        }
        Long ownerId = request.getOwnerId() == null ? numberAsLong(existing.get("ownerId")) : request.getOwnerId();
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("KR 负责人不存在或已停用");
        }
        BigDecimal currentValue = request.getCurrentValue() == null
                ? zeroIfNull(numberAsBigDecimal(existing.get("currentValue")))
                : request.getCurrentValue();
        if (currentValue.signum() < 0) {
            throw new BusinessException("当前值不能为负数");
        }
        BigDecimal targetValue = request.getTargetValue() == null
                ? numberAsBigDecimal(existing.get("targetValue")) : request.getTargetValue();

        BigDecimal progress;
        String progressSource;
        String taskLinkStatus;
        if ("quantitative".equals(type)) {
            if (targetValue == null || targetValue.signum() <= 0) {
                throw new BusinessException("量化型关键结果必须填写大于 0 的目标值");
            }
            progress = computeQuantitative(currentValue, targetValue, direction);
            progressSource = "quantitative";
            taskLinkStatus = "unlinked";
        } else {
            targetValue = null;
            int taskCount = intOf(existing.get("taskCount"));
            int doneTaskCount = intOf(existing.get("doneTaskCount"));
            progress = computeTaskDriven(doneTaskCount, taskCount);
            progressSource = "task_writeback";
            taskLinkStatus = taskCount > 0 ? "linked" : "unlinked";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("krTitle", title);
        row.put("krType", type);
        row.put("metricUnit", request.getMetricUnit() == null ? existing.get("metricUnit") : trimToNull(request.getMetricUnit()));
        row.put("targetValue", targetValue);
        row.put("currentValue", currentValue);
        row.put("direction", direction);
        row.put("progress", progress);
        row.put("progressSource", progressSource);
        row.put("taskLinkStatus", taskLinkStatus);
        row.put("ownerId", ownerId);
        row.put("sort", request.getSort() == null
                ? (existing.get("sort") == null ? 0 : numberAsLong(existing.get("sort")).intValue())
                : request.getSort());
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        keyResultMapper.updateKeyResult(krId, row, currentUsername());

        BigDecimal from = numberAsBigDecimal(existing.get("progress"));
        if (from == null || from.compareTo(progress) != 0) {
            writeProgressLog(objectiveId, krId, from, progress, progressSource, "修改关键结果后重算进度");
        }
        objectiveService.refreshProgress(objectiveId);
    }

    @Transactional
    public void delete(Long objectiveId, Long krId) {
        Map<String, Object> existing = requireKeyResult(objectiveId, krId);
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        periodService.requireEditable(numberAsLong(objective.get("periodId")));
        keyResultMapper.softDeleteKeyResult(krId, currentUsername());
        BigDecimal from = numberAsBigDecimal(existing.get("progress"));
        writeProgressLog(objectiveId, krId, from, BigDecimal.ZERO, "manual", "删除关键结果，进度归零");
        objectiveService.refreshProgress(objectiveId);
    }

    /**
     * 更新 KR 进度（R1）：量化型按公式重算或人工校准留痕；任务驱动型拒绝人工填写。
     */
    @Transactional
    public Map<String, Object> updateProgress(Long objectiveId, Long krId, OkrKeyResultProgressRequest request) {
        Map<String, Object> existing = requireKeyResult(objectiveId, krId);
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        periodService.requireEditable(numberAsLong(objective.get("periodId")));

        String type = String.valueOf(existing.get("krType"));
        if ("task_driven".equals(type)) {
            throw new BusinessException("任务驱动型 KR 的进度由「已完成顶层任务数 / 关联顶层任务总数」计算，不支持人工填写");
        }
        BigDecimal from = numberAsBigDecimal(existing.get("progress"));
        BigDecimal currentValue = numberAsBigDecimal(existing.get("currentValue"));
        BigDecimal progress;
        String progressSource;
        String reason;
        if (request.getCurrentValue() != null && request.getProgress() != null) {
            throw new BusinessException("currentValue 与 progress 只能提供一个：前者按公式重算，后者为人工校准");
        }
        if (request.getCurrentValue() != null) {
            currentValue = request.getCurrentValue();
            if (currentValue.signum() < 0) {
                throw new BusinessException("当前值不能为负数");
            }
            BigDecimal targetValue = numberAsBigDecimal(existing.get("targetValue"));
            String direction = String.valueOf(existing.get("direction"));
            progress = computeQuantitative(currentValue, targetValue, direction);
            progressSource = "quantitative";
            reason = defaultString(request.getReason(), "更新当前值后按公式重算进度");
        } else if (request.getProgress() != null) {
            progress = request.getProgress();
            if (progress.signum() < 0 || progress.compareTo(HUNDRED) > 0) {
                throw new BusinessException("进度必须在 0 到 100 之间");
            }
            progress = scale2(progress);
            progressSource = "manual";
            reason = required(request.getReason(), "人工调整进度必须填写原因");
        } else {
            throw new BusinessException("请提供 currentValue（按公式重算）或 progress（人工校准）");
        }
        keyResultMapper.updateKeyResultProgress(krId, currentValue, progress, progressSource, currentUsername());
        writeProgressLog(objectiveId, krId, from, progress, progressSource, reason);
        objectiveService.refreshProgress(objectiveId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", krId);
        result.put("fromProgress", from);
        result.put("progress", progress);
        result.put("progressSource", progressSource);
        return result;
    }

    @DataScope
    public List<Map<String, Object>> progressLogs(Long objectiveId, Long krId) {
        Map<String, Object> row = keyResultMapper.selectKeyResult(objectiveId, krId, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("关键结果不存在或无权访问");
        }
        return progressLogMapper.selectProgressLogs(krId, LOG_LIMIT);
    }

    /**
     * 任务驱动型 KR 的事件回写入口（M02 RULE R3 幂等由事件侧 {@code (taskId, krId)} 去重保证）。
     *
     * <p><b>本期不开放 HTTP</b>：G-3 领域事件能力未完成，待 M03 发布任务完成事件后由监听器调用。</p>
     */
    @Transactional
    public void applyTaskWriteback(Long krId, int doneTaskCount, int taskCount) {
        Map<String, Object> existing = keyResultMapper.selectKeyResultById(krId);
        if (existing == null) {
            throw new BusinessException("关键结果不存在");
        }
        if (!"task_driven".equals(String.valueOf(existing.get("krType")))) {
            throw new BusinessException("仅任务驱动型关键结果支持任务完成度回写");
        }
        if (taskCount < 0 || doneTaskCount < 0 || doneTaskCount > taskCount) {
            throw new BusinessException("已完成任务数必须在 0 到关联任务总数之间");
        }
        Long objectiveId = numberAsLong(existing.get("objectiveId"));
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        periodService.requireEditable(numberAsLong(objective.get("periodId")));
        BigDecimal progress = computeTaskDriven(doneTaskCount, taskCount);
        String taskLinkStatus = taskCount > 0 ? "linked" : "unlinked";
        keyResultMapper.updateTaskWriteback(krId, taskCount, doneTaskCount, taskLinkStatus, progress, currentUsername());
        writeProgressLog(objectiveId, krId, numberAsBigDecimal(existing.get("progress")), progress,
                "task_writeback", "任务完成度回写（M03 事件，待接入）");
        objectiveService.refreshProgress(objectiveId);
    }

    private void requireVisibleObjective(Long objectiveId) {
        if (objectiveService.visibleObjective(objectiveId) == null) {
            throw new BusinessException("目标不存在或无权访问");
        }
    }

    private Map<String, Object> requireKeyResult(Long objectiveId, Long krId) {
        Map<String, Object> row = keyResultMapper.selectKeyResultById(krId);
        if (row == null || !objectiveId.equals(numberAsLong(row.get("objectiveId")))) {
            throw new BusinessException("关键结果不存在或不属于该目标");
        }
        return row;
    }

    /**
     * 量化型进度口径（D-2026-10-25-03）：正向 {@code min(1, current/target)}，
     * 反向 {@code min(1, target/current)}（当前值为 0 视为最好，进度 100%）。
     */
    BigDecimal computeQuantitative(BigDecimal current, BigDecimal target, String direction) {
        if (target == null || target.signum() <= 0) {
            throw new BusinessException("量化型关键结果必须填写大于 0 的目标值");
        }
        BigDecimal currentValue = current == null ? BigDecimal.ZERO : current;
        if (currentValue.signum() < 0) {
            currentValue = BigDecimal.ZERO;
        }
        BigDecimal ratio;
        if ("negative".equals(direction)) {
            ratio = currentValue.signum() == 0
                    ? BigDecimal.ONE
                    : target.divide(currentValue, 8, RoundingMode.HALF_UP);
        } else {
            ratio = currentValue.divide(target, 8, RoundingMode.HALF_UP);
        }
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        if (ratio.signum() < 0) {
            ratio = BigDecimal.ZERO;
        }
        return ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /** 任务驱动型进度：已完成顶层任务数 / 关联顶层任务总数；无关联任务时 0。 */
    BigDecimal computeTaskDriven(int doneTaskCount, int taskCount) {
        if (taskCount <= 0) {
            return BigDecimal.ZERO;
        }
        int done = Math.min(Math.max(doneTaskCount, 0), taskCount);
        return BigDecimal.valueOf(done)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(taskCount), 2, RoundingMode.HALF_UP);
    }

    private void writeProgressLog(
            Long objectiveId, Long krId, BigDecimal from, BigDecimal to, String progressSource, String reason
    ) {
        String operator = currentUsername();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("krId", krId);
        row.put("objectiveId", objectiveId);
        row.put("fromProgress", from);
        row.put("toProgress", to);
        row.put("progressSource", progressSource);
        row.put("reason", reason);
        row.put("operatorId", platformMapper.selectUserIdByUsername(operator));
        row.put("operatorName", operator);
        progressLogMapper.insertProgressLog(row, operator);
    }

    private int intOf(Object value) {
        return value == null ? 0 : numberAsLong(value).intValue();
    }
}
