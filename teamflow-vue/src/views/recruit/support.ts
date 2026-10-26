/* ============================================================
   M08 招聘页面共用的展示口径
   ------------------------------------------------------------
   后端没有「招聘状态机查询」接口，状态语义在 RecruitStatusMachine
   中集中定义（职位/候选人/面试/Offer 四套）。这里只做**文案与语义色的对照**，
   以及日期、金额的显示格式；允许的流转目标一律用接口返回的
   nextStatuses / nextStages，不在前端另存一份迁移表。
   ============================================================ */

function toDate(value?: string | null): Date | null {
  if (!value) return null
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const parsed = new Date(normalized)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

export function dateText(value?: string | null): string {
  const date = toDate(value)
  if (!date) return '—'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

export function dateTimeText(value?: string | null): string {
  const date = toDate(value)
  if (!date) return '—'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day} ${hour}:${minute}`
}

/** yyyy-MM-dd HH:mm（面试时间等接口按此格式接收） */
export function dateTimeInput(value?: string | null): string {
  return value ? dateTimeText(value) : ''
}

export function moneyText(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  return `¥ ${Number(value).toLocaleString('zh-CN')}`
}

export function percentText(value?: number | null): string {
  return value === null || value === undefined ? '—' : `${Number(value).toFixed(1)}%`
}

/* ── 职位 ── */
export const JOB_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  pending_approval: '需求审批中',
  recruiting: '招聘中',
  paused: '已暂停',
  closed: '已关闭',
}
export const JOB_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  pending_approval: 'info',
  recruiting: 'ok',
  paused: 'neutral',
  closed: 'neutral',
}
export const JOB_STATUS_OPTIONS = Object.entries(JOB_STATUS_TEXT).map(([value, label]) => ({ label, value }))

/* ── 候选人阶段 ── */
export const CANDIDATE_STAGE_TEXT: Record<string, string> = {
  applied: '投递',
  screening: '初筛',
  interview: '面试',
  offer: 'Offer',
  hired: '已入职',
  rejected: '已淘汰',
}
export const CANDIDATE_STAGE_TONE: Record<string, string> = {
  applied: 'neutral',
  screening: 'info',
  interview: 'info',
  offer: 'info',
  hired: 'ok',
  rejected: 'danger',
}
export const CANDIDATE_STAGE_OPTIONS = Object.entries(CANDIDATE_STAGE_TEXT)
  .filter(([value]) => value !== 'hired')
  .map(([value, label]) => ({ label, value }))
export const CANDIDATE_CHANNEL_TEXT: Record<string, string> = {
  referral: '内推',
  website: '官网',
  social: '社交招聘',
  agency: '猎头',
  internal: '内部转岗',
  other: '其他',
}
export const CANDIDATE_CHANNEL_OPTIONS = Object.entries(CANDIDATE_CHANNEL_TEXT).map(([value, label]) => ({ label, value }))

/* ── 面试 ── */
export const INTERVIEW_STATUS_TEXT: Record<string, string> = {
  scheduled: '已安排',
  completed: '已完成',
  cancelled: '已取消',
}
export const INTERVIEW_STATUS_TONE: Record<string, string> = {
  scheduled: 'info',
  completed: 'ok',
  cancelled: 'neutral',
}
export const INTERVIEW_STATUS_OPTIONS = Object.entries(INTERVIEW_STATUS_TEXT).map(([value, label]) => ({ label, value }))
export const INTERVIEW_RESULT_TEXT: Record<string, string> = {
  pending: '待定',
  pass: '通过',
  fail: '不通过',
}
export const INTERVIEW_RESULT_TONE: Record<string, string> = {
  pending: 'neutral',
  pass: 'ok',
  fail: 'danger',
}
/** 结构化评价的两个固定维度（R4：每维度独立评分 + 评语） */
export const INTERVIEW_DIMENSIONS = [
  { code: 'professional', name: '专业能力' },
  { code: 'communication', name: '沟通协作' },
]

/* ── Offer ── */
export const OFFER_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  pending_approval: '审批中',
  approved: '已批准',
  rejected: '已驳回',
  sent: '已发送',
  accepted: '已接受',
  declined: '已拒绝',
  onboarded: '已入职',
}
export const OFFER_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  pending_approval: 'info',
  approved: 'ok',
  rejected: 'danger',
  sent: 'info',
  accepted: 'ok',
  declined: 'danger',
  onboarded: 'ok',
}
export const OFFER_STATUS_OPTIONS = Object.entries(OFFER_STATUS_TEXT).map(([value, label]) => ({ label, value }))

/* ── 审批 ── */
export const APPROVAL_STATE_TEXT: Record<string, string> = {
  none: '未提交',
  pending: '审批中',
  approved: '已通过',
  rejected: '已驳回',
}
export const APPROVAL_STATE_TONE: Record<string, string> = {
  none: 'neutral',
  pending: 'info',
  approved: 'ok',
  rejected: 'danger',
}
export const APPROVAL_INSTANCE_STATUS_TEXT: Record<string, string> = {
  running: '审批中',
  completed: '已通过',
  terminated: '已终止',
}
export const NODE_STATUS_TEXT: Record<string, string> = {
  pending: '待处理',
  completed: '已完成',
  rejected: '已驳回',
}

/** 今日起往前 n 天的日期区间（漏斗查询用，格式 yyyy-MM-dd） */
export function rangeOfDays(days: number): { startDate: string; endDate: string } {
  const end = new Date()
  const start = new Date(end.getTime() - (days - 1) * 24 * 60 * 60 * 1000)
  return { startDate: dateText(start.toISOString()), endDate: dateText(end.toISOString()) }
}
