package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.recruit.domain.dto.RecruitJobWriteRequest;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.mapper.RecruitCandidateMapper;
import com.teamflow.recruit.mapper.RecruitJobMapper;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.recruit.stage.RecruitStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M08 职位服务（2.1.1）：JD 维护、招聘负责人、需求审批流（复用 M03 流程实例）、职位状态机。
 *
 * <p><b>边界</b>：不建流程表（审批走 {@link RecruitFlowService} 复用 M03 {@code flow_*}）；
 * 不建部门/用户表（只读 {@code sys_dept}/{@code sys_user}）。</p>
 */
@Service
public class RecruitJobService extends RecruitServiceSupport {

    private final RecruitJobMapper jobMapper;
    private final RecruitCandidateMapper candidateMapper;
    private final RecruitFlowService flowService;

    public RecruitJobService(
            RecruitPlatformMapper platformMapper,
            RecruitJobMapper jobMapper,
            RecruitCandidateMapper candidateMapper,
            RecruitFlowService flowService
    ) {
        super(platformMapper);
        this.jobMapper = jobMapper;
        this.candidateMapper = candidateMapper;
        this.flowService = flowService;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String status, Long deptId) {
        Page page = pageOf(query);
        long total = jobMapper.countJobs(trimToNull(keyword), trimToNull(status), deptId, DataScopeContext.current());
        List<Map<String, Object>> rows = jobMapper.selectJobs(
                trimToNull(keyword), trimToNull(status), deptId, DataScopeContext.current(),
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = jobMapper.selectJob(id, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("职位不存在或无权访问");
        }
        decorate(row);
        Long instanceId = numberAsLong(row.get("approvalInstanceId"));
        row.put("approvalHistory", instanceId == null ? null : flowService.history(instanceId));
        return row;
    }

    @Transactional
    public RecruitIdResponse create(RecruitJobWriteRequest request) {
        String code = required(request.getJobCode(), "职位编码不能为空");
        String title = required(request.getJobTitle(), "职位名称不能为空");
        if (jobMapper.countJobCode(code, null) > 0) {
            throw new BusinessException("职位编码已存在：" + code);
        }
        Long ownerId = request.getOwnerId() == null ? currentUserId() : request.getOwnerId();
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("招聘负责人不存在或已停用");
        }
        if (request.getDeptId() != null && platformMapper.countDept(request.getDeptId()) == 0) {
            throw new BusinessException("归属部门不存在");
        }
        int headcount = request.getHeadcount() == null ? 1 : request.getHeadcount();
        if (headcount <= 0) {
            throw new BusinessException("需求人数必须大于 0");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobCode", code);
        row.put("jobTitle", title);
        row.put("jobDesc", trimToNull(request.getJobDesc()));
        row.put("deptId", request.getDeptId());
        row.put("headcount", headcount);
        row.put("jobLevel", trimToNull(request.getJobLevel()));
        row.put("salaryRange", trimToNull(request.getSalaryRange()));
        row.put("city", trimToNull(request.getCity()));
        row.put("ownerId", ownerId);
        row.put("status", RecruitStatusMachine.JOB_DRAFT);
        row.put("approvalState", RecruitStatusMachine.APPROVAL_NONE);
        row.put("remark", trimToNull(request.getRemark()));
        jobMapper.insertJob(row, currentUsername());
        return new RecruitIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, RecruitJobWriteRequest request) {
        Map<String, Object> existing = requireJob(id);
        String status = String.valueOf(existing.get("status"));
        if (RecruitStatusMachine.JOB_PENDING_APPROVAL.equals(status)) {
            throw new BusinessException("职位需求审批中，不能修改；请等待审批结果");
        }
        if (RecruitStatusMachine.JOB_CLOSED.equals(status)) {
            throw new BusinessException("职位已关闭，不能修改");
        }
        Long ownerId = request.getOwnerId() == null ? numberAsLong(existing.get("ownerId")) : request.getOwnerId();
        if (ownerId != null && platformMapper.countActiveUser(ownerId) == 0) {
            throw new BusinessException("招聘负责人不存在或已停用");
        }
        if (request.getDeptId() != null && platformMapper.countDept(request.getDeptId()) == 0) {
            throw new BusinessException("归属部门不存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobTitle", request.getJobTitle() == null
                ? String.valueOf(existing.get("jobTitle")) : required(request.getJobTitle(), "职位名称不能为空"));
        row.put("jobDesc", request.getJobDesc() == null ? existing.get("jobDesc") : trimToNull(request.getJobDesc()));
        row.put("deptId", request.getDeptId() == null ? existing.get("deptId") : request.getDeptId());
        row.put("headcount", request.getHeadcount() == null
                ? numberAsLong(existing.get("headcount")).intValue() : request.getHeadcount());
        row.put("jobLevel", request.getJobLevel() == null ? existing.get("jobLevel") : trimToNull(request.getJobLevel()));
        row.put("salaryRange", request.getSalaryRange() == null
                ? existing.get("salaryRange") : trimToNull(request.getSalaryRange()));
        row.put("city", request.getCity() == null ? existing.get("city") : trimToNull(request.getCity()));
        row.put("ownerId", ownerId);
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        jobMapper.updateJob(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> existing = requireJob(id);
        if (!RecruitStatusMachine.JOB_DRAFT.equals(String.valueOf(existing.get("status")))) {
            throw new BusinessException("只有草稿状态的职位可以删除");
        }
        if (candidateMapper.countCandidatesByJob(id) > 0) {
            throw new BusinessException("职位下已有候选人，不能删除（可改为关闭）");
        }
        jobMapper.softDeleteJob(id, currentUsername());
    }

    /** 职位状态流转（招聘中 → 暂停 / 关闭 等；审批不在此接口，见 submit/decide）。 */
    @Transactional
    public Map<String, Object> updateStatus(Long id, String targetStatus) {
        Map<String, Object> existing = requireJob(id);
        String from = String.valueOf(existing.get("status"));
        String to = required(targetStatus, "目标状态不能为空");
        requireTransition(() -> RecruitStatusMachine.requireJobTransition(from, to));
        if (RecruitStatusMachine.JOB_PENDING_APPROVAL.equals(to)) {
            throw new BusinessException("进入审批请调用「提交需求审批」接口");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime publishTime = parseDateTimeValue(existing.get("publishTime"));
        jobMapper.updateJobStatus(id, to, String.valueOf(existing.get("approvalState")),
                numberAsLong(existing.get("approvalInstanceId")),
                publishTime,
                RecruitStatusMachine.JOB_CLOSED.equals(to) ? now : null,
                currentUsername());
        return viewOf(id);
    }

    /** 提交需求审批：状态 draft → pending_approval，并生成 M03 流程实例（类型 job_approval）。 */
    @Transactional
    public Map<String, Object> submitApproval(Long id, Long approverId) {
        Map<String, Object> existing = requireJob(id);
        String status = String.valueOf(existing.get("status"));
        if (!RecruitStatusMachine.JOB_DRAFT.equals(status)) {
            throw new BusinessException("只有草稿状态的职位可以提交需求审批（当前：" + RecruitStatusMachine.jobStatusLabel(status) + "）");
        }
        Long instanceId = flowService.start(RecruitFlowService.TYPE_JOB_APPROVAL, id,
                "职位需求审批：" + existing.get("jobTitle"), approverId);
        jobMapper.updateJobStatus(id, RecruitStatusMachine.JOB_PENDING_APPROVAL,
                RecruitStatusMachine.APPROVAL_PENDING, instanceId, null, null, currentUsername());
        return viewOf(id);
    }

    /** 需求审批处理：通过 → 招聘中（记发布人/时间），驳回 → 回到草稿。 */
    @Transactional
    public Map<String, Object> decideApproval(Long id, boolean approved, String opinion) {
        Map<String, Object> existing = requireJob(id);
        String status = String.valueOf(existing.get("status"));
        if (!RecruitStatusMachine.JOB_PENDING_APPROVAL.equals(status)) {
            throw new BusinessException("职位不在需求审批中，不能处理审批（当前："
                    + RecruitStatusMachine.jobStatusLabel(status) + "）");
        }
        Long instanceId = numberAsLong(existing.get("approvalInstanceId"));
        if (instanceId == null) {
            throw new BusinessException("职位缺少审批流程实例，无法处理");
        }
        flowService.decide(RecruitFlowService.TYPE_JOB_APPROVAL, instanceId, approved, opinion);
        if (approved) {
            jobMapper.updateJobStatus(id, RecruitStatusMachine.JOB_RECRUITING,
                    RecruitStatusMachine.APPROVAL_APPROVED, instanceId, LocalDateTime.now(), null, currentUsername());
        } else {
            jobMapper.updateJobStatus(id, RecruitStatusMachine.JOB_DRAFT,
                    RecruitStatusMachine.APPROVAL_REJECTED, instanceId, null, null, currentUsername());
        }
        return viewOf(id);
    }

    /** 写操作返回视图：内部直接读（避免 @DataScope 自调用丢失数据范围上下文）。 */
    private Map<String, Object> viewOf(Long id) {
        Map<String, Object> row = requireJob(id);
        decorate(row);
        Long instanceId = numberAsLong(row.get("approvalInstanceId"));
        row.put("approvalHistory", instanceId == null ? null : flowService.history(instanceId));
        return row;
    }
    public Map<String, Object> requireJob(Long id) {
        if (id == null) {
            throw new BusinessException("职位 ID 不能为空");
        }
        Map<String, Object> row = jobMapper.selectJobById(id);
        if (row == null) {
            throw new BusinessException("职位不存在或已删除");
        }
        return row;
    }


    private void decorate(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        row.put("statusLabel", RecruitStatusMachine.jobStatusLabel(status));
        row.put("nextStatuses", RecruitStatusMachine.jobNextStatuses(status));
        row.put("approvalStateLabel", switch (String.valueOf(row.get("approvalState"))) {
            case "none" -> "未提交";
            case "pending" -> "审批中";
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            default -> String.valueOf(row.get("approvalState"));
        });
    }
}
