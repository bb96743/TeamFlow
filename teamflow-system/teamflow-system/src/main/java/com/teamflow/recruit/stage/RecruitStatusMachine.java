package com.teamflow.recruit.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M08 招聘状态机（**候选人阶段、职位状态、Offer 状态、面试状态的唯一来源**，M08 RULE R5）。
 *
 * <p>与 M03 的状态机写法保持一致：状态码、中文标签与允许迁移表集中声明，Service 层只调用
 * {@link #canTransit}/{@link #requireTransition}，禁止在 Controller/Service 里散落
 * {@code if (status == X)} 判断；四张迁移表通过 {@link #definition()} 对外导出，便于前端渲染与排查。</p>
 *
 * <p><b>候选人阶段（R5）</b>：投递(applied) → 初筛(screening) → 面试(interview) → Offer(offer) → 入职(hired)，
 * 任一前置阶段可淘汰(rejected)；hired / rejected 为终态（不可逆）。</p>
 * <p><b>职位状态</b>：draft → pending_approval →（审批通过）recruiting →（可暂停/关闭）paused/closed；
 * 审批驳回回到 draft；closed 为终态。</p>
 * <p><b>Offer 状态</b>：draft → pending_approval →（通过）approved /（驳回）rejected → sent → accepted / declined →
 * onboarded；rejected/declined/onboarded 为终态。</p>
 */
public final class RecruitStatusMachine {

    // ================= 候选人阶段 =================
    public static final String CANDIDATE_APPLIED = "applied";
    public static final String CANDIDATE_SCREENING = "screening";
    public static final String CANDIDATE_INTERVIEW = "interview";
    public static final String CANDIDATE_OFFER = "offer";
    public static final String CANDIDATE_HIRED = "hired";
    public static final String CANDIDATE_REJECTED = "rejected";

    // ================= 职位状态 =================
    public static final String JOB_DRAFT = "draft";
    public static final String JOB_PENDING_APPROVAL = "pending_approval";
    public static final String JOB_RECRUITING = "recruiting";
    public static final String JOB_PAUSED = "paused";
    public static final String JOB_CLOSED = "closed";

    // ================= Offer 状态 =================
    public static final String OFFER_DRAFT = "draft";
    public static final String OFFER_PENDING_APPROVAL = "pending_approval";
    public static final String OFFER_APPROVED = "approved";
    public static final String OFFER_REJECTED = "rejected";
    public static final String OFFER_SENT = "sent";
    public static final String OFFER_ACCEPTED = "accepted";
    public static final String OFFER_DECLINED = "declined";
    public static final String OFFER_ONBOARDED = "onboarded";

    // ================= 面试状态 =================
    public static final String INTERVIEW_SCHEDULED = "scheduled";
    public static final String INTERVIEW_COMPLETED = "completed";
    public static final String INTERVIEW_CANCELLED = "cancelled";

    // ================= 审批状态（职位需求 / Offer 审批共用） =================
    public static final String APPROVAL_NONE = "none";
    public static final String APPROVAL_PENDING = "pending";
    public static final String APPROVAL_APPROVED = "approved";
    public static final String APPROVAL_REJECTED = "rejected";

    private static final Map<String, String> CANDIDATE_LABELS;
    private static final Map<String, Set<String>> CANDIDATE_TRANSITIONS;
    private static final Map<String, String> JOB_LABELS;
    private static final Map<String, Set<String>> JOB_TRANSITIONS;
    private static final Map<String, String> OFFER_LABELS;
    private static final Map<String, Set<String>> OFFER_TRANSITIONS;
    private static final Map<String, String> INTERVIEW_LABELS;
    private static final Map<String, Set<String>> INTERVIEW_TRANSITIONS;

    static {
        Map<String, String> candidateLabels = new LinkedHashMap<>();
        candidateLabels.put(CANDIDATE_APPLIED, "投递");
        candidateLabels.put(CANDIDATE_SCREENING, "初筛");
        candidateLabels.put(CANDIDATE_INTERVIEW, "面试");
        candidateLabels.put(CANDIDATE_OFFER, "Offer");
        candidateLabels.put(CANDIDATE_HIRED, "已入职");
        candidateLabels.put(CANDIDATE_REJECTED, "已淘汰");
        CANDIDATE_LABELS = Collections.unmodifiableMap(candidateLabels);

        Map<String, Set<String>> candidateTransitions = new LinkedHashMap<>();
        candidateTransitions.put(CANDIDATE_APPLIED, setOf(CANDIDATE_SCREENING, CANDIDATE_REJECTED));
        candidateTransitions.put(CANDIDATE_SCREENING, setOf(CANDIDATE_INTERVIEW, CANDIDATE_REJECTED));
        candidateTransitions.put(CANDIDATE_INTERVIEW, setOf(CANDIDATE_OFFER, CANDIDATE_REJECTED));
        candidateTransitions.put(CANDIDATE_OFFER, setOf(CANDIDATE_HIRED, CANDIDATE_REJECTED));
        candidateTransitions.put(CANDIDATE_HIRED, Set.of());
        candidateTransitions.put(CANDIDATE_REJECTED, Set.of());
        CANDIDATE_TRANSITIONS = Collections.unmodifiableMap(candidateTransitions);

        Map<String, String> jobLabels = new LinkedHashMap<>();
        jobLabels.put(JOB_DRAFT, "草稿");
        jobLabels.put(JOB_PENDING_APPROVAL, "需求审批中");
        jobLabels.put(JOB_RECRUITING, "招聘中");
        jobLabels.put(JOB_PAUSED, "已暂停");
        jobLabels.put(JOB_CLOSED, "已关闭");
        JOB_LABELS = Collections.unmodifiableMap(jobLabels);

        Map<String, Set<String>> jobTransitions = new LinkedHashMap<>();
        jobTransitions.put(JOB_DRAFT, setOf(JOB_PENDING_APPROVAL));
        jobTransitions.put(JOB_PENDING_APPROVAL, setOf(JOB_RECRUITING, JOB_DRAFT));
        jobTransitions.put(JOB_RECRUITING, setOf(JOB_PAUSED, JOB_CLOSED));
        jobTransitions.put(JOB_PAUSED, setOf(JOB_RECRUITING, JOB_CLOSED));
        jobTransitions.put(JOB_CLOSED, Set.of());
        JOB_TRANSITIONS = Collections.unmodifiableMap(jobTransitions);

        Map<String, String> offerLabels = new LinkedHashMap<>();
        offerLabels.put(OFFER_DRAFT, "草稿");
        offerLabels.put(OFFER_PENDING_APPROVAL, "审批中");
        offerLabels.put(OFFER_APPROVED, "已批准");
        offerLabels.put(OFFER_REJECTED, "已驳回");
        offerLabels.put(OFFER_SENT, "已发送");
        offerLabels.put(OFFER_ACCEPTED, "已接受");
        offerLabels.put(OFFER_DECLINED, "已拒绝");
        offerLabels.put(OFFER_ONBOARDED, "已入职");
        OFFER_LABELS = Collections.unmodifiableMap(offerLabels);

        Map<String, Set<String>> offerTransitions = new LinkedHashMap<>();
        offerTransitions.put(OFFER_DRAFT, setOf(OFFER_PENDING_APPROVAL));
        offerTransitions.put(OFFER_PENDING_APPROVAL, setOf(OFFER_APPROVED, OFFER_REJECTED));
        offerTransitions.put(OFFER_APPROVED, setOf(OFFER_SENT));
        offerTransitions.put(OFFER_REJECTED, setOf(OFFER_DRAFT));
        offerTransitions.put(OFFER_SENT, setOf(OFFER_ACCEPTED, OFFER_DECLINED));
        offerTransitions.put(OFFER_ACCEPTED, setOf(OFFER_ONBOARDED));
        offerTransitions.put(OFFER_DECLINED, Set.of());
        offerTransitions.put(OFFER_ONBOARDED, Set.of());
        OFFER_TRANSITIONS = Collections.unmodifiableMap(offerTransitions);

        Map<String, String> interviewLabels = new LinkedHashMap<>();
        interviewLabels.put(INTERVIEW_SCHEDULED, "已安排");
        interviewLabels.put(INTERVIEW_COMPLETED, "已完成");
        interviewLabels.put(INTERVIEW_CANCELLED, "已取消");
        INTERVIEW_LABELS = Collections.unmodifiableMap(interviewLabels);

        Map<String, Set<String>> interviewTransitions = new LinkedHashMap<>();
        interviewTransitions.put(INTERVIEW_SCHEDULED, setOf(INTERVIEW_COMPLETED, INTERVIEW_CANCELLED));
        interviewTransitions.put(INTERVIEW_COMPLETED, Set.of());
        interviewTransitions.put(INTERVIEW_CANCELLED, Set.of());
        INTERVIEW_TRANSITIONS = Collections.unmodifiableMap(interviewTransitions);
    }

    private RecruitStatusMachine() {
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    // ================= 候选人阶段 =================

    public static boolean isCandidateStage(String stage) {
        return stage != null && CANDIDATE_LABELS.containsKey(stage);
    }

    public static String candidateStageLabel(String stage) {
        return stage == null ? "" : CANDIDATE_LABELS.getOrDefault(stage, stage);
    }

    public static boolean canTransitCandidate(String from, String to) {
        if (!isCandidateStage(from) || !isCandidateStage(to)) {
            return false;
        }
        return CANDIDATE_TRANSITIONS.get(from).contains(to);
    }

    public static List<String> candidateNextStages(String from) {
        return isCandidateStage(from) ? new ArrayList<>(CANDIDATE_TRANSITIONS.get(from)) : List.of();
    }

    public static void requireCandidateTransition(String from, String to) {
        if (!isCandidateStage(to)) {
            throw new IllegalArgumentException("候选人阶段不合法：「" + to + "」，合法阶段为 " + String.join("、", CANDIDATE_LABELS.keySet()));
        }
        if (!isCandidateStage(from)) {
            throw new IllegalArgumentException("候选人当前阶段不合法：「" + from + "」");
        }
        if (!canTransitCandidate(from, to)) {
            throw new IllegalArgumentException("候选人阶段不允许从「" + candidateStageLabel(from) + "」变更为「"
                    + candidateStageLabel(to) + "」，当前阶段允许变更为：「"
                    + String.join("、", candidateNextStages(from).stream().map(RecruitStatusMachine::candidateStageLabel).toList()) + "」");
        }
    }

    // ================= 职位状态 =================

    public static boolean isJobStatus(String status) {
        return status != null && JOB_LABELS.containsKey(status);
    }

    public static String jobStatusLabel(String status) {
        return status == null ? "" : JOB_LABELS.getOrDefault(status, status);
    }

    public static boolean canTransitJob(String from, String to) {
        if (!isJobStatus(from) || !isJobStatus(to)) {
            return false;
        }
        return JOB_TRANSITIONS.get(from).contains(to);
    }

    public static List<String> jobNextStatuses(String from) {
        return isJobStatus(from) ? new ArrayList<>(JOB_TRANSITIONS.get(from)) : List.of();
    }

    public static void requireJobTransition(String from, String to) {
        if (!isJobStatus(to)) {
            throw new IllegalArgumentException("职位状态不合法：「" + to + "」");
        }
        if (!isJobStatus(from)) {
            throw new IllegalArgumentException("职位当前状态不合法：「" + from + "」");
        }
        if (!canTransitJob(from, to)) {
            throw new IllegalArgumentException("职位状态不允许从「" + jobStatusLabel(from) + "」变更为「"
                    + jobStatusLabel(to) + "」，当前状态允许变更为：「"
                    + String.join("、", jobNextStatuses(from).stream().map(RecruitStatusMachine::jobStatusLabel).toList()) + "」");
        }
    }

    // ================= Offer 状态 =================

    public static boolean isOfferStatus(String status) {
        return status != null && OFFER_LABELS.containsKey(status);
    }

    public static String offerStatusLabel(String status) {
        return status == null ? "" : OFFER_LABELS.getOrDefault(status, status);
    }

    public static boolean canTransitOffer(String from, String to) {
        if (!isOfferStatus(from) || !isOfferStatus(to)) {
            return false;
        }
        return OFFER_TRANSITIONS.get(from).contains(to);
    }

    public static List<String> offerNextStatuses(String from) {
        return isOfferStatus(from) ? new ArrayList<>(OFFER_TRANSITIONS.get(from)) : List.of();
    }

    public static void requireOfferTransition(String from, String to) {
        if (!isOfferStatus(to)) {
            throw new IllegalArgumentException("Offer 状态不合法：「" + to + "」");
        }
        if (!isOfferStatus(from)) {
            throw new IllegalArgumentException("Offer 当前状态不合法：「" + from + "」");
        }
        if (!canTransitOffer(from, to)) {
            throw new IllegalArgumentException("Offer 状态不允许从「" + offerStatusLabel(from) + "」变更为「"
                    + offerStatusLabel(to) + "」，当前状态允许变更为：「"
                    + String.join("、", offerNextStatuses(from).stream().map(RecruitStatusMachine::offerStatusLabel).toList()) + "」");
        }
    }

    // ================= 面试状态 =================

    public static boolean isInterviewStatus(String status) {
        return status != null && INTERVIEW_LABELS.containsKey(status);
    }

    public static String interviewStatusLabel(String status) {
        return status == null ? "" : INTERVIEW_LABELS.getOrDefault(status, status);
    }

    public static void requireInterviewTransition(String from, String to) {
        if (!isInterviewStatus(to) || !isInterviewStatus(from)) {
            throw new IllegalArgumentException("面试状态不合法：「" + from + " → " + to + "」");
        }
        if (!INTERVIEW_TRANSITIONS.get(from).contains(to)) {
            throw new IllegalArgumentException("面试状态不允许从「" + interviewStatusLabel(from) + "」变更为「"
                    + interviewStatusLabel(to) + "」");
        }
    }

    // ================= 对外导出（前端渲染 / 排障） =================

    public static Map<String, Object> definition() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("candidate", block(CANDIDATE_LABELS, CANDIDATE_TRANSITIONS));
        definition.put("job", block(JOB_LABELS, JOB_TRANSITIONS));
        definition.put("offer", block(OFFER_LABELS, OFFER_TRANSITIONS));
        definition.put("interview", block(INTERVIEW_LABELS, INTERVIEW_TRANSITIONS));
        definition.put("singleSourceOfTruth", RecruitStatusMachine.class.getName());
        return definition;
    }

    private static Map<String, Object> block(Map<String, String> labels, Map<String, Set<String>> transitions) {
        List<Map<String, Object>> states = new ArrayList<>();
        labels.forEach((code, label) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("label", label);
            state.put("terminal", transitions.get(code).isEmpty());
            states.add(state);
        });
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("states", states);
        block.put("transitions", transitions);
        return block;
    }
}
