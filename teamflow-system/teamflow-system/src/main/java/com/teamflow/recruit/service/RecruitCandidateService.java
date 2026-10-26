package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.recruit.domain.dto.RecruitCandidateStageRequest;
import com.teamflow.recruit.domain.dto.RecruitCandidateWriteRequest;
import com.teamflow.recruit.domain.dto.RecruitResumeAttachRequest;
import com.teamflow.recruit.domain.vo.RecruitCandidateCreateResponse;
import com.teamflow.recruit.domain.vo.RecruitIdResponse;
import com.teamflow.recruit.mapper.RecruitCandidateMapper;
import com.teamflow.recruit.mapper.RecruitJobMapper;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import com.teamflow.recruit.mapper.RecruitResumeMapper;
import com.teamflow.recruit.stage.RecruitStatusMachine;
import com.teamflow.system.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M08 候选人服务（2.1.2）：入库与查重、标签、阶段推进、简历版本（只存 file_id）。
 *
 * <p><b>R1 查重</b>：手机号 + 邮箱归一化后组成 {@code dedup_key} 唯一；命中已有记录时**返回原记录**
 * （{@code duplicated=true}），不重复创建。</p>
 * <p><b>不建用户表</b>：{@code name_snapshot} 是外部候选人报名姓名快照；入职只写 {@code hired_user_id} 引用
 * {@code sys_user.id}，不复制账号/昵称/部门。</p>
 * <p><b>不做文件存储</b>：简历文件由 G-1 统一上传，本模块只存 {@code file_id}，并通过 M00
 * {@link FileService#incrementRef} 维护引用计数（保证被引用文件不可被误删）。</p>
 */
@Service
public class RecruitCandidateService extends RecruitServiceSupport {

    private static final Set<String> CHANNELS = Set.of("referral", "website", "social", "agency", "internal", "other");
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    private final RecruitCandidateMapper candidateMapper;
    private final RecruitJobMapper jobMapper;
    private final RecruitResumeMapper resumeMapper;
    private final FileService fileService;

    public RecruitCandidateService(
            RecruitPlatformMapper platformMapper,
            RecruitCandidateMapper candidateMapper,
            RecruitJobMapper jobMapper,
            RecruitResumeMapper resumeMapper,
            FileService fileService
    ) {
        super(platformMapper);
        this.candidateMapper = candidateMapper;
        this.jobMapper = jobMapper;
        this.resumeMapper = resumeMapper;
        this.fileService = fileService;
    }

    @DataScope
    public PageResult<Map<String, Object>> list(
            PageQuery query, String keyword, Long jobId, String stage, String channel) {
        Page page = pageOf(query);
        long total = candidateMapper.countCandidates(
                trimToNull(keyword), jobId, trimToNull(stage), trimToNull(channel), DataScopeContext.current());
        List<Map<String, Object>> rows = candidateMapper.selectCandidates(
                trimToNull(keyword), jobId, trimToNull(stage), trimToNull(channel), DataScopeContext.current(),
                page.offset(), page.pageSize());
        rows.forEach(row -> {
            maskContact(row);
            decorate(row);
        });
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /** 详情：返回完整联系方式（R6 只要求列表默认脱敏；查看详情受 recruit:candidate:list 权限控制）。 */
    @DataScope
    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = candidateMapper.selectCandidate(id, DataScopeContext.current());
        if (row == null) {
            throw new BusinessException("候选人不存在或无权访问");
        }
        decorate(row);
        row.put("resumes", resumeMapper.selectByCandidate(id));
        return row;
    }

    /** R1：手机号 + 邮箱归一化查重；命中返回已有候选人（不重复创建）。 */
    @Transactional
    public RecruitCandidateCreateResponse create(RecruitCandidateWriteRequest request) {
        Long jobId = request.getJobId();
        if (jobId == null) {
            throw new BusinessException("应聘职位不能为空");
        }
        Map<String, Object> job = jobMapper.selectJobById(jobId);
        if (job == null) {
            throw new BusinessException("应聘职位不存在");
        }
        if (!RecruitStatusMachine.JOB_RECRUITING.equals(String.valueOf(job.get("status")))) {
            throw new BusinessException("只有「招聘中」的职位可以入库候选人（当前职位状态："
                    + RecruitStatusMachine.jobStatusLabel(String.valueOf(job.get("status"))) + "）");
        }
        String name = required(request.getNameSnapshot(), "候选人姓名不能为空");
        String phoneNorm = normalizePhone(request.getPhone());
        String emailNorm = normalizeEmail(request.getEmail());
        if (phoneNorm == null && emailNorm == null) {
            throw new BusinessException("手机号与邮箱至少填写一个（用于查重与后续联系）");
        }
        String dedupKey = (phoneNorm == null ? "" : phoneNorm) + "|" + (emailNorm == null ? "" : emailNorm);
        Map<String, Object> existing = candidateMapper.selectCandidateByDedupKey(dedupKey);
        if (existing != null) {
            return new RecruitCandidateCreateResponse(numberAsLong(existing.get("id")), true);
        }
        String channel = defaultString(request.getSourceChannel(), "other");
        if (!CHANNELS.contains(channel)) {
            throw new BusinessException("来源渠道只能为 " + String.join("、", CHANNELS));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidateCode", "CAND-" + CODE_SEQ.incrementAndGet());
        row.put("jobId", jobId);
        row.put("nameSnapshot", name);
        row.put("phone", trimToNull(request.getPhone()));
        row.put("email", trimToNull(request.getEmail()));
        row.put("phoneNorm", phoneNorm);
        row.put("emailNorm", emailNorm);
        row.put("dedupKey", dedupKey);
        row.put("sourceChannel", channel);
        row.put("tags", trimToNull(request.getTags()));
        row.put("currentStage", RecruitStatusMachine.CANDIDATE_APPLIED);
        row.put("stageTime", LocalDateTime.now());
        row.put("remark", trimToNull(request.getRemark()));
        candidateMapper.insertCandidate(row, currentUsername());
        return new RecruitCandidateCreateResponse(numberAsLong(row.get("id")), false);
    }

    @Transactional
    public void update(Long id, RecruitCandidateWriteRequest request) {
        Map<String, Object> existing = requireCandidate(id);
        if (RecruitStatusMachine.CANDIDATE_HIRED.equals(String.valueOf(existing.get("currentStage")))) {
            throw new BusinessException("候选人已入职，档案不可编辑（如需变更请联系管理员走用户管理）");
        }
        String name = request.getNameSnapshot() == null
                ? String.valueOf(existing.get("nameSnapshot")) : required(request.getNameSnapshot(), "候选人姓名不能为空");
        String phone = request.getPhone() == null ? (String) existing.get("phone") : trimToNull(request.getPhone());
        String email = request.getEmail() == null ? (String) existing.get("email") : trimToNull(request.getEmail());
        String phoneNorm = normalizePhone(phone);
        String emailNorm = normalizeEmail(email);
        if (phoneNorm == null && emailNorm == null) {
            throw new BusinessException("手机号与邮箱至少填写一个");
        }
        String dedupKey = (phoneNorm == null ? "" : phoneNorm) + "|" + (emailNorm == null ? "" : emailNorm);
        Map<String, Object> duplicated = candidateMapper.selectCandidateByDedupKey(dedupKey);
        if (duplicated != null && !numberAsLong(duplicated.get("id")).equals(id)) {
            throw new BusinessException("手机号+邮箱与已有候选人重复（候选人 ID=" + duplicated.get("id") + "）");
        }
        String channel = request.getSourceChannel() == null
                ? String.valueOf(existing.get("sourceChannel")) : request.getSourceChannel();
        if (!CHANNELS.contains(channel)) {
            throw new BusinessException("来源渠道只能为 " + String.join("、", CHANNELS));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nameSnapshot", name);
        row.put("phone", phone);
        row.put("email", email);
        row.put("phoneNorm", phoneNorm);
        row.put("emailNorm", emailNorm);
        row.put("dedupKey", dedupKey);
        row.put("sourceChannel", channel);
        row.put("tags", request.getTags() == null ? existing.get("tags") : trimToNull(request.getTags()));
        row.put("remark", request.getRemark() == null ? existing.get("remark") : trimToNull(request.getRemark()));
        candidateMapper.updateCandidate(id, row, currentUsername());
    }

    /** 阶段推进（R5 状态机集中判定；淘汰必须填原因；入职只能走 Offer 入职接口）。 */
    @Transactional
    public Map<String, Object> changeStage(Long id, RecruitCandidateStageRequest request) {
        Map<String, Object> existing = requireCandidate(id);
        String from = String.valueOf(existing.get("currentStage"));
        String to = required(request.getStage(), "目标阶段不能为空");
        requireTransition(() -> RecruitStatusMachine.requireCandidateTransition(from, to));
        if (RecruitStatusMachine.CANDIDATE_HIRED.equals(to)) {
            throw new BusinessException("入职请通过「Offer 入职」接口完成（会创建 sys_user 并记录 hired_user_id）");
        }
        String rejectReason = null;
        if (RecruitStatusMachine.CANDIDATE_REJECTED.equals(to)) {
            rejectReason = required(request.getRejectReason(), "淘汰候选人必须填写原因（留痕可追溯）");
        }
        candidateMapper.updateStage(id, to, LocalDateTime.now(), rejectReason, currentUsername());
        return viewOf(id);
    }

    /** 简历入库：文件已由 G-1 上传，本接口只关联 file_id 并递增 M00 引用计数、生成新版本号。 */
    @Transactional
    public RecruitIdResponse attachResume(Long candidateId, RecruitResumeAttachRequest request) {
        Map<String, Object> candidate = requireCandidate(candidateId);
        if (request.getFileId() == null) {
            throw new BusinessException("简历文件 ID 不能为空（文件须先经 /api/system/files 上传）");
        }
        // 引用计数 +1：文件不存在时 FileService 抛业务异常；被引用期间文件不可删除
        fileService.incrementRef(request.getFileId());
        int version = resumeMapper.selectMaxVersion(candidateId) + 1;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidateId", candidateId);
        row.put("fileId", request.getFileId());
        row.put("fileName", trimToNull(request.getFileName()));
        row.put("version", version);
        row.put("remark", trimToNull(request.getRemark()));
        try {
            resumeMapper.insertResume(row, currentUsername());
        } catch (RuntimeException exception) {
            // 版本唯一键冲突等异常时回滚引用计数，避免文件被永久占引用
            try {
                fileService.decrementRef(request.getFileId());
            } catch (RuntimeException ignored) {
                // 回滚失败不影响原异常抛出
            }
            throw exception;
        }
        if (candidate == null) {
            throw new BusinessException("候选人不存在");
        }
        return new RecruitIdResponse(numberAsLong(row.get("id")));
    }

    @DataScope
    public List<Map<String, Object>> listResumes(Long candidateId) {
        Map<String, Object> candidate = candidateMapper.selectCandidate(candidateId, DataScopeContext.current());
        if (candidate == null) {
            throw new BusinessException("候选人不存在或无权访问");
        }
        return resumeMapper.selectByCandidate(candidateId);
    }

    /** 写操作返回视图：内部直接读（避免 @DataScope 自调用丢失数据范围上下文）。 */
    private Map<String, Object> viewOf(Long id) {
        Map<String, Object> row = requireCandidate(id);
        decorate(row);
        row.put("resumes", resumeMapper.selectByCandidate(id));
        return row;
    }
    public Map<String, Object> requireCandidate(Long id) {
        if (id == null) {
            throw new BusinessException("候选人 ID 不能为空");
        }
        Map<String, Object> row = candidateMapper.selectCandidateById(id);
        if (row == null) {
            throw new BusinessException("候选人不存在或已删除");
        }
        return row;
    }

    /** 列表脱敏（R6）：手机号保留前 3 后 4，邮箱保留首字符与域名。 */
    private void maskContact(Map<String, Object> row) {
        row.put("phone", maskPhone(row.get("phone") == null ? null : String.valueOf(row.get("phone"))));
        row.put("email", maskEmail(row.get("email") == null ? null : String.valueOf(row.get("email"))));
    }

    private void decorate(Map<String, Object> row) {
        String stage = String.valueOf(row.get("currentStage"));
        row.put("stageLabel", RecruitStatusMachine.candidateStageLabel(stage));
        row.put("nextStages", RecruitStatusMachine.candidateNextStages(stage));
        row.put("channelLabel", switch (String.valueOf(row.get("sourceChannel"))) {
            case "referral" -> "内推";
            case "website" -> "官网";
            case "social" -> "社交招聘";
            case "agency" -> "猎头";
            case "internal" -> "内部转岗";
            default -> "其他";
        });
    }
}
