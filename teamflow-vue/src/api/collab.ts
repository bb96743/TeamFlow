import { httpClient, type HttpClient, type QueryParams } from './http.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M09 协作与消息接口（/api/collab）
   ------------------------------------------------------------
   与后端 5 个 Controller 对应：
     CollabConversationController  /api/collab/conversations（会话、消息、已读）
     CollabFeedController          /api/collab/feeds（只读动态流）
     CollabAnnouncementController  /api/collab/announcements（公告）
     CollabCommentController / CollabNotificationController 由其他页面使用，这里不封装
   权限码见 sys_menu：collab:msg:list / collab:msg:send / collab:feed:list /
   collab:notice:list / add / edit / remove，与后端 @RequiresPermission 完全一致。

   约定：**未读状态一律以后端返回为准**（会话行的 unreadCount 与 /unread-count），
   前端不自行推断；已读只需把「已读到的消息 ID」提交给后端推进游标。
   ============================================================ */

export interface CollabConversationRow {
  id: number
  conversationType: 'direct' | 'group' | string
  conversationTypeLabel?: string | null
  title?: string | null
  memberCount?: number | null
  lastMessageId?: number | null
  lastMessageTime?: string | null
  lastMessage?: string | null
  /** 未读消息数（后端按已读游标与发送人计算） */
  unreadCount?: number | null
  status?: string | null
}

export interface CollabConversationCreateRequest {
  conversationType?: 'direct' | 'group'
  /** 群组名称（单聊忽略） */
  title?: string | null
  /** 参与人用户 ID（不含自己；单聊必须恰好 1 人） */
  memberIds?: number[]
}

export interface CollabMessageRow {
  id: number
  conversationId: number
  senderId?: number | null
  senderName?: string | null
  senderUsername?: string | null
  contentType?: string | null
  content: string
  actionUrl?: string | null
  bizType?: string | null
  bizId?: number | null
  mentionedUserIds?: number[]
  createTime?: string
}

export interface CollabMessageSendRequest {
  content?: string
  actionUrl?: string | null
  bizType?: string | null
  bizId?: number | null
  mentionedUserIds?: number[]
}

export interface CollabMessageSendResult {
  id: number
  conversationId: number
  mentionedUserIds?: number[]
}

export interface CollabReadResult {
  conversationId: number
  lastReadMessageId: number
  /** 后端返回的当前账号总未读数 */
  unreadCount: number
}

export interface CollabUnreadCount {
  unreadCount: number
  unreadNotifications: number
}

export interface CollabFeedRow {
  id: number
  eventId?: string | null
  eventType?: string | null
  actorId?: number | null
  actorName?: string | null
  action?: string | null
  /** 后端给出的中文动作标签 */
  actionLabel?: string | null
  bizType?: string | null
  bizId?: number | null
  summary?: string | null
  occurredAt?: string
}

export interface CollabAnnouncementRow {
  id: number
  title: string
  content: string
  /** all 全员 / dept 指定部门（可见性由后端按数据权限判定） */
  scopeType?: 'all' | 'dept' | string
  scopeLabel?: string | null
  deptId?: number | null
  deptName?: string | null
  publisherId?: number | null
  publisherName?: string | null
  publishTime?: string | null
  status?: 'draft' | 'published' | 'archived' | string
  statusLabel?: string | null
  createTime?: string
  /** 当前账号是否已读（后端按已读记录返回） */
  read?: boolean
  readTime?: string | null
}

export interface CollabAnnouncementWriteRequest {
  title?: string
  content?: string
  scopeType?: string
  deptId?: number | null
  status?: string
}

export interface CollabApi {
  conversations: {
    list: () => Promise<CollabConversationRow[]>
    create: (body: CollabConversationCreateRequest) => Promise<{ id: number }>
    messages: (id: number, query?: QueryParams) => Promise<PageResult<CollabMessageRow>>
    send: (id: number, body: CollabMessageSendRequest) => Promise<CollabMessageSendResult>
    read: (id: number, lastReadMessageId: number) => Promise<CollabReadResult>
    unreadCount: () => Promise<CollabUnreadCount>
  }
  feeds: {
    list: (query?: QueryParams) => Promise<PageResult<CollabFeedRow>>
  }
  announcements: {
    list: (query?: QueryParams) => Promise<PageResult<CollabAnnouncementRow>>
    detail: (id: number) => Promise<CollabAnnouncementRow>
    create: (body: CollabAnnouncementWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: CollabAnnouncementWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
    read: (id: number) => Promise<void>
  }
}

export function createCollabApi(client: HttpClient): CollabApi {
  return {
    conversations: {
      list: () => client.get<CollabConversationRow[]>('/collab/conversations'),
      create: body => client.post<{ id: number }>('/collab/conversations', body),
      messages: (id, query) => client.get<PageResult<CollabMessageRow>>(`/collab/conversations/${id}/messages`, query),
      send: (id, body) => client.post<CollabMessageSendResult>(`/collab/conversations/${id}/messages`, body),
      read: (id, lastReadMessageId) =>
        client.put<CollabReadResult>(`/collab/conversations/${id}/read`, { lastReadMessageId }),
      unreadCount: () => client.get<CollabUnreadCount>('/collab/conversations/unread-count'),
    },
    feeds: {
      list: query => client.get<PageResult<CollabFeedRow>>('/collab/feeds', query),
    },
    announcements: {
      list: query => client.get<PageResult<CollabAnnouncementRow>>('/collab/announcements', query),
      detail: id => client.get<CollabAnnouncementRow>(`/collab/announcements/${id}`),
      create: body => client.post<{ id: number }>('/collab/announcements', body),
      update: (id, body) => client.put<void>(`/collab/announcements/${id}`, body),
      remove: id => client.delete<void>(`/collab/announcements/${id}`),
      read: id => client.put<void>(`/collab/announcements/${id}/read`),
    },
  }
}

export const collabApi = createCollabApi(httpClient)
