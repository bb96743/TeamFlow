import type { AppealStatus, CycleStatus, ItemCategory, ResultStatus } from '@/api/perf'

/* M05 绩效页面共用的中文文案与色调映射。
   状态标签只做展示，真正的状态流转合法性由后端状态机判定。 */

export const CYCLE_STATUS_LABEL: Record<CycleStatus, string> = {
  draft: '草稿',
  active: '进行中',
  scoring: '评分中',
  archived: '已归档',
  locked: '已锁定',
}

/** Tag 只支持 ok / info / danger / neutral 四种语义色（DESIGN §四） */
export const CYCLE_STATUS_TONE: Record<CycleStatus, string> = {
  draft: 'neutral',
  active: 'info',
  scoring: 'info',
  archived: 'ok',
  locked: 'danger',
}

export const RESULT_STATUS_LABEL: Record<ResultStatus, string> = {
  draft: '草稿',
  archived: '已归档',
  locked: '已锁定',
}

export const APPEAL_STATUS_LABEL: Record<AppealStatus, string> = {
  pending: '待处理',
  approved: '已通过',
  rejected: '已驳回',
}

export const APPEAL_STATUS_TONE: Record<AppealStatus, string> = {
  pending: 'info',
  approved: 'ok',
  rejected: 'danger',
}

export const RATER_LABEL: Record<string, string> = {
  system: '系统归集',
  self: '自评',
  manager: '上级评',
}

export const CATEGORY_LABEL: Record<ItemCategory, string> = {
  collectable: '可归集',
  subjective: '主观',
}

export const CYCLE_TYPE_LABEL: Record<string, string> = {
  quarter: '季度',
  month: '月度',
}

export const TASK_STATUS_LABEL: Record<string, string> = {
  pool: '待认领',
  todo: '待办',
  in_progress: '进行中',
  done: '已完成',
  closed: '已关闭',
}

export const PROGRESS_SOURCE_LABEL: Record<string, string> = {
  quantitative: '量化公式',
  task_writeback: '任务回写',
  manual: '人工校准',
}

export const ITEM_CATEGORY_OPTIONS: Array<{ label: string; value: ItemCategory }> = [
  { label: '可归集（系统计算，只读）', value: 'collectable' },
  { label: '主观（人工评分，必填评语）', value: 'subjective' },
]

export const CYCLE_STATUS_OPTIONS = Object.entries(CYCLE_STATUS_LABEL).map(([value, label]) => ({ label, value }))

export const APPEAL_STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  ...Object.entries(APPEAL_STATUS_LABEL).map(([value, label]) => ({ label, value })),
]

export function cycleStatusLabel(status?: string | null) {
  if (!status) return '—'
  return CYCLE_STATUS_LABEL[status as CycleStatus] ?? status
}

export function cycleStatusTone(status?: string | null) {
  if (!status) return 'neutral'
  return CYCLE_STATUS_TONE[status as CycleStatus] ?? 'neutral'
}

export function resultStatusLabel(status?: string | null) {
  if (!status) return '—'
  return RESULT_STATUS_LABEL[status as ResultStatus] ?? status
}

export function appealStatusLabel(status?: string | null) {
  if (!status) return '—'
  return APPEAL_STATUS_LABEL[status as AppealStatus] ?? status
}

export function appealStatusTone(status?: string | null) {
  if (!status) return 'neutral'
  return APPEAL_STATUS_TONE[status as AppealStatus] ?? 'neutral'
}

export function categoryLabel(category?: string | null) {
  if (!category) return '—'
  return CATEGORY_LABEL[category as ItemCategory] ?? category
}

export function cycleTypeLabel(type?: string | null) {
  if (!type) return '—'
  return CYCLE_TYPE_LABEL[type] ?? type
}

export function taskStatusLabel(status?: string | null) {
  if (!status) return '—'
  return TASK_STATUS_LABEL[status] ?? status
}

export function progressSourceLabel(source?: string | null) {
  if (!source) return '—'
  return PROGRESS_SOURCE_LABEL[source] ?? source
}

/** 分值统一保留两位小数展示；空值显示为破折号，避免编造 0 分 */
export function scoreText(value?: number | null, suffix = '') {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(2)}${suffix}`
}

export function percentText(value?: number | null) {
  return scoreText(value, '%')
}
