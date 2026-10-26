import type { KnowledgeNodeRow, KnowledgePageStatus, KnowledgePageType, KnowledgeSearchEngine } from '@/api/knowledge'

/* ============================================================
   M07 知识与文档页面共用的展示口径
   ------------------------------------------------------------
   只做「值与文案的对照」和日期/体积格式化，不含权限判断：
   页面可见性、能否编辑/管理，一律由后端返回的 access 字段决定，
   前端只把后端结论翻译成可读文案与语义色。
   ============================================================ */

export const PAGE_TYPE_LABEL: Record<string, string> = {
  wiki: '知识页面',
  sop: '流程说明',
  decision: '决策记录',
  minutes: '会议纪要',
  retro: '项目复盘',
}

export const PAGE_TYPE_OPTIONS = [
  { label: '知识页面（通用）', value: 'wiki' },
  { label: '流程说明（SOP）', value: 'sop' },
  { label: '决策记录', value: 'decision' },
  { label: '会议纪要（由会议归档）', value: 'minutes' },
  { label: '项目复盘（由项目归档）', value: 'retro' },
]

export const PAGE_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  archived: '已归档',
}

export const PAGE_STATUS_TONE: Record<string, string> = {
  draft: 'neutral',
  published: 'ok',
  archived: 'info',
}

export const PAGE_STATUS_OPTIONS = [
  { label: '草稿（仅可编辑者可见）', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '已归档', value: 'archived' },
]

export const VISIBILITY_LABEL: Record<string, string> = {
  inherit: '继承上级',
  internal: '已登录可读',
  restricted: '仅授权可见',
}

export const SPACE_VISIBILITY_OPTIONS = [
  { label: '已登录可读（internal）', value: 'internal' },
  { label: '仅授权可见（restricted）', value: 'restricted' },
]

export const NODE_VISIBILITY_OPTIONS = [
  { label: '继承上级（默认）', value: 'inherit' },
  { label: '已登录可读（覆盖）', value: 'internal' },
  { label: '仅授权可见（覆盖）', value: 'restricted' },
]

export const PAGE_VISIBILITY_OPTIONS = NODE_VISIBILITY_OPTIONS

export const SPACE_STATUS_OPTIONS = [
  { label: '正常', value: 'active' },
  { label: '已归档', value: 'archived' },
]

export const SPACE_STATUS_LABEL: Record<string, string> = {
  active: '正常',
  archived: '已归档',
}

/** 检索引擎文案：单字回落 LIKE 是后端的正常设计，不要当成异常 */
export const ENGINE_LABEL: Record<string, string> = {
  'fulltext-ngram': '全文索引（ngram 分词）',
  'fulltext-miss-like-fallback': '全文索引未命中，回退 LIKE',
  'like-short-keyword': '单字关键词，回退 LIKE',
  'like-fallback': 'LIKE 兜底',
}

export const ENGINE_HINT =
  '中文全文索引最小分词长度为 2 个字：单字关键词会自动回退到 LIKE 检索，这是后端设计，不是异常。'

export const PAGE_TYPE_TEXT: Record<KnowledgePageType | string, string> = PAGE_TYPE_LABEL

export function pageTypeLabel(type?: string | null): string {
  if (!type) return '—'
  return PAGE_TYPE_LABEL[type] ?? type
}

export function pageStatusLabel(status?: string | null): string {
  if (!status) return '—'
  return PAGE_STATUS_LABEL[status as KnowledgePageStatus] ?? status
}

export function pageStatusTone(status?: string | null): string {
  if (!status) return 'neutral'
  return PAGE_STATUS_TONE[status] ?? 'neutral'
}

export function visibilityLabel(visibility?: string | null): string {
  if (!visibility) return '—'
  return VISIBILITY_LABEL[visibility] ?? visibility
}

export function engineTone(engine?: KnowledgeSearchEngine | string | null): string {
  return engine === 'fulltext-ngram' ? 'ok' : 'neutral'
}

export function engineLabel(engine?: KnowledgeSearchEngine | string | null): string {
  if (!engine) return '—'
  return ENGINE_LABEL[engine] ?? engine
}

export function spaceStatusLabel(status?: string | null): string {
  if (!status) return '—'
  return SPACE_STATUS_LABEL[status] ?? status
}

/* ---------------- 日期 / 体积 / 高亮 ---------------- */

function toDate(value?: string | null): Date | null {
  if (!value) return null
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const parsed = new Date(normalized)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function dateTimeText(value?: string | null): string {
  const date = toDate(value)
  if (!date) return '—'
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function dateText(value?: string | null): string {
  const text = dateTimeText(value)
  return text === '—' ? text : text.slice(0, 10)
}

export function fileSizeText(size?: number | null): string {
  if (size === null || size === undefined || Number.isNaN(Number(size))) return '—'
  const value = Number(size)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(2)} MB`
}

export interface HighlightPart {
  text: string
  hit: boolean
}

/** 把命中片段按关键词切成「普通/命中」段，供模板用文本插值渲染（不引入 Markdown/HTML 渲染库）。 */
export function highlightParts(text?: string | null, keyword?: string | null): HighlightPart[] {
  const source = text ?? ''
  const key = (keyword ?? '').trim()
  if (!source) return []
  if (!key) return [{ text: source, hit: false }]
  const parts: HighlightPart[] = []
  const lowerSource = source.toLowerCase()
  const lowerKey = key.toLowerCase()
  let cursor = 0
  while (cursor < source.length) {
    const hit = lowerSource.indexOf(lowerKey, cursor)
    if (hit === -1) {
      parts.push({ text: source.slice(cursor), hit: false })
      break
    }
    if (hit > cursor) parts.push({ text: source.slice(cursor, hit), hit: false })
    parts.push({ text: source.slice(hit, hit + key.length), hit: true })
    cursor = hit + key.length
  }
  return parts
}

export interface FlatNode {
  node: KnowledgeNodeRow
  depth: number
}

/** 空间树 → 扁平目录列表（用于「归属目录」下拉；层级用缩进提示）。 */
export function flattenNodes(nodes: KnowledgeNodeRow[], depth = 0, acc: FlatNode[] = []): FlatNode[] {
  for (const node of nodes) {
    acc.push({ node, depth })
    if (node.children?.length) flattenNodes(node.children, depth + 1, acc)
  }
  return acc
}

export function nodeSelectOptions(nodes: KnowledgeNodeRow[]): Array<{ label: string; value: number; hint?: string }> {
  return [
    { label: '空间根（不放入目录）', value: 0 },
    ...flattenNodes(nodes).map(item => ({
      label: `${'　'.repeat(item.depth)}${item.node.nodeName}`,
      value: item.node.id,
      hint: visibilityLabel(item.node.visibility),
    })),
  ]
}
