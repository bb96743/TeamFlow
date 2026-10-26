import type { AiCitation, AiMessageRow } from '@/api/ai'

/* ============================================================
   M11 AI 助手页面展示口径
   ------------------------------------------------------------
   只做「后端字段 → 可读文案」的翻译与安全解析；
   不推断权限、不重新检索、不改写后端返回的拒绝原因。
   ============================================================ */

export const TOOL_LABEL: Record<string, string> = {
  'task.query': '查询任务',
  'perf.query': '查询绩效',
  'okr.query': '查询目标',
  'meeting.query': '查询会议',
  'knowledge.qa': '知识库检索',
  'report.generate': '生成报告草稿',
}

export const INTENT_LABEL: Record<string, string> = {
  task_query: '查询任务',
  perf_query: '查询绩效',
  okr_query: '查询目标',
  meeting_query: '查询会议',
  knowledge_qa: '知识库问答',
  daily_report: '生成日报草稿',
  weekly_report: '生成周报草稿',
  unknown: '未识别意图',
  llm: '大模型意图识别',
}

export const ENGINE_LABEL: Record<string, string> = {
  'fulltext-ngram': '知识库全文索引（ngram）',
  'fulltext-miss-like-fallback': '全文索引未命中，回退 LIKE',
  'like-short-keyword': '单字关键词，回退 LIKE',
  'like-fallback': 'LIKE 兜底检索',
}

export const REPORT_TYPE_LABEL: Record<string, string> = {
  daily: '日报',
  weekly: '周报',
}

export function reportTypeLabel(type?: string | null): string {
  if (!type) return '—'
  return REPORT_TYPE_LABEL[type] ?? type
}

export function engineLabel(engine?: string | null): string {
  if (!engine) return '—'
  return ENGINE_LABEL[engine] ?? engine
}

export function engineTone(engine?: string | null): string {
  return engine === 'fulltext-ngram' ? 'ok' : 'neutral'
}

export function toolLabel(name?: string | null): string {
  if (!name) return '—'
  return TOOL_LABEL[name] ?? name
}

export function intentText(code?: string | null, label?: string | null): string {
  if (label) return label
  if (!code) return '—'
  return INTENT_LABEL[code] ?? code
}

/** 运行模式：规则实现（默认）或显式启用的大模型路径；不夸大模型能力。 */
export function modeLabel(value?: string | null): string {
  const text = (value ?? '').toLowerCase()
  if (text.includes('llm') || text.includes('chatclient')) {
    return '大模型路径（已配置启用）'
  }
  return '规则意图识别（当前未启用大模型）'
}

export function parseJson<T>(text: string | null | undefined, fallback: T): T {
  if (!text) return fallback
  try {
    return JSON.parse(text) as T
  } catch {
    return fallback
  }
}

/** 会话消息的工具参数（后端存 JSON 字符串） */
export function messageToolParams(message: AiMessageRow): Record<string, unknown> | null {
  return parseJson<Record<string, unknown> | null>(message.toolParams, null)
}

/** 会话消息的引用（后端存 JSON 字符串） */
export function messageCitations(message: AiMessageRow): AiCitation[] {
  return parseJson<AiCitation[]>(message.citations, [])
}

/** 历史消息是否被拒绝执行：状态由后端判定，此处只做展示映射 */
export function messageToolAllowed(message: AiMessageRow): boolean {
  return message.status !== 'denied'
}

export function paramsText(params?: Record<string, unknown> | null): string {
  if (!params || !Object.keys(params).length) return ''
  return JSON.stringify(params, null, 2)
}

export function dateTimeText(value?: string | null): string {
  if (!value) return '—'
  const normalized = value.includes('T') ? value.replace('T', ' ').slice(0, 19) : value
  return normalized.length > 16 ? normalized.slice(0, 16) : normalized
}

export function shortTimeText(value?: string | null): string {
  if (!value) return ''
  const text = dateTimeText(value)
  return text.length > 10 ? text.slice(5) : text
}

export interface HighlightPart {
  text: string
  hit: boolean
}

/** 把返回的片段按问题词切分高亮；不改写片段内容，不重新检索。 */
export function highlightTerms(text?: string | null, terms: string[] = []): HighlightPart[] {
  const source = text ?? ''
  if (!source) return []
  const keywords = terms
    .map(term => term.trim())
    .filter(term => term.length >= 2)
    .sort((left, right) => right.length - left.length)
  if (!keywords.length) return [{ text: source, hit: false }]
  const parts: HighlightPart[] = []
  let cursor = 0
  while (cursor < source.length) {
    let nextIndex = -1
    let nextKeyword = ''
    for (const keyword of keywords) {
      const index = source.toLowerCase().indexOf(keyword.toLowerCase(), cursor)
      if (index !== -1 && (nextIndex === -1 || index < nextIndex)) {
        nextIndex = index
        nextKeyword = keyword
      }
    }
    if (nextIndex === -1) {
      parts.push({ text: source.slice(cursor), hit: false })
      break
    }
    if (nextIndex > cursor) parts.push({ text: source.slice(cursor, nextIndex), hit: false })
    parts.push({ text: source.slice(nextIndex, nextIndex + nextKeyword.length), hit: true })
    cursor = nextIndex + nextKeyword.length
  }
  return parts
}

/** 从问题中提取高亮词（按标点/空白切分，保留长度 ≥2 的词；单字问题退回原问题）。 */
export function questionTerms(question: string): string[] {
  const terms = question
    .split(/[，。？！、；：\s?,.;:!()（）]+/)
    .map(term => term.trim())
    .filter(term => term.length >= 2)
  if (terms.length) return terms
  const trimmed = question.trim()
  return trimmed ? [trimmed] : []
}
