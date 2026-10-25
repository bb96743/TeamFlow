import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M10 个人效率 · 接口层
   ------------------------------------------------------------
   收件箱、工时、复盘、效能画像四组接口。
   收件箱「处理」是 R3 强约束的入口：后端在回写原模块失败时返回 400 与原文提示，
   页面必须原样展示，不能改写为通用错误。
   ============================================================ */

export type TodoStatus = 'open' | 'handled' | 'ignored'

export interface TodoRow {
  id: number
  bizType: string
  bizId: number
  actionType: string
  title: string
  actionUrl: string
  dueTime?: string | null
  priority: string
  status: TodoStatus
  handleTime?: string | null
  handleResult?: string | null
  createTime?: string
  statusLabel?: string
  priorityLabel?: string
  /** 该业务类型是否已登记回写处理者；未登记时只能回源到原模块处理 */
  handlerRegistered?: boolean
}

export interface TodoBizTypeRow {
  bizType: string
  handlerRegistered: boolean
  reserved: boolean
}

export interface TodoBizTypeInfo {
  knownBizTypes: TodoBizTypeRow[]
  registeredHandlers: Record<string, string>
}

export interface TimeLogRow {
  id: number
  taskId: number
  workDate: string
  startTime?: string | null
  endTime?: string | null
  durationMinutes: number
  description?: string | null
  createTime?: string
}

export interface TimeLogWriteRequest {
  taskId: number | null
  workDate?: string
  startTime?: string
  endTime?: string
  durationMinutes: number | null
  description?: string
}

export interface TimeSummaryRow {
  taskId: number
  totalMinutes: number
  logCount: number
}

export type ReviewType = 'day' | 'week' | 'month' | 'custom'

export interface ReviewRow {
  id: number
  reviewType: ReviewType
  reviewTitle: string
  periodStart?: string | null
  periodEnd?: string | null
  content?: string | null
  tags?: string | null
  visibility?: string
  kbPageId?: number | null
  createTime?: string
}

export interface ReviewWriteRequest {
  reviewType?: ReviewType
  reviewTitle?: string
  periodStart?: string
  periodEnd?: string
  content?: string
  tags?: string
}

export interface PersonalProfile {
  userId: number
  generatedAt: string
  readOnly: boolean
  sourceTables: string[]
  todoThroughput: {
    received: number
    open: number
    handled: number
    ignored: number
    avgHandleMinutes: number | null
  }
  byBizType: Array<{ bizType: string; received: number; handled: number; avgHandleMinutes: number | null }>
  workTime: {
    totalMinutes: number
    taskCount: number
    logCount: number
    topTasks: Array<{ taskId: number; totalMinutes: number }>
  }
  focus: { sessionCount: number; totalMinutes: number }
  reviewCount: number
}

/** 我的任务（工时必须选择任务，选项来自任务是本人名下的任务） */
export interface MyTaskOption {
  id: number
  taskTitle: string
  status: string
  statusLabel?: string
}

export function createPersonalApi(client: HttpClient) {
  return {
    todos: {
      list: (query?: QueryParams) => client.get<PageResult<TodoRow>>('/personal/todos', query),
      bizTypes: () => client.get<TodoBizTypeInfo>('/personal/todos/biz-types'),
      handle: (id: number, action: 'complete' | 'ignore') =>
        client.post<TodoRow>(`/personal/todos/${id}/handle`, { action }),
    },
    timeLogs: {
      list: (query?: QueryParams) => client.get<PageResult<TimeLogRow>>('/personal/time-logs', query),
      summary: (query?: QueryParams) => client.get<TimeSummaryRow[]>('/personal/time-logs/summary', query),
      create: (body: TimeLogWriteRequest) => client.post<IdResponse>('/personal/time-logs', body),
      update: (id: number, body: TimeLogWriteRequest) => client.put<void>(`/personal/time-logs/${id}`, body),
      remove: (id: number) => client.delete<void>(`/personal/time-logs/${id}`),
    },
    reviews: {
      list: (query?: QueryParams) => client.get<PageResult<ReviewRow>>('/personal/reviews', query),
      detail: (id: number) => client.get<ReviewRow>(`/personal/reviews/${id}`),
      create: (body: ReviewWriteRequest) => client.post<IdResponse>('/personal/reviews', body),
      update: (id: number, body: ReviewWriteRequest) => client.put<void>(`/personal/reviews/${id}`, body),
      remove: (id: number) => client.delete<void>(`/personal/reviews/${id}`),
    },
    profile: {
      get: () => client.get<PersonalProfile>('/personal/profile'),
    },
    /** 工时选择任务：读取本人任务（M03 的公开只读接口），不复制任务数据 */
    myTasks: () => client.get<PageResult<MyTaskOption>>('/task/tasks/my', { pageNum: 1, pageSize: 100 }),
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const personalApi = createPersonalApi(httpClient)
