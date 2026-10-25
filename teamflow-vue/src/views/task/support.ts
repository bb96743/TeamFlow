import type { TaskStatusMachine } from '@/api/task'

/* ============================================================
   M03 页面共用的展示口径
   ------------------------------------------------------------
   任务状态的**中文标签与允许迁移**一律来自后端
   `GET /api/task/flow/status-machine`（TaskStatusMachine 是唯一来源），
   本文件只放与流程无关的固定对照：优先级、流程实例与审批待办的展示文案。
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

export const PRIORITY_TEXT: Record<string, string> = {
  low: '低',
  normal: '普通',
  high: '高',
  urgent: '紧急',
}

export const PRIORITY_TONE: Record<string, string> = {
  low: 'neutral',
  normal: 'neutral',
  high: 'danger',
  urgent: 'danger',
}

export const PRIORITY_OPTIONS = [
  { label: '低', value: 'low' },
  { label: '普通', value: 'normal' },
  { label: '高', value: 'high' },
  { label: '紧急', value: 'urgent' },
]

export const INSTANCE_TYPE_TEXT: Record<string, string> = {
  status: '状态流转',
  approval: '审批',
}

export const INSTANCE_STATUS_TEXT: Record<string, string> = {
  running: '进行中',
  completed: '已完成',
  terminated: '已终止',
}

export const INSTANCE_STATUS_TONE: Record<string, string> = {
  running: 'info',
  completed: 'ok',
  terminated: 'danger',
}

export const FLOW_TASK_STATUS_TEXT: Record<string, string> = {
  pending: '待处理',
  approved: '已通过',
  rejected: '已驳回',
}

export const FLOW_TASK_STATUS_TONE: Record<string, string> = {
  pending: 'info',
  approved: 'ok',
  rejected: 'danger',
}

export const BUSINESS_TYPE_TEXT: Record<string, string> = {
  task_status: '任务状态',
  task_approve: '任务审批',
}

/** 状态码 → 中文标签（取自后端状态机） */
export function statusLabelOf(machine: TaskStatusMachine | null, code?: string | null): string {
  if (!code) return '—'
  const state = machine?.states.find(item => item.code === code)
  return state?.label ?? code
}

/** 某状态允许迁移到的目标状态（后端唯一来源；前端不做任何补全） */
export function allowedTargetsOf(machine: TaskStatusMachine | null, from?: string | null): string[] {
  if (!from) return []
  return machine?.transitions[from] ?? []
}

/** 流程定义的节点清单（后端存 nodes_json，这里只用于展示节点名称与数量） */
export function parseNodes(nodesJson?: string | null): Array<{ key?: string; name?: string }> {
  if (!nodesJson) return []
  try {
    const parsed = JSON.parse(nodesJson)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}
