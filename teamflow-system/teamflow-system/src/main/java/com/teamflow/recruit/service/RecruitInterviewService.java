package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.recruit.domain.dto.RecruitFeedbackDimension;
import com.teamflow.recruit.domain.dto.RecruitFeedbackWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitInterviewUpdateRequest;
import com.teamflow.recruit.domain.dto.RecruitInterviewWriteRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.mapper.RecruitCandidateMapper;
import com.teamflow.recruit.mapper.RecruitInterviewFeedbackMapper;
import com.teamflow.recruit.mapper.RecruitInterviewMapper;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.recruit.stage.RecruitStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M08 面试服务（2.1.3）：轮次编排、面试官分配、结构化评价（R4：每维度独立评分 + 评语）。
 *
 * <p>日程能力归 M06（M3 排期），本期只保存安排时间与地点；面试结论由人操作并留痕（R3：筛查/评分只建议不决策）。</p>
 */
@Service
public class RecruitInterviewService extends RecruitServiceSupport {

    private static final List<String> RESULTS = List.of("pending", "pass", "fail");

    private final RecruitInterviewMapper interviewMapper;
    private final RecruitInterviewFeedbackMapper feedbackMapper;
    private final RecruitCandidateMapper candidateMapper;

    public RecruitInterviewService(
            RecruitPlatformMapper platformMapper,
            RecruitInterviewMapper interviewMapper,
            RecruitInterviewFeedbackMapper feedbackMapper,
            RecruitCandidateMapper candidateMapper
    ) {
        super(platformMapper);
        this.interviewMapper = interviewMapper;
        this.feedbackMapper = feedbackMapper;
        this.candidateMapper = candidateMapper;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(
            PageQuery query, Long candidateId, Long jobId, String status, Long interviewerId) {
        Page page = pageOf(query);
        long total = interviewMapper.countInterviews(candidateId, jobId, trimToNull(status), interviewerId,
                DataScopeContext.current());
        List<Map<String, Object>> rows = interviewMapper.selectInterviews(candidateId, jobId, trimToNull(status),
                interviewerId, DataScopeContext.current(), page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = interviewMapper.selectInterview(id, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("面试不存在或无权访问");
        }
        decorate(row);
        row.put("feedbacks", feedbackMapper.selectByInterview(id));
        return row;
    }

    /** 安排面试：候选人须处于「面试」阶段；轮次按候选人递增；面试官须为启用用户。 */
    @Transactional
    public RecruitIdResponse schedule(RecruitInterviewWriteRequest request) {
        Map<String, Object> candidate = requireCandidate(request.getCandidateId());
        String stage = String.valueOf(candidate.get("currentStage"));
        if (!RecruitStatusMachine.CANDIDATE_INTERVIEW.equals(stage)) {
            throw new BusinessException("候选人当前阶段为「" + RecruitStatusMachine.candidateStageLabel(stage)
                    + "」，请先将阶段推进到「面试」再安排面试");
        }
        Long interviewerId = request.getInterviewerId();
        if (interviewerId == null || platformMapper.countActiveUser(interviewerId) == 0) {
            throw new BusinessException("面试官不存在或已停用");
        }
        LocalDateTime scheduledTime = parseDateTime(request.getScheduledTime(), "面试时间");
        int roundNo = interviewMapper.selectMaxRound(numberAsLong(candidate.get("id"))) + 1;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidateId", numberAsLong(candidate.get("id")));
        row.put("jobId", numberAsLong(candidate.get("jobId")));
        row.put("roundNo", roundNo);
        row.put("roundName", trimToNull(request.getRoundName()) == null
                ? "第 " + roundNo + " 轮面试" : request.getRoundName());
        row.put("interviewerId", interviewerId);
        row.put("scheduledTime", scheduledTime);
        row.put("durationMinutes", request.getDurationMinutes() == null ? 60 : request.getDurationMinutes());
        row.put("location", trimToNull(request.getLocation()));
        row.put("status", RecruitStatusMachine.INTERVIEW_SCHEDULED);
        row.put("remark", trimToNull(request.getRemark()));
        interviewMapper.insertInterview(row, currentUsername());
        return new RecruitIdResponse(numberAsLong(row.get("id")));
    }

    /** 修改/完成/取消面试；结论 pass/fail 只能在面试完成状态下给出。 */
    @Transactional
    public Map<String, Object> update(Long id, RecruitInterviewUpdateRequest request) {
        Map<String, Object> existing = requireInterview(id);
        String currentStatus = String.valueOf(existing.get("status"));
        String targetStatus = request.getStatus() == null ? currentStatus : request.getStatus();
        if (!targetStatus.equals(currentStatus)) {
            requireTransition(() -> RecruitStatusMachine.requireInterviewTransition(currentStatus, targetStatus));
        }
        String result = request.getResult() == null ? String.valueOf(existing.get("result")) : request.getResult();
        if (!RESULTS.contains(result)) {
            throw new BusinessException("面试结论只能为 pending / pass / fail");
        }
        if (!"pending".equals(result) && !RecruitStatusMachine.INTERVIEW_COMPLETED.equals(targetStatus)) {
            throw new BusinessException("只有已完成的面试才能给出通过/不通过结论（结果由人操作并留痕，R3）");
        }
        Long interviewerId = request.getInterviewerId() == null
                ? numberAsLong(existing.get("interviewerId")) : request.getInterviewerId();
        if (request.getInterviewerId() != null && platformMapper.countActiveUser(interviewerId) == 0) {
            throw new BusinessException("面试官不存在或已停用");
        }
        LocalDateTime scheduledTime = request.getScheduledTime() == null
                ? parseDateTimeValue(existing.get("scheduledTime"))
                : parseDateTime(request.getScheduledTime(), "面试时间");
        if (scheduledTime == null) {
            throw new BusinessException("面试时间不能为空");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roundName", request.getRoundName() == null ? existing.get("roundName") : request.getRoundName());
        row.put("interviewerId", interviewerId);
        row.put("scheduledTime", scheduledTime);
        row.put("durationMinutes", request.getDurationMinutes() == null
                ? numberAsLong(existing.get("durationMinutes")).intValue() : request.getDurationMinutes());
        row.put("location", request.getLocation() == null ? existing.get("location") : trimToNull(request.getLocation()));
        row.put("status", targetStatus);
        row.put("result", result);
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        interviewMapper.updateInterview(id, row, currentUsername());
        return viewOf(id);
    }

    /** 提交结构化评价（R4）：多维度一次提交，每维度必须评分 + 评语；同维度重复提交覆盖。 */
    @Transactional
    public Map<String, Object> submitFeedback(Long id, RecruitFeedbackWriteRequest request) {
        Map<String, Object> existing = requireInterview(id);
        if (!RecruitStatusMachine.INTERVIEW_COMPLETED.equals(String.valueOf(existing.get("status")))) {
            throw new BusinessException("面试尚未完成，不能提交评价（请先把面试状态改为已完成）");
        }
        List<RecruitFeedbackDimension> dimensions = request == null ? null : request.getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            throw new BusinessException("结构化评价至少需要一个维度（R4：禁止只填一个总分）");
        }
        Long operatorId = currentUserId();
        feedbackMapper.softDeleteByInterview(id, currentUsername());
        List<String> codes = new ArrayList<>();
        for (RecruitFeedbackDimension dimension : dimensions) {
            String code = required(dimension.getDimensionCode(), "评价维度编码不能为空");
            if (codes.contains(code)) {
                throw new BusinessException("评价维度重复：" + code);
            }
            codes.add(code);
            String name = required(dimension.getDimensionName(), "评价维度名称不能为空");
            BigDecimal score = dimension.getScore();
            if (score == null || score.signum() < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException("维度「" + name + "」评分必须在 0-100 之间");
            }
            String comment = required(dimension.getComment(), "维度「" + name + "」必须填写评语（R4）");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("interviewId", id);
            row.put("dimensionCode", code);
            row.put("dimensionName", name);
            row.put("score", scale2(score));
            row.put("comment", comment);
            row.put("interviewerId", operatorId == null
                    ? numberAsLong(existing.get("interviewerId")) : operatorId);
            row.put("submitTime", LocalDateTime.now());
            feedbackMapper.insertFeedback(row, currentUsername());
        }
        return viewOf(id);
    }

    /** 写操作返回视图：内部直接读（避免 @DataScope 自调用丢失数据范围上下文）。 */
    private Map<String, Object> viewOf(Long id) {
        Map<String, Object> row = requireInterview(id);
        decorate(row);
        row.put("feedbacks", feedbackMapper.selectByInterview(id));
        return row;
    }
    public Map<String, Object> requireInterview(Long id) {
        if (id == null) {
            throw new BusinessException("面试 ID 不能为空");
        }
        Map<String, Object> row = interviewMapper.selectInterviewById(id);
        if (row == null) {
            throw new BusinessException("面试不存在或已删除");
        }
        return row;
    }

    private Map<String, Object> requireCandidate(Long candidateId) {
        if (candidateId == null) {
            throw new BusinessException("候选人 ID 不能为空");
        }
        Map<String, Object> candidate = candidateMapper.selectCandidateById(candidateId);
        if (candidate == null) {
            throw new BusinessException("候选人不存在或已删除");
        }
        return candidate;
    }

    private void decorate(Map<String, Object> row) {
        row.put("statusLabel", RecruitStatusMachine.interviewStatusLabel(String.valueOf(row.get("status"))));
        row.put("resultLabel", switch (String.valueOf(row.get("result"))) {
            case "pass" -> "通过";
            case "fail" -> "不通过";
            default -> "待定";
        });
    }
}
