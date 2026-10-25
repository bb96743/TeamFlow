import type { OkrKeyResultRow, OkrObjectiveLevel, OkrProgressSource } from '@/api/okr'

/* ============================================================
   M02 页面共用的展示口径
   ------------------------------------------------------------
   只做「值与文案的对照」和日期格式化，不含业务判断：
   状态是否允许变更、权重是否合法、校准是否超限，一律由后端判定，
   前端只负责把后端返回的值翻译成可读文案与语义色。
   ============================================================ */

function toDate(value?: string | null): Date | null {
  if (!value) return null
  // 后端日期字段有两种形态：ISO（2026-10-25T00:00:00.000Z）与本地时间串（2026-10-25 10:20）
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

/** 进度百分比文案：后端返回 0–100 的数值 */
export function percentText(value?: number | null): string {
  return value === null || value === undefined ? '—' : `${Number(value).toFixed(1)}%`
}

/** 数值文案：整数不补小数位，非整数保留两位 */
export function numberText(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return Number.isInteger(num) ? String(num) : num.toFixed(2)
}

export const PERIOD_STATUS_TEXT: Record<string, string> = {
  draft: '未开启',
  active: '进行中',
  archived: '已归档',
}

export const PERIOD_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  active: 'ok',
  archived: 'info',
}

export const PERIOD_TYPE_OPTIONS = [
  { label: '季度', value: 'quarter' },
  { label: '月度', value: 'month' },
]

export const PERIOD_TYPE_TEXT: Record<string, string> = {
  quarter: '季度',
  month: '月度',
}

export const LEVEL_TEXT: Record<OkrObjectiveLevel | string, string> = {
  company: '公司级',
  dept: '部门级',
  personal: '个人级',
}

export const LEVEL_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '公司级', value: 'company' },
  { label: '部门级', value: 'dept' },
  { label: '个人级', value: 'personal' },
]

export const OBJECTIVE_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  active: '进行中',
  done: '已完成',
}

export const OBJECTIVE_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  active: 'ok',
  done: 'info',
}

export const OBJECTIVE_STATUS_OPTIONS = [
  { label: '草稿', value: 'draft' },
  { label: '进行中', value: 'active' },
  { label: '已完成', value: 'done' },
]

export const KR_TYPE_TEXT: Record<string, string> = {
  quantitative: '量化型',
  task_driven: '任务驱动型',
}

export const DIRECTION_TEXT: Record<string, string> = {
  positive: '越大越好',
  negative: '越小越好',
}

/** 进度来源的说明文案：让读者一眼明白这个百分比是怎么来的 */
export const PROGRESS_SOURCE_TEXT: Record<OkrProgressSource | string, string> = {
  quantitative: '公式计算',
  task_writeback: '任务回写',
  manual: '人工校准',
}

export const PROGRESS_SOURCE_HINT: Record<OkrProgressSource | string, string> = {
  quantitative: '按「当前值 / 目标值」的公式算出，更新当前值会自动重算。',
  task_writeback: '由关联任务的完成情况自动回写，不能手工填写。',
  manual: '由人工校准，每次调整都留有记录与原因。',
}

/** 量化型 KR 的自动进度（与后端 D-03 口径一致：正向 min(1, 当前/目标)、反向 min(1, 目标/当前)） */
export function autoProgressOf(kr: Pick<OkrKeyResultRow, 'krType' | 'currentValue' | 'targetValue' | 'direction'>): number | null {
  if (kr.krType !== 'quantitative') return null
  const target = Number(kr.targetValue ?? 0)
  const current = Number(kr.currentValue ?? 0)
  if (!Number.isFinite(target) || target <= 0) return null
  const ratio = kr.direction === 'negative' ? Math.min(1, target / Math.max(current, 0.0000001)) : Math.min(1, current / target)
  return Math.round(Math.min(Math.max(ratio, 0), 1) * 10000) / 100
}

/** 人工校准的允许区间（D-06.3：自动值 ±20 个百分点，并夹在 0–100 内） */
export function calibrationRangeOf(auto: number | null): { min: number; max: number } | null {
  if (auto === null) return null
  return { min: Math.max(0, Math.round((auto - 20) * 100) / 100), max: Math.min(100, Math.round((auto + 20) * 100) / 100) }
}
