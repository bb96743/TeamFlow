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
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** D-06.3：人工校准相对自动值的双向限幅（个百分点）。 */
    private static final BigDecimal CALIBRATION_LIMIT = new BigDecimal("20");
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
            // D-06.3：校准只允许在「自动值 ±20 个百分点」范围内；超限须领导者审批后走 M05 申诉流程
            BigDecimal autoProgress = computeQuantitative(currentValue,
                    numberAsBigDecimal(existing.get("targetValue")), String.valueOf(existing.get("direction")));
            BigDecimal deviation = progress.subtract(autoProgress).abs();
            if (deviation.compareTo(CALIBRATION_LIMIT) > 0) {
                throw new BusinessException("人工校准超出 ±20 个百分点限额（D-06.3）：自动值为 "
                        + autoProgress.toPlainString() + "%，本次校准为 " + progress.toPlainString()
                        + "%，偏离 " + deviation.toPlainString() + " 个百分点；允许范围 "
                        + autoProgress.subtract(CALIBRATION_LIMIT).max(BigDecimal.ZERO).toPlainString() + "%~"
                        + autoProgress.add(CALIBRATION_LIMIT).min(HUNDRED).toPlainString() + "%");
            }
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
     * M05 绩效归集的**只读取数出口**（跨模块只走 Service，禁止 M05 直连 {@code okr_*}）。
     *
     * <p><b>取数口径</b>：某员工承接（{@code owner_id}）的、目标所属 {@code okr_period} 与绩效周期
     * {@code [startDate, endDate]} 区间重叠的 KR；同时带出最近一次人工校准的量与原因，
     * 供 M05 下钻展示（L0 §9.1 {@code D-06.3}）。本方法不写库、不套数据范围（归集是管理端动作，
     * 调用方负责权限）。</p>
     *
     * @param ownerId   KR 负责人（被评人）用户 ID
     * @param startDate 绩效周期开始日期
     * @param endDate   绩效周期结束日期
     */
    public List<Map<String, Object>> perfFacts(Long ownerId, LocalDate startDate, LocalDate endDate) {
        if (ownerId == null) {
            return List.of();
        }
        if (startDate == null || endDate == null) {
            throw new BusinessException("归集取数必须提供绩效周期起止日期");
        }
        return keyResultMapper.selectPerfKrs(ownerId, startDate.toString(), endDate.toString());
    }

    /**
     * 批量取某批负责人承接的 KR 归集事实（M13 洞察聚合用，避免逐成员 N+1）。
     *
     * <p>筛选条件、排序与单条版本 {@link #perfFacts(Long, LocalDate, LocalDate)} 完全一致：
     * 同一日期重叠口径、顶层任务无关、校准字段相同；额外带出 {@code ownerId} 供调用方按用户分组。
     * 空集合早返回，不产生 SQL。</p>
     *
     * @param ownerIds  KR 负责人用户 ID 集合（可含 null/重复，内部去重）
     * @param startDate 绩效周期开始日期
     * @param endDate   绩效周期结束日期
     */
    public List<Map<String, Object>> perfFactsBatch(Collection<Long> ownerIds,
                                                    LocalDate startDate, LocalDate endDate) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return List.of();
        }
        if (startDate == null || endDate == null) {
            throw new BusinessException("归集取数必须提供绩效周期起止日期");
        }
        List<Long> userIds = ownerIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return keyResultMapper.selectPerfKrsBatch(userIds, startDate.toString(), endDate.toString());
    }

    /**
     * 任务驱动型 KR 的事件回写入口（M02 RULE R3）。
     *
     * <p><b>G-3 起由 {@code OkrTaskEventSubscriber} 在 M03 事件消费时调用</b>：订阅端按派生投影
     * {@code okr_kr_task_link} 统计"顶层任务总数 / 已完成顶层数"后传入。保留本三参重载供内部与测试直接调用，
     * 操作人取当前登录用户。</p>
     */
    @Transactional
    public void applyTaskWriteback(Long krId, int doneTaskCount, int taskCount) {
        applyTaskWriteback(krId, doneTaskCount, taskCount, currentUsername(), "任务完成度回写（M03 事件）");
    }

    /**
     * G-3 事件回写：操作人与原因由事件消费端传入。
     *
     * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 回调不保证仍持有登录上下文，因此
     * <b>不能依赖 {@code currentUsername()}</b>；操作人由事件里的 {@code operatorId} 还原
     * （取不到时用 {@code m03:event}）。进度按 D-2026-10-25-03 计算，并写 {@code okr_progress_log}。</p>
     */
    @Transactional
    public void applyTaskWriteback(Long krId, int doneTaskCount, int taskCount, String operator, String reason) {
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
        String effectiveOperator = operator == null || operator.isBlank() ? "system" : operator;
        Long objectiveId = numberAsLong(existing.get("objectiveId"));
        Map<String, Object> objective = objectiveService.requireObjective(objectiveId);
        periodService.requireEditable(numberAsLong(objective.get("periodId")));
        BigDecimal progress = computeTaskDriven(doneTaskCount, taskCount);
        String taskLinkStatus = taskCount > 0 ? "linked" : "unlinked";
        keyResultMapper.updateTaskWriteback(krId, taskCount, doneTaskCount, taskLinkStatus, progress, effectiveOperator);
        writeProgressLog(objectiveId, krId, numberAsBigDecimal(existing.get("progress")), progress,
                "task_writeback", reason, effectiveOperator);
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
        writeProgressLog(objectiveId, krId, from, to, progressSource, reason, currentUsername());
    }

    /** G-3：事件回写不走 SecurityContext，操作人显式传入（见 {@link #applyTaskWriteback(Long, int, int, String, String)}）。 */
    private void writeProgressLog(
            Long objectiveId, Long krId, BigDecimal from, BigDecimal to, String progressSource, String reason,
            String operator
    ) {
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
