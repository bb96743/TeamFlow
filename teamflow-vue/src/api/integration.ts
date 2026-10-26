import { httpClient, type HttpClient, type QueryParams } from './http'
import type { PageResult } from './system'

/* ============================================================
   M12 集成与开放 · 接口层
   ------------------------------------------------------------
   两个页面：
     · 集成中心 /integration/app：第三方接入配置 + 凭据（加密存储）+ 连通性检测
     · 开放 API Key /integration/key：Key 签发/撤销 + 调用审计
   安全约定（与后端一致）：
     · Key 明文只在创建响应出现一次；列表/详情只返回前缀，前端不得缓存/回显明文；
     · 第三方凭据不回显明文，只有末 4 位提示位；编辑留空表示不修改；
     · 连通性检测只做地址可达性，success=false 时原样展示原因，绝不显示成"已连接"。
   ============================================================ */

export type IntegrationAppType = 'dingtalk' | 'wecom' | 'feishu' | 'github' | 'jira' | 'open_client'

export interface IntegrationAppRow {
  id: number
  appCode: string
  appName: string
  appType: IntegrationAppType | string
  appTypeLabel?: string
  endpointUrl?: string | null
  configJson?: string | null
  credentialHint?: string | null
  /** 是否已配置凭据（只有提示位，无明文） */
  credentialConfigured?: boolean
  status: string
  statusLabel?: string
  lastCheckTime?: string | null
  lastCheckResult?: string | null
  lastCheckMessage?: string | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface IntegrationAppWriteRequest {
  appCode?: string
  appName?: string
  appType?: string
  endpointUrl?: string | null
  configJson?: string | null
  /** 凭据明文：仅提交时使用；留空表示不修改（后端加密存储，不回读） */
  credential?: string | null
  status?: string
  remark?: string | null
}

export interface IntegrationAppCheckResult {
  appId: number
  appCode: string
  appName: string
  endpointUrl?: string | null
  success: boolean
  result: string
  /** 失败原因（原样展示，不改写成"已连接"） */
  message: string
  checkedAt: string
  credentialConfigured?: boolean
  limitation?: string
}

export interface IntegrationEncryptionStatus {
  credentialEncryptionConfigured: boolean
  keyFingerprint?: string | null
  hint?: string
}

export interface IntegrationApiKeyRow {
  id: number
  appId?: number | null
  appName?: string | null
  keyName: string
  /** 密钥前缀（列表只显示它，永远不返回明文） */
  keyPrefix: string
  userId: number
  username?: string | null
  /** 权限范围（逗号分隔权限码；空表示仅受绑定用户权限约束） */
  scope?: string | null
  expiresAt?: string | null
  status: string
  lastUsedAt?: string | null
  callCount?: number
  remark?: string | null
  createTime?: string
}

export interface IntegrationApiKeyCreateRequest {
  keyName: string
  appId?: number | null
  userId?: number | null
  scope?: string | null
  expiresAt?: string | null
  remark?: string | null
}

export interface IntegrationApiKeyCreated {
  id: number
  keyName: string
  keyPrefix: string
  /** 明文密钥：只在创建响应出现一次，关闭后无法再次查看 */
  plainKey: string
  userId: number
  scope?: string | null
  expiresAt?: string | null
  warning?: string
}

export interface IntegrationEventLogRow {
  id: number
  channel: string
  keyId?: number | null
  appId?: number | null
  userId?: number | null
  method?: string | null
  path?: string | null
  status?: number | null
  costMs?: number | null
  clientIp?: string | null
  requestId?: string | null
  detail?: string | null
  createTime?: string
}

export function createIntegrationApi(client: HttpClient) {
  return {
    apps: {
      list: (query?: QueryParams) => client.get<PageResult<IntegrationAppRow>>('/integration/apps', query),
      detail: (id: number) => client.get<IntegrationAppRow>(`/integration/apps/${id}`),
      create: (body: IntegrationAppWriteRequest) => client.post<number>('/integration/apps', body),
      update: (id: number, body: IntegrationAppWriteRequest) =>
        client.put<void>(`/integration/apps/${id}`, body),
      remove: (id: number) => client.delete<void>(`/integration/apps/${id}`),
      /** 连通性检测：只做地址可达性探测，失败原因需原样展示 */
      check: (id: number) => client.post<IntegrationAppCheckResult>(`/integration/apps/${id}/check`),
      encryptionStatus: () => client.get<IntegrationEncryptionStatus>('/integration/apps/encryption-status'),
    },
    keys: {
      list: (query?: QueryParams) => client.get<PageResult<IntegrationApiKeyRow>>('/integration/keys', query),
      detail: (id: number) => client.get<IntegrationApiKeyRow>(`/integration/keys/${id}`),
      create: (body: IntegrationApiKeyCreateRequest) =>
        client.post<IntegrationApiKeyCreated>('/integration/keys', body),
      revoke: (id: number) => client.post<void>(`/integration/keys/${id}/revoke`),
      /** 最近开放调用（开放接口 + 智能体工具），用于排障 */
      audits: (query?: QueryParams) => client.get<IntegrationEventLogRow[]>('/integration/keys/audits', query),
    },
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const integrationApi = createIntegrationApi(httpClient)
