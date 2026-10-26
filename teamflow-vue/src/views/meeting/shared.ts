/* M06 日程与会议 · 页面共用常量与小工具（只在本模块目录内使用） */

/** 把界面上的「yyyy-MM-dd HH:mm」或「yyyy-MM-dd」转成后端要求的 ISO-8601。 */
export function toIso(value: string | null | undefined, fallbackTime = '00:00:00'): string | null {
  const text = (value ?? '').trim()
  if (!text) return null
  const normalized = text.replace(/\//g, '-').replace(' ', 'T')
  if (normalized.includes('T')) {
    const [date, time] = normalized.split('T')
    const pieces = (time || '').split(':')
    const hh = (pieces[0] || '00').padStart(2, '0')
    const mm = (pieces[1] || '00').padStart(2, '0')
    const ss = (pieces[2] || '00').padStart(2, '0')
    return `${date}T${hh}:${mm}:${ss}`
  }
  return `${normalized}T${fallbackTime}`
}

/** 后端返回的 ISO/空格时间 → 界面展示（yyyy-MM-dd HH:mm）。 */
export function toDisplay(value: string | null | undefined): string {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 日程列表的日期区间参数：仅日期时补足到当天起止。 */
export function dayRangeParams(from: string, to: string): { fromTime?: string; toTime?: string } {
  const params: { fromTime?: string; toTime?: string } = {}
  const fromText = (from ?? '').trim()
  const toText = (to ?? '').trim()
  if (fromText) params.fromTime = fromText.length === 10 ? `${fromText} 00:00:00` : toDisplay(fromText) + ':00'
  if (toText) params.toTime = toText.length === 10 ? `${toText} 23:59:59` : toDisplay(toText) + ':59'
  return params
}

export const EVENT_TYPE_OPTIONS = [
  { label: '个人日程', value: 'personal' },
  { label: '团队日程', value: 'team' },
  { label: '会议同步', value: 'meeting' },
]
export const EVENT_TYPE_LABEL: Record<string, string> = {
  personal: '个人日程',
  team: '团队日程',
  meeting: '会议同步',
}
export const EVENT_TYPE_TONE: Record<string, string> = {
  personal: 'neutral',
  team: 'info',
  meeting: 'ok',
}
export const EVENT_STATUS_LABEL: Record<string, string> = { active: '有效', cancelled: '已取消' }

export const MEETING_STATUS_OPTIONS = [
  { label: '草稿', value: 'draft' },
  { label: '已排期', value: 'scheduled' },
  { label: '进行中', value: 'ongoing' },
  { label: '已结束', value: 'ended' },
  { label: '已取消', value: 'cancelled' },
]
export const MEETING_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  scheduled: '已排期',
  ongoing: '进行中',
  ended: '已结束',
  cancelled: '已取消',
}
export const MEETING_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  scheduled: 'info',
  ongoing: 'ok',
  ended: 'neutral',
  cancelled: 'danger',
}

export const ROOM_STATUS_OPTIONS = [
  { label: '可用', value: 'available' },
  { label: '停用', value: 'disabled' },
]
export const ROOM_STATUS_LABEL: Record<string, string> = { available: '可用', disabled: '停用' }

export const BOOKING_STATUS_LABEL: Record<string, string> = { booked: '已预定', cancelled: '已取消' }

export const TEMPLATE_TYPE_OPTIONS = [
  { label: '常规会议', value: 'normal' },
  { label: '目标校准会', value: 'okr_calibration' },
  { label: '复盘会', value: 'retro' },
]
export const TEMPLATE_TYPE_LABEL: Record<string, string> = {
  normal: '常规会议',
  okr_calibration: '目标校准会',
  retro: '复盘会',
}
export const TEMPLATE_STATUS_OPTIONS = [
  { label: '启用', value: 'active' },
  { label: '停用', value: 'disabled' },
]
export const TEMPLATE_STATUS_LABEL: Record<string, string> = { active: '启用', disabled: '停用' }

export const PARTICIPANT_ROLE_LABEL: Record<string, string> = {
  host: '主持人',
  required: '必选',
  optional: '可选',
}
export const RESPONSE_STATUS_LABEL: Record<string, string> = {
  pending: '待回复',
  accepted: '已接受',
  declined: '已拒绝',
}
