import { httpClient, type HttpClient, type QueryParams } from './http.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M11 AI 助手接口（/api/ai）
   ------------------------------------------------------------
   与后端 3 个 Controller 对应：
     AiChatController   /chat · /sessions · /sessions/{id}/messages
     AiKbQaController   /kb/qa
     AiReportController /reports（daily / weekly / 列表 / 详情 / 编辑）
   说明：后端默认规则意图识别；回答中的 executor=rule，真实大模型路径仅在配置模型时启用。
   ============================================================ */

export interface AiIntentInfo {
  code: string
  label?: string | null
  reason?: string | null
  params?: Record<string, unknown> | null
  confidence?: number | null
}

export interface AiToolInfo {
  name: string
  params?: Record<string, unknown> | null
  allowed: boolean
  permissionCode?: string | null
  summary?: string | null
}

export interface AiCitation {
  pageId: number
  pageTitle: string
  pageType?: string | null
  spaceName?: string | null
  snippet?: string | null
  score?: number | null
  offset?: number | null
}

export interface AiChatResponse {
  sessionId: number
  messageId: number
  answer: string
  intent: AiIntentInfo
  tool?: AiToolInfo | null
  citations: AiCitation[]
  status: string
  executor?: string | null
  elapsedMs?: number | null
}

export interface AiSessionRow {
  id: number
  sessionTitle?: string | null
  sessionType?: string | null
  ownerId?: number
  ownerName?: string | null
  status?: string | null
  messageCount?: number
  lastMessageTime?: string | null
  createTime?: string | null
}

export interface AiMessageRow {
  id: number
  sessionId: number
  role: string
  content: string
  intentCode?: string | null
  intentLabel?: string | null
  toolName?: string | null
  /** 后端以 JSON 字符串存储，页面需安全解析后展示 */
  toolParams?: string | null
  toolResult?: string | null
  citations?: string | null
  modelName?: string | null
  status?: string | null
  elapsedMs?: number | null
  createTime?: string | null
}

export interface AiQaResponse {
  sessionId: number
  messageId: number
  answer: string
  citations: AiCitation[]
  engine?: string | null
  elapsedMs?: number | null
  total?: number | null
  status?: string | null
  tool?: AiToolInfo | null
}

export interface AiReportRow {
  id: number
  reportType: string
  reportDate?: string | null
  periodStart?: string | null
  periodEnd?: string | null
  reportTitle: string
  status: string
  ownerId?: number
  ownerName?: string | null
  updateTime?: string | null
  createTime?: string | null
}

export interface AiReportDetail extends AiReportRow {
  content: string
  sourceSummary?: string | null
}

export interface AiChatRequest {
  message: string
  sessionId?: number | null
}

export interface AiQaRequest {
  question: string
  spaceId?: number | null
  limit?: number | null
}

export interface AiReportUpdateRequest {
  reportTitle?: string
  content?: string
}

export interface AiApi {
  chat: (body: AiChatRequest) => Promise<AiChatResponse>
  sessions: (query?: QueryParams) => Promise<PageResult<AiSessionRow>>
  messages: (sessionId: number) => Promise<AiMessageRow[]>
  qa: (body: AiQaRequest) => Promise<AiQaResponse>
  reports: {
    list: (query?: QueryParams) => Promise<PageResult<AiReportRow>>
    daily: (body?: Record<string, never>) => Promise<{ id: number }>
    weekly: (body?: Record<string, never>) => Promise<{ id: number }>
    detail: (id: number) => Promise<AiReportDetail>
    update: (id: number, body: AiReportUpdateRequest) => Promise<void>
  }
}

export function createAiApi(client: HttpClient): AiApi {
  return {
    chat: body => client.post<AiChatResponse>('/ai/chat', body),
    sessions: query => client.get<PageResult<AiSessionRow>>('/ai/sessions', query),
    messages: sessionId => client.get<AiMessageRow[]>(`/ai/sessions/${sessionId}/messages`),
    qa: body => client.post<AiQaResponse>('/ai/kb/qa', body),
    reports: {
      list: query => client.get<PageResult<AiReportRow>>('/ai/reports', query),
      daily: body => client.post<{ id: number }>('/ai/reports/daily', body ?? {}),
      weekly: body => client.post<{ id: number }>('/ai/reports/weekly', body ?? {}),
      detail: id => client.get<AiReportDetail>(`/ai/reports/${id}`),
      update: (id, body) => client.put<void>(`/ai/reports/${id}`, body),
    },
  }
}

export const aiApi = createAiApi(httpClient)
