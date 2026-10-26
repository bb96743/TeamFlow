import type { InsightMetricReadout, InsightMetricRow } from '@/api/insight'

/* ============================================================
   M13 页面共用的展示口径
   ------------------------------------------------------------
   只做「值与文案的对照」、日期格式化和口径四要素拼装，不含业务判断：
   指标能不能上线、看板能不能出数一律由后端闸门判定，前端只把后端
   返回的 status / missing / implemented 翻译成可读文案与语义色。
   ============================================================ */

function toDate(value?: string | null): Date | null {
  if (!value) return null
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const parsed = new Date(normalized)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

export function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

export function isIsoDate(value?: string | null): boolean {
  return Boolean(value && /^\d{4}-\d{2}-\d{2}$/.test(value))
}

export function dateText(value?: string | null): string {
  const date = toDate(value)
  return date ? isoDate(date) : '—'
}

export function dateTimeText(value?: string | null): string {
  const date = toDate(value)
  if (!date) return '—'
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${isoDate(date)} ${hour}:${minute}`
}

/** 比例类读数文案：空值显示破折号，避免把"没有数据"读成 0% */
export function percentText(value?: number | null, decimals = 1): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(decimals)}%`
}

/** 小时读数：保留两位小数 */
export function hoursText(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(2)} 小时`
}

/** 整数/小数数值文案：整数不补小数位 */
export function numberText(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  const num = Number(value)
  return Number.isInteger(num) ? String(num) : num.toFixed(2)
}

/** 看板指标读数：无数据（missing）统一显示「暂无数据」，绝不显示 0 */
export function readoutText(readout?: InsightMetricReadout | null): string {
  if (!readout || readout.missing || readout.value === null || readout.value === undefined) return '暂无数据'
  const unit = readout.unit ?? ''
  if (!unit) return numberText(readout.value)
  if (unit === '%') return percentText(readout.value)
  return `${Number(readout.value).toFixed(2)} ${unit}`
}

/* ---------------- 指标字典文案 ---------------- */

export const METRIC_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  online: '已上线',
  offline: '已下线',
}

export const METRIC_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  online: 'ok',
  offline: 'danger',
}

export const METRIC_CATEGORY_LABEL: Record<string, string> = {
  task: '任务统计',
  team: '团队效能',
  cross: '跨域指标',
}

export const REFRESH_FREQUENCY_LABEL: Record<string, string> = {
  realtime: '实时',
  '5min': '每 5 分钟',
  '1h': '每小时',
  daily: '每日',
}

export const METRIC_STATUS_OPTIONS = [
  { label: '草稿（不参与看板出数）', value: 'draft' },
  { label: '已上线（参与看板出数）', value: 'online' },
  { label: '已下线（停止出数）', value: 'offline' },
]

export const METRIC_CATEGORY_OPTIONS = [
  { label: '任务统计', value: 'task' },
  { label: '团队效能', value: 'team' },
  { label: '跨域指标（后续里程碑）', value: 'cross' },
]

export const REFRESH_FREQUENCY_OPTIONS = [
  { label: '实时', value: 'realtime' },
  { label: '每 5 分钟', value: '5min' },
  { label: '每小时', value: '1h' },
  { label: '每日', value: 'daily' },
]

/** 状态是否参与看板出数：只有 online 才出数（后端闸门，前端只表达） */
export function isOnline(status?: string | null): boolean {
  return status === 'online'
}

/** 与 M05 绩效归集同码同口径的三项指标（后端 V20 种子与 PerfMetricDictionary 逐字一致） */
export const PERF_ALIGNED_METRIC_CODES = ['kr_achievement', 'task_completion', 'time_compliance'] as const

export function isPerfAligned(metricCode?: string | null): boolean {
  return Boolean(metricCode && PERF_ALIGNED_METRIC_CODES.includes(metricCode as (typeof PERF_ALIGNED_METRIC_CODES)[number]))
}

/** 任务总览的核心指标展示顺序 */
export const TASK_CORE_METRIC_CODES = [
  'task_completion',
  'time_compliance',
  'kr_achievement',
  'task_overdue_rate',
] as const

/** 团队负载的指标展示顺序 */
export const TEAM_METRIC_CODES = ['team_member_load', 'team_response_time', 'team_load_balance'] as const

/* ---------------- 口径四要素 ---------------- */

export interface MetricElements {
  sourceModule?: string | null
  formula?: string | null
  timeGrain?: string | null
  missingPolicy?: string | null
}

/** 指标字典四要素：数据来源模块 / 计算公式 / 取数时间范围 / 缺失值处理 */
export function metricElementRows(metric: MetricElements): Array<{ label: string; value: string }> {
  return [
    { label: '数据来源模块', value: metric.sourceModule || '—' },
    { label: '计算公式', value: metric.formula || '—' },
    { label: '取数时间范围', value: metric.timeGrain || '—' },
    { label: '缺失值处理', value: metric.missingPolicy || '—' },
  ]
}

/** 指标字典行的展示值：优先用后端装饰文案，缺失时回落本地映射 */
export function metricStatusLabel(metric: Pick<InsightMetricRow, 'status' | 'statusLabel'>): string {
  return metric.statusLabel || METRIC_STATUS_LABEL[metric.status] || metric.status
}

export function metricCategoryLabel(metric: Pick<InsightMetricRow, 'metricCategory' | 'categoryLabel'>): string {
  return metric.categoryLabel || METRIC_CATEGORY_LABEL[metric.metricCategory] || metric.metricCategory
}

export function refreshFrequencyLabel(metric: Pick<InsightMetricRow, 'refreshFrequency' | 'refreshFrequencyLabel'>): string {
  return metric.refreshFrequencyLabel || REFRESH_FREQUENCY_LABEL[metric.refreshFrequency] || metric.refreshFrequency
}

/* ---------------- 统计区间快捷选择 ---------------- */

export type InsightRangePreset = '7d' | '30d' | '90d' | 'quarter'

export const RANGE_PRESETS: Array<{ label: string; value: InsightRangePreset }> = [
  { label: '近 7 天', value: '7d' },
  { label: '近 30 天', value: '30d' },
  { label: '近 90 天', value: '90d' },
  { label: '本季度', value: 'quarter' },
]

/** 按快捷选项算出 yyyy-MM-dd 起止日期（含首尾；时区取浏览器本地，与后端 +08:00 一致） */
export function rangeOfPreset(preset: InsightRangePreset, today = new Date()): { startDate: string; endDate: string } {
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  if (preset === 'quarter') {
    const startMonth = Math.floor(end.getMonth() / 3) * 3
    return { startDate: isoDate(new Date(end.getFullYear(), startMonth, 1)), endDate: isoDate(end) }
  }
  const days = preset === '7d' ? 7 : preset === '90d' ? 90 : 30
  const start = new Date(end.getFullYear(), end.getMonth(), end.getDate() - (days - 1))
  return { startDate: isoDate(start), endDate: isoDate(end) }
}

/* ---------------- 负载均衡 ---------------- */

export const LOAD_LEVEL_TONE: Record<string, string> = {
  balanced: 'ok',
  moderate: 'info',
  unbalanced: 'danger',
}

export function loadLevelTone(level?: string | null): string {
  return (level && LOAD_LEVEL_TONE[level]) || 'neutral'
}
