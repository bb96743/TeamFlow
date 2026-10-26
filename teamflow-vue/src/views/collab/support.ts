/* ============================================================
   M09 协作与消息页面共用的展示口径
   ------------------------------------------------------------
   只做「值与文案的对照」和日期格式化。
   未读数、已读状态、公告可见范围全部来自后端，前端不做推断；
   动态流的动作标签以接口返回的 actionLabel 为准，这里的对照表只用于筛选项。
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

/** 会话列表「最近一条消息」的时间：今天只显示时间，其余显示日期 */
export function shortTimeText(value?: string | null): string {
  const date = toDate(value)
  if (!date) return ''
  const now = new Date()
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return sameDay ? `${hour}:${minute}` : dateText(value)
}

export const CONVERSATION_TYPE_TEXT: Record<string, string> = {
  direct: '单聊',
  group: '群组',
}

/* ── 公告 ── */
export const ANNOUNCEMENT_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  archived: '已归档',
}

export const ANNOUNCEMENT_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  published: 'ok',
  archived: 'neutral',
}

export const ANNOUNCEMENT_STATUS_OPTIONS = Object.entries(ANNOUNCEMENT_STATUS_TEXT)
  .map(([value, label]) => ({ label, value }))

/* ── 动态流 ──
   动作取值来自后端事件投影（task_completed 等）；筛选项仅为文案对照，
   行内展示一律用接口返回的 actionLabel。 */
export const FEED_ACTION_OPTIONS = [
  { label: '完成', value: 'task_completed' },
  { label: '状态变更', value: 'task_status_changed' },
  { label: '删除', value: 'task_deleted' },
  { label: '目标关联变更', value: 'task_kr_changed' },
]

export const FEED_BIZ_TEXT: Record<string, string> = {
  task: '任务',
  objective: '目标',
  kr: '关键结果',
}
