import { ApiError, httpClient, type ApiResponse, type HttpClient, type QueryParams } from './http.ts'
import { authSession } from './session.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M07 知识与文档接口（/api/knowledge）
   ------------------------------------------------------------
   与后端 5 个 Controller 一一对应：
     KnowledgeCatalogController  /spaces · /spaces/{id}/tree · /spaces/{spaceId}/nodes · /nodes · /acls
     KnowledgePageController     /pages · /pages/{id}/versions · rollback
     KnowledgeFileController     /files（附件引用与鉴权下载）
     KnowledgeSearchController   /search
   字段名与后端返回的 Map 键一致；权限判定（含 access.reason）完全由后端计算，前端不自行推断。
   ============================================================ */

export type KnowledgePageType = 'wiki' | 'sop' | 'decision' | 'minutes' | 'retro'
export type KnowledgeVisibility = 'inherit' | 'internal' | 'restricted'
export type KnowledgePageStatus = 'draft' | 'published' | 'archived'
export type KnowledgeSpaceStatus = 'active' | 'archived'
/** 检索实际使用的引擎（后端返回） */
export type KnowledgeSearchEngine = 'fulltext-ngram' | 'fulltext-miss-like-fallback' | 'like-short-keyword' | 'like-fallback'

/** 后端计算的最终权限（含判定原因，便于回答「为什么能看到/能编辑」） */
export interface KnowledgeAccess {
  level: number
  levelLabel: string
  reason: string
  canRead: boolean
  canWrite: boolean
  canManage: boolean
}

export interface KnowledgeSpaceRow {
  id: number
  spaceName: string
  spaceCode: string
  spaceDesc?: string | null
  visibility: KnowledgeVisibility | string
  ownerId: number
  ownerName?: string | null
  status: KnowledgeSpaceStatus | string
  sort?: number
  remark?: string | null
  createTime?: string
  updateTime?: string
  nodeCount?: number
  pageCount?: number
  /** 详情接口返回 */
  access?: KnowledgeAccess
}

export interface KnowledgeSpaceWriteRequest {
  spaceName?: string
  spaceCode?: string
  spaceDesc?: string | null
  visibility?: string
  status?: string
  sort?: number | null
  remark?: string | null
}

export interface KnowledgeNodeRow {
  id: number
  spaceId: number
  parentId: number
  nodeName: string
  nodeDesc?: string | null
  visibility: KnowledgeVisibility | string
  sort?: number
  remark?: string | null
  createTime?: string
  updateTime?: string
  access?: KnowledgeAccess
  /** 空间树接口返回 */
  children?: KnowledgeNodeRow[]
  pages?: KnowledgePageRow[]
}

export interface KnowledgeNodeWriteRequest {
  spaceId?: number | null
  parentId?: number | null
  nodeName?: string
  nodeDesc?: string | null
  visibility?: string
  sort?: number | null
  remark?: string | null
}

export interface KnowledgePageRow {
  id: number
  spaceId: number
  spaceName?: string | null
  nodeId: number
  nodeName?: string | null
  pageTitle: string
  pageType: KnowledgePageType | string
  tags?: string | null
  summary?: string | null
  content?: string
  contentFormat?: string
  visibility: KnowledgeVisibility | string
  status: KnowledgePageStatus | string
  currentVersion: number
  ownerId: number
  ownerName?: string | null
  sourceModule?: string | null
  sourceBizType?: string | null
  sourceBizId?: number | null
  sort?: number
  remark?: string | null
  createTime?: string
  updateTime?: string
  access?: KnowledgeAccess
}

export interface KnowledgePageWriteRequest {
  spaceId?: number | null
  nodeId?: number | null
  pageTitle?: string
  pageType?: string
  tags?: string | null
  summary?: string | null
  content?: string
  contentFormat?: string
  visibility?: string
  status?: string
  sort?: number | null
  remark?: string | null
  changeSummary?: string | null
}

export interface KnowledgeVersionRow {
  id: number
  pageId: number
  versionNo: number
  pageTitle: string
  content?: string
  contentFormat?: string
  tags?: string | null
  changeSummary?: string | null
  editorId?: number | null
  editorName?: string | null
  createTime?: string
}

export interface KnowledgeFileRow {
  id: number
  spaceId: number
  pageId?: number | null
  fileId: number
  fileTitle: string
  category: string
  sort?: number
  remark?: string | null
  createBy?: string | null
  createTime?: string
}

export interface KnowledgeFileWriteRequest {
  spaceId?: number | null
  pageId?: number | null
  fileId?: number
  fileTitle?: string
  category?: string
  sort?: number | null
  remark?: string | null
}

export interface KnowledgeTree {
  space: KnowledgeSpaceRow
  nodeCount: number
  pageCount: number
  nodes: KnowledgeNodeRow[]
  pages: KnowledgePageRow[]
}

export interface KnowledgeSearchHit {
  pageId: number
  pageTitle: string
  pageType: KnowledgePageType | string
  spaceId: number
  spaceName?: string | null
  nodeId?: number | null
  tags?: string | null
  updateTime?: string
  score?: number | null
  snippet?: string | null
}

export interface KnowledgeSearchResult {
  keyword: string
  spaceId?: number | null
  pageType?: string | null
  /** fulltext-ngram / fulltext-miss-like-fallback / like-short-keyword / like-fallback */
  engine: KnowledgeSearchEngine | string
  elapsedMs: number
  total: number
  results: KnowledgeSearchHit[]
  note?: string
}

/** 平台统一上传响应（G-1）：拿到 id 后再挂到知识页面 */
export interface KnowledgeUploadResult {
  id: number
  originalName: string
  size: number
  contentType: string
  bizType?: string | null
  bizId?: number | null
  url: string
}

export interface KnowledgeApi {
  spaces: {
    list: (query?: QueryParams) => Promise<PageResult<KnowledgeSpaceRow>>
    detail: (id: number) => Promise<KnowledgeSpaceRow>
    tree: (id: number) => Promise<KnowledgeTree>
    nodes: (spaceId: number) => Promise<KnowledgeNodeRow[]>
    create: (body: KnowledgeSpaceWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: KnowledgeSpaceWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
  }
  nodes: {
    detail: (id: number) => Promise<KnowledgeNodeRow>
    create: (body: KnowledgeNodeWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: KnowledgeNodeWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
  }
  pages: {
    list: (query?: QueryParams) => Promise<PageResult<KnowledgePageRow>>
    detail: (id: number) => Promise<KnowledgePageRow>
    create: (body: KnowledgePageWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: KnowledgePageWriteRequest) => Promise<{ id: number; versionNo: number }>
    remove: (id: number) => Promise<void>
    versions: (id: number) => Promise<KnowledgeVersionRow[]>
    versionDetail: (id: number, versionNo: number) => Promise<KnowledgeVersionRow>
    rollback: (id: number, versionNo: number, body?: { changeSummary?: string }) => Promise<{ id: number; versionNo: number; rolledBackFrom: number }>
  }
  files: {
    list: (query: { spaceId?: number; pageId?: number }) => Promise<KnowledgeFileRow[]>
    attach: (body: KnowledgeFileWriteRequest) => Promise<{ id: number }>
    remove: (id: number) => Promise<void>
    /** 鉴权下载：走知识附件接口，不拼静态地址 */
    download: (id: number) => Promise<Blob>
    /** 平台统一上传（G-1）：multipart 直传，返回 fileId 供挂载 */
    upload: (file: File) => Promise<KnowledgeUploadResult>
  }
  search: (params: { keyword: string; spaceId?: number | null; pageType?: string; limit?: number }) => Promise<KnowledgeSearchResult>
}

/** 与 api/http.ts 相同来源的 API 基地址（multipart 上传需要 fetch 直传） */
function apiBaseUrl(): string {
  return ((import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL) || '/api'
}

export function createKnowledgeApi(client: HttpClient): KnowledgeApi {
  return {
    spaces: {
      list: query => client.get<PageResult<KnowledgeSpaceRow>>('/knowledge/spaces', query),
      detail: id => client.get<KnowledgeSpaceRow>(`/knowledge/spaces/${id}`),
      tree: id => client.get<KnowledgeTree>(`/knowledge/spaces/${id}/tree`),
      nodes: spaceId => client.get<KnowledgeNodeRow[]>(`/knowledge/spaces/${spaceId}/nodes`),
      create: body => client.post<{ id: number }>('/knowledge/spaces', body),
      update: (id, body) => client.put<void>(`/knowledge/spaces/${id}`, body),
      remove: id => client.delete<void>(`/knowledge/spaces/${id}`),
    },
    nodes: {
      detail: id => client.get<KnowledgeNodeRow>(`/knowledge/nodes/${id}`),
      create: body => client.post<{ id: number }>('/knowledge/nodes', body),
      update: (id, body) => client.put<void>(`/knowledge/nodes/${id}`, body),
      remove: id => client.delete<void>(`/knowledge/nodes/${id}`),
    },
    pages: {
      list: query => client.get<PageResult<KnowledgePageRow>>('/knowledge/pages', query),
      detail: id => client.get<KnowledgePageRow>(`/knowledge/pages/${id}`),
      create: body => client.post<{ id: number }>('/knowledge/pages', body),
      update: (id, body) => client.put<{ id: number; versionNo: number }>(`/knowledge/pages/${id}`, body),
      remove: id => client.delete<void>(`/knowledge/pages/${id}`),
      versions: id => client.get<KnowledgeVersionRow[]>(`/knowledge/pages/${id}/versions`),
      versionDetail: (id, versionNo) => client.get<KnowledgeVersionRow>(`/knowledge/pages/${id}/versions/${versionNo}`),
      rollback: (id, versionNo, body) =>
        client.post<{ id: number; versionNo: number; rolledBackFrom: number }>(
          `/knowledge/pages/${id}/versions/${versionNo}/rollback`,
          body ?? {},
        ),
    },
    files: {
      list: query => client.get<KnowledgeFileRow[]>('/knowledge/files', { spaceId: query.spaceId, pageId: query.pageId }),
      attach: body => client.post<{ id: number }>('/knowledge/files', body),
      remove: id => client.delete<void>(`/knowledge/files/${id}`),
      download: id => client.download(`/knowledge/files/${id}/download`),
      upload: async file => {
        const form = new FormData()
        form.append('file', file)
        const token = authSession.token()
        const response = await fetch(`${apiBaseUrl()}/system/files`, {
          method: 'POST',
          headers: token ? { Authorization: `Bearer ${token}` } : undefined,
          body: form,
        })
        const text = await response.text()
        const payload = (text ? JSON.parse(text) : { code: response.status, message: response.statusText }) as ApiResponse<KnowledgeUploadResult>
        if (!response.ok || payload.code !== 200) {
          throw new ApiError(payload.message || '附件上传失败', payload.code || response.status, response.status, payload.traceId)
        }
        return payload.data
      },
    },
    search: params => client.get<KnowledgeSearchResult>('/knowledge/search', {
      keyword: params.keyword,
      spaceId: params.spaceId ?? undefined,
      pageType: params.pageType,
      limit: params.limit,
    }),
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const knowledgeApi = createKnowledgeApi(httpClient)
