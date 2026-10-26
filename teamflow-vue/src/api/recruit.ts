import { ApiError, httpClient, type HttpClient, type QueryParams, type ApiResponse } from './http.ts'
import { authSession } from './session.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M08 招聘接口（/api/recruit）
   ------------------------------------------------------------
   与后端 5 个 Controller 一一对应：
     RecruitJobController       /api/recruit/jobs
     RecruitCandidateController /api/recruit/candidates
     RecruitInterviewController /api/recruit/interviews
     RecruitOfferController     /api/recruit/offers · /offer-templates
     RecruitDashboardController /api/recruit/dashboard
   权限码见 recruit:*（V18__recruit_menu.sql），与后端 @RequiresPermission 完全一致。

   简历附件走 M00 统一上传能力（G-1）：本模块只存 file_id，
   上传 POST /api/system/files、下载 GET /api/system/files/{id} 都走鉴权接口。
   ============================================================ */

/** 职位状态：draft 草稿 / pending_approval 需求审批中 / recruiting 招聘中 / paused 已暂停 / closed 已关闭 */
export type RecruitJobStatus = 'draft' | 'pending_approval' | 'recruiting' | 'paused' | 'closed'
/** 候选人阶段：applied 投递 / screening 初筛 / interview 面试 / offer Offer / hired 已入职 / rejected 已淘汰 */
export type RecruitCandidateStage = 'applied' | 'screening' | 'interview' | 'offer' | 'hired' | 'rejected'
/** 面试状态：scheduled 已安排 / completed 已完成 / cancelled 已取消 */
export type RecruitInterviewStatus = 'scheduled' | 'completed' | 'cancelled'
/** Offer 状态 */
export type RecruitOfferStatus =
  | 'draft' | 'pending_approval' | 'approved' | 'rejected' | 'sent' | 'accepted' | 'declined' | 'onboarded'
/** 审批状态：none 未提交 / pending 审批中 / approved 已通过 / rejected 已驳回 */
export type RecruitApprovalState = 'none' | 'pending' | 'approved' | 'rejected'

/* ------------------------------ 职位 ------------------------------ */

export interface RecruitJobRow {
  id: number
  jobCode?: string | null
  jobTitle: string
  jobDesc?: string | null
  deptId?: number | null
  deptName?: string | null
  headcount?: number | null
  jobLevel?: string | null
  salaryRange?: string | null
  city?: string | null
  ownerId?: number | null
  ownerName?: string | null
  status: RecruitJobStatus | string
  statusLabel?: string | null
  approvalState?: RecruitApprovalState | string
  approvalStateLabel?: string | null
  approvalInstanceId?: number | null
  /** 允许流转到的目标状态（后端按状态机给出） */
  nextStatuses?: string[]
  /** 审批进展（详情接口返回） */
  approvalHistory?: RecruitApprovalHistory | null
  candidateCount?: number | null
  hiredCount?: number | null
  publishTime?: string | null
  closeTime?: string | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface RecruitJobWriteRequest {
  jobCode?: string
  jobTitle?: string
  jobDesc?: string | null
  deptId?: number | null
  headcount?: number | null
  jobLevel?: string | null
  salaryRange?: string | null
  city?: string | null
  ownerId?: number | null
  remark?: string | null
}

/* ----------------------------- 候选人 ----------------------------- */

export interface RecruitCandidateRow {
  id: number
  candidateCode?: string | null
  jobId?: number | null
  jobTitle?: string | null
  jobCode?: string | null
  nameSnapshot: string
  /** 列表默认脱敏（前 3 后 4），详情为完整值 */
  phone?: string | null
  email?: string | null
  phoneNorm?: string | null
  emailNorm?: string | null
  sourceChannel?: string | null
  channelLabel?: string | null
  tags?: string | null
  currentStage: RecruitCandidateStage | string
  stageLabel?: string | null
  nextStages?: string[]
  stageTime?: string | null
  rejectReason?: string | null
  hiredUserId?: number | null
  hireTime?: string | null
  remark?: string | null
  createTime?: string
  updateTime?: string
  resumes?: RecruitResumeRow[]
}

export interface RecruitCandidateWriteRequest {
  jobId?: number | null
  nameSnapshot?: string
  phone?: string | null
  email?: string | null
  sourceChannel?: string | null
  tags?: string | null
  remark?: string | null
}

/** 入库结果：duplicated=true 表示命中查重、返回的是库中已有候选人 */
export interface RecruitCandidateCreateResponse {
  id: number
  duplicated: boolean
}

export interface RecruitResumeRow {
  id: number
  candidateId: number
  fileId?: number | null
  fileName?: string | null
  parseStatus?: string | null
  screeningScore?: number | null
  createTime?: string
}

/* ------------------------------ 面试 ------------------------------ */

export interface RecruitInterviewRow {
  id: number
  candidateId: number
  candidateName?: string | null
  jobId?: number | null
  jobTitle?: string | null
  roundNo?: number | null
  roundName?: string | null
  interviewerId?: number | null
  interviewerName?: string | null
  scheduledTime?: string | null
  durationMinutes?: number | null
  location?: string | null
  status: RecruitInterviewStatus | string
  statusLabel?: string | null
  result?: string | null
  resultLabel?: string | null
  remark?: string | null
  createTime?: string
  updateTime?: string
  feedbacks?: RecruitInterviewFeedbackRow[]
}

export interface RecruitInterviewWriteRequest {
  candidateId?: number | null
  roundName?: string | null
  interviewerId?: number | null
  /** yyyy-MM-dd HH:mm */
  scheduledTime?: string
  durationMinutes?: number | null
  location?: string | null
  remark?: string | null
}

export interface RecruitInterviewUpdateRequest {
  roundName?: string | null
  interviewerId?: number | null
  scheduledTime?: string | null
  durationMinutes?: number | null
  location?: string | null
  status?: string
  result?: string
  remark?: string | null
}

export interface RecruitInterviewFeedbackRow {
  id: number
  interviewId: number
  dimensionCode?: string | null
  dimensionName?: string | null
  interviewerId?: number | null
  score?: number | null
  comment?: string | null
  submitTime?: string
}

export interface RecruitFeedbackDimension {
  dimensionCode: string
  dimensionName: string
  score?: number | null
  comment?: string | null
}

export interface RecruitFeedbackWriteRequest {
  dimensions: RecruitFeedbackDimension[]
}

/* ------------------------------ Offer ----------------------------- */

export interface RecruitOfferRow {
  id: number
  candidateId: number
  candidateName?: string | null
  jobId?: number | null
  jobTitle?: string | null
  templateId?: number | null
  templateName?: string | null
  salary?: number | null
  expectedOnboardDate?: string | null
  status: RecruitOfferStatus | string
  statusLabel?: string | null
  approvalState?: RecruitApprovalState | string
  approvalStateLabel?: string | null
  approvalInstanceId?: number | null
  nextStatuses?: string[]
  approvalHistory?: RecruitApprovalHistory | null
  sentTime?: string | null
  acceptedTime?: string | null
  declinedReason?: string | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface RecruitOfferWriteRequest {
  candidateId?: number | null
  templateId?: number | null
  salary?: number | null
  /** yyyy-MM-dd */
  expectedOnboardDate?: string | null
  remark?: string | null
}

export interface RecruitOfferTemplateRow {
  id: number
  templateCode?: string | null
  templateName: string
  content?: string | null
  status?: string | null
  remark?: string | null
  createTime?: string
}

export interface RecruitOfferTemplateWriteRequest {
  templateCode?: string
  templateName?: string
  content?: string | null
  status?: string | null
  remark?: string | null
}

export interface RecruitOfferRespondRequest {
  accepted?: boolean
  reason?: string | null
}

export interface RecruitOnboardRequest {
  username?: string | null
  deptId?: number | null
  roleIds?: number[] | null
  postId?: number | null
}

/** 入职结果：duplicated=true 表示该候选人此前已入职，返回同一 userId（幂等） */
export interface RecruitOnboardResult {
  offerId: number
  candidateId: number
  userId: number
  username?: string | null
  duplicated: boolean
  message?: string | null
}

/* --------------------------- 审批与漏斗 --------------------------- */

export interface RecruitApprovalDecisionRequest {
  approved?: boolean
  opinion?: string | null
}

export interface RecruitApprovalDecisionResult {
  instanceId: number
  approved: boolean
  instanceStatus?: string | null
  operatorName?: string | null
  operatedTime?: string | null
}

/** 审批节点留痕（复用 M03 流程实例的节点记录） */
export interface RecruitApprovalNode {
  id?: number
  nodeKey?: string | null
  nodeName?: string | null
  nodeStatus?: string | null
  fromStatus?: string | null
  toStatus?: string | null
  operatorId?: number | null
  operatorName?: string | null
  opinion?: string | null
  operatedTime?: string | null
}

export interface RecruitApprovalHistory {
  instanceId: number
  instanceType?: string | null
  instanceStatus?: string | null
  defCode?: string | null
  startBy?: number | null
  startTime?: string | null
  endTime?: string | null
  nodes?: RecruitApprovalNode[]
}

export interface RecruitFunnelStage {
  stage: string
  label: string
  value: number
  /** 相对上一阶段的转化率（首个阶段为 null） */
  conversionRate?: number | null
  missing?: boolean
}

/** 招聘看板指标登记状态（2.1.5：口径须在 M13 指标字典登记后才出数） */
export interface RecruitDashboardMetricState {
  allRegistered: boolean
  metrics: Array<{
    metricCode: string
    metricName: string
    definition?: string
    registered?: boolean
    note?: string | null
  }>
}

export interface RecruitDashboardOverview {  period?: { startDate?: string; endDate?: string; timezone?: string; rangeDays?: number }
  summary?: {
    jobCount?: number
    candidateCount?: number
    interviewCandidateCount?: number
    offerCandidateCount?: number
    hiredCount?: number
  }
  funnel?: RecruitFunnelStage[]
  channels?: Array<Record<string, unknown>>
  monthlyHires?: Array<Record<string, unknown>>
  hireCycle?: Record<string, unknown>
  offerStats?: Record<string, unknown>
  interviewStats?: Record<string, unknown>
}

/* ---------------------------- 文件（G-1） -------------------------- */

export interface RecruitFileUploadResult {
  id: number
  originalName?: string | null
  size?: number | null
  contentType?: string | null
  bizType?: string | null
  bizId?: number | null
  url?: string | null
}

export interface RecruitApi {
  jobs: {
    list: (query?: QueryParams) => Promise<PageResult<RecruitJobRow>>
    detail: (id: number) => Promise<RecruitJobRow>
    create: (body: RecruitJobWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: RecruitJobWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
    updateStatus: (id: number, status: string) => Promise<RecruitJobRow>
    submitApproval: (id: number, approverId?: number | null) => Promise<RecruitJobRow>
    decideApproval: (id: number, body: RecruitApprovalDecisionRequest) => Promise<RecruitApprovalDecisionResult>
  }
  candidates: {
    list: (query?: QueryParams) => Promise<PageResult<RecruitCandidateRow>>
    detail: (id: number) => Promise<RecruitCandidateRow>
    create: (body: RecruitCandidateWriteRequest) => Promise<RecruitCandidateCreateResponse>
    update: (id: number, body: RecruitCandidateWriteRequest) => Promise<void>
    changeStage: (id: number, stage: string, rejectReason?: string) => Promise<RecruitCandidateRow>
    resumes: (id: number) => Promise<RecruitResumeRow[]>
    attachResume: (id: number, body: { fileId: number; fileName?: string; remark?: string }) => Promise<{ id: number }>
  }
  interviews: {
    list: (query?: QueryParams) => Promise<PageResult<RecruitInterviewRow>>
    detail: (id: number) => Promise<RecruitInterviewRow>
    schedule: (body: RecruitInterviewWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: RecruitInterviewUpdateRequest) => Promise<RecruitInterviewRow>
    submitFeedback: (id: number, body: RecruitFeedbackWriteRequest) => Promise<RecruitInterviewRow>
  }
  offers: {
    list: (query?: QueryParams) => Promise<PageResult<RecruitOfferRow>>
    detail: (id: number) => Promise<RecruitOfferRow>
    create: (body: RecruitOfferWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: RecruitOfferWriteRequest) => Promise<void>
    submitApproval: (id: number, approverId?: number | null) => Promise<RecruitOfferRow>
    decideApproval: (id: number, body: RecruitApprovalDecisionRequest) => Promise<RecruitApprovalDecisionResult>
    send: (id: number) => Promise<RecruitOfferRow>
    respond: (id: number, body: RecruitOfferRespondRequest) => Promise<RecruitOfferRow>
    onboard: (id: number, body: RecruitOnboardRequest) => Promise<RecruitOnboardResult>
    templates: (status?: string) => Promise<RecruitOfferTemplateRow[]>
    createTemplate: (body: RecruitOfferTemplateWriteRequest) => Promise<{ id: number }>
    updateTemplate: (id: number, body: RecruitOfferTemplateWriteRequest) => Promise<void>
  }
  dashboard: {
    overview: (startDate: string, endDate: string) => Promise<RecruitDashboardOverview>
    /** 指标登记状态：allRegistered=false 时看板拒绝出数（需先登记口径） */
    metrics: () => Promise<RecruitDashboardMetricState>
    /** 把 4 个招聘指标口径登记到 M13 指标字典（幂等） */
    registerMetrics: () => Promise<Record<string, unknown>>
  }
  files: {
    upload: (file: File, bizType?: string, bizId?: number) => Promise<RecruitFileUploadResult>
    download: (fileId: number) => Promise<Blob>
  }
}

/** 接口根地址：与 http 客户端保持一致（同一个 VITE_API_BASE_URL 约定） */
function apiBaseUrl(): string {
  const env = (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env
  return (env?.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
}

export function createRecruitApi(client: HttpClient): RecruitApi {
  return {
    jobs: {
      list: query => client.get<PageResult<RecruitJobRow>>('/recruit/jobs', query),
      detail: id => client.get<RecruitJobRow>(`/recruit/jobs/${id}`),
      create: body => client.post<{ id: number }>('/recruit/jobs', body),
      update: (id, body) => client.put<void>(`/recruit/jobs/${id}`, body),
      remove: id => client.delete<void>(`/recruit/jobs/${id}`),
      updateStatus: (id, status) => client.put<RecruitJobRow>(`/recruit/jobs/${id}/status`, { status }),
      submitApproval: (id, approverId) =>
        client.post<RecruitJobRow>(`/recruit/jobs/${id}/submit-approval`, { approverId: approverId ?? null }),
      decideApproval: (id, body) => client.put<RecruitApprovalDecisionResult>(`/recruit/jobs/${id}/approval`, body),
    },
    candidates: {
      list: query => client.get<PageResult<RecruitCandidateRow>>('/recruit/candidates', query),
      detail: id => client.get<RecruitCandidateRow>(`/recruit/candidates/${id}`),
      create: body => client.post<RecruitCandidateCreateResponse>('/recruit/candidates', body),
      update: (id, body) => client.put<void>(`/recruit/candidates/${id}`, body),
      changeStage: (id, stage, rejectReason) =>
        client.put<RecruitCandidateRow>(`/recruit/candidates/${id}/stage`, { stage, rejectReason: rejectReason ?? null }),
      resumes: id => client.get<RecruitResumeRow[]>(`/recruit/candidates/${id}/resumes`),
      attachResume: (id, body) => client.post<{ id: number }>(`/recruit/candidates/${id}/resumes`, body),
    },
    interviews: {
      list: query => client.get<PageResult<RecruitInterviewRow>>('/recruit/interviews', query),
      detail: id => client.get<RecruitInterviewRow>(`/recruit/interviews/${id}`),
      schedule: body => client.post<{ id: number }>('/recruit/interviews', body),
      update: (id, body) => client.put<RecruitInterviewRow>(`/recruit/interviews/${id}`, body),
      submitFeedback: (id, body) => client.post<RecruitInterviewRow>(`/recruit/interviews/${id}/feedbacks`, body),
    },
    offers: {
      list: query => client.get<PageResult<RecruitOfferRow>>('/recruit/offers', query),
      detail: id => client.get<RecruitOfferRow>(`/recruit/offers/${id}`),
      create: body => client.post<{ id: number }>('/recruit/offers', body),
      update: (id, body) => client.put<void>(`/recruit/offers/${id}`, body),
      submitApproval: (id, approverId) =>
        client.post<RecruitOfferRow>(`/recruit/offers/${id}/submit-approval`, { approverId: approverId ?? null }),
      decideApproval: (id, body) => client.put<RecruitApprovalDecisionResult>(`/recruit/offers/${id}/approval`, body),
      send: id => client.post<RecruitOfferRow>(`/recruit/offers/${id}/send`),
      respond: (id, body) => client.put<RecruitOfferRow>(`/recruit/offers/${id}/respond`, body),
      onboard: (id, body) => client.post<RecruitOnboardResult>(`/recruit/offers/${id}/onboard`, body),
      templates: status => client.get<RecruitOfferTemplateRow[]>('/recruit/offer-templates', { status }),
      createTemplate: body => client.post<{ id: number }>('/recruit/offer-templates', body),
      updateTemplate: (id, body) => client.put<void>(`/recruit/offer-templates/${id}`, body),
    },
    dashboard: {
      overview: (startDate, endDate) =>
        client.get<RecruitDashboardOverview>('/recruit/dashboard/overview', { startDate, endDate }),
      metrics: () => client.get<RecruitDashboardMetricState>('/recruit/dashboard/metrics'),
      registerMetrics: () => client.post<Record<string, unknown>>('/recruit/dashboard/register-metrics'),
    },
    files: {
      /** 上传简历文件（multipart）：http 客户端只做 JSON，这里用同一套鉴权与错误约定直传 */
      upload: async (file, bizType, bizId) => {
        const form = new FormData()
        form.append('file', file)
        if (bizType) form.append('bizType', bizType)
        if (bizId !== undefined && bizId !== null) form.append('bizId', String(bizId))
        const token = authSession.token()
        const response = await fetch(`${apiBaseUrl()}/system/files`, {
          method: 'POST',
          headers: token ? { Authorization: `Bearer ${token}` } : undefined,
          body: form,
        })
        const text = await response.text()
        const payload = (text ? JSON.parse(text) : { code: response.status, message: response.statusText }) as ApiResponse<RecruitFileUploadResult>
        if (!response.ok || payload.code !== 200) {
          throw new ApiError(payload.message || '简历上传失败', payload.code || response.status, response.status, payload.traceId)
        }
        return payload.data
      },
      /** 下载简历：走鉴权下载接口，不拼静态地址 */
      download: fileId => client.download(`/system/files/${fileId}`),
    },
  }
}

export const recruitApi = createRecruitApi(httpClient)
