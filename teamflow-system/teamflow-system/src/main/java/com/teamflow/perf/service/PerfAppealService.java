package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.perf.domain.dto.PerfAppealHandleRequest;
import com.teamflow.perf.domain.dto.PerfAppealWriteRequest;
import com.teamflow.perf.domain.vo.PerfIdResponse;
import com.teamflow.perf.mapper.PerfAppealMapper;
import com.teamflow.perf.mapper.PerfPlatformMapper;
import com.teamflow.perf.mapper.PerfResultMapper;
import com.teamflow.perf.status.PerfCycleStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M05 绩效申诉服务：R4/R5「锁定后任何修改必须走申诉流程」的唯一落地通道。
 *
 * <p>流程：被评人在周期锁定后提交申诉（记录申诉前总分）→ 管理者处理 →
 * 通过时可携带调整分，系统定向更新 {@code perf_result.total_score} 并标记已应用；驳回则结果不变。
 * 全过程留痕（申诉人/理由/处理人/意见/时间/调整前后分值）。</p>
 */
@Service
public class PerfAppealService extends PerfServiceSupport {

    private final PerfAppealMapper appealMapper;
    private final PerfResultMapper resultMapper;
    private final PerfCycleService cycleService;
    private final PerfPlatformMapper platformMapper;

    public PerfAppealService(
            PerfAppealMapper appealMapper,
            PerfResultMapper resultMapper,
            PerfCycleService cycleService,
            PerfPlatformMapper platformMapper
    ) {
        this.appealMapper = appealMapper;
        this.resultMapper = resultMapper;
        this.cycleService = cycleService;
        this.platformMapper = platformMapper;
    }

    /** 提交申诉：只能由被评人本人对已锁定周期的本人结果发起。 */
    @Transactional
    public PerfIdResponse create(PerfAppealWriteRequest request) {
        if (request == null || request.getResultId() == null) {
            throw new BusinessException("申诉必须指定绩效结果 resultId");
        }
        String reason = required(request.getReason(), "申诉理由不能为空");
        Map<String, Object> result = resultMapper.selectResultById(request.getResultId());
        if (result == null) {
            throw new BusinessException("绩效结果不存在或已删除");
        }
        Map<String, Object> cycle = cycleService.requireCycle(numberAsLong(result.get("cycleId")));
        if (!PerfCycleStatusMachine.isLocked(String.valueOf(cycle.get("status")))) {
            throw new BusinessException("只有已锁定的绩效周期才需要申诉；当前周期状态为「"
                    + PerfCycleStatusMachine.label(String.valueOf(cycle.get("status")))
                    + "」，未锁定前可直接联系评分人调整");
        }
        Long currentUserId = platformMapper.selectUserIdByUsername(currentUsername());
        if (currentUserId == null || !currentUserId.equals(numberAsLong(result.get("employeeId")))) {
            throw new BusinessException("只能对本人的绩效结果提起申诉");
        }
        if (appealMapper.countPendingByResult(numberAsLong(result.get("id"))) > 0) {
            throw new BusinessException("该绩效结果已有待处理的申诉，请等待处理完成");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cycleId", numberAsLong(result.get("cycleId")));
        row.put("resultId", numberAsLong(result.get("id")));
        row.put("employeeId", currentUserId);
        row.put("appealReason", reason);
        row.put("beforeScore", numberAsBigDecimal(result.get("totalScore")));
        appealMapper.insertAppeal(row, currentUsername());
        return new PerfIdResponse(numberAsLong(row.get("id")));
    }

    @DataScope
    public PageResult<Map<String, Object>> list(Long cycleId, String status, PageQuery query) {
        Page page = pageOf(query);
        long total = appealMapper.countAppeals(cycleId, trimToNull(status), DataScopeContext.current());
        List<Map<String, Object>> rows = appealMapper.selectAppeals(cycleId, trimToNull(status),
                DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        return decorate(requireAppeal(id));
    }

    /** 申诉处理：通过可携带调整分（锁定后改分的唯一通道），驳回不改结果；两种处理都留痕。 */
    @Transactional
    public Map<String, Object> handle(Long id, PerfAppealHandleRequest request) {
        Map<String, Object> appeal = requireAppeal(id);
        if (!"pending".equals(String.valueOf(appeal.get("appealStatus")))) {
            throw new BusinessException("该申诉已处理，不能重复处理");
        }
        boolean approved = Boolean.TRUE.equals(request == null ? null : request.getApproved());
        String comment = request == null ? null : trimToNull(request.getComment());
        BigDecimal afterScore = request == null ? null : request.getAfterScore();
        if (afterScore != null && (afterScore.signum() < 0 || afterScore.compareTo(HUNDRED) > 0)) {
            throw new BusinessException("调整分必须在 0 到 100 之间");
        }
        if (approved && comment == null) {
            throw new BusinessException("通过申诉必须填写处理意见");
        }
        if (afterScore != null && !approved) {
            throw new BusinessException("只有通过申诉才能调整分值");
        }
        Long handlerId = platformMapper.selectUserIdByUsername(currentUsername());
        int applied = 0;
        if (approved && afterScore != null) {
            resultMapper.updateResultTotal(numberAsLong(appeal.get("resultId")), percent(afterScore), currentUsername());
            applied = 1;
        }
        appealMapper.updateHandle(id, approved ? "approved" : "rejected", separate(afterScore),
                handlerId, currentUsername(), comment, LocalDateTime.now(), applied, currentUsername());
        return decorate(requireAppeal(id));
    }

    public Map<String, Object> requireAppeal(Long id) {
        if (id == null) {
            throw new BusinessException("申诉 ID 不能为空");
        }
        Map<String, Object> row = appealMapper.selectAppealById(id);
        if (row == null) {
            throw new BusinessException("申诉记录不存在或已删除");
        }
        return row;
    }

    private BigDecimal separate(BigDecimal value) {
        return value == null ? null : percent(value);
    }

    private Map<String, Object> decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("appealStatus"));
        row.put("appealStatusLabel", switch (status) {
            case "pending" -> "待处理";
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            default -> status;
        });
        row.put("applied", intOf(row.get("applied")) == 1);
        return row;
    }
}
