import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M06 日程与会议 · 接口层
   ------------------------------------------------------------
   日程 / 会议 / 会议室 / 预定 / 模板五组能力。
   要点：
     · 会议室冲突、参会人时段冲突都由**后端**判定并返回可读提示（400），
       前端原样展示 message，不改写成通用错误。
     · 会议纪要正文归档到知识模块，本模块只保存 minutesPageId；
       前端展示"已归档到知识库"并给出页面 ID。
     · 时间统一按 ISO-8601（yyyy-MM-ddTHH:mm:ss）提交，展示用 yyyy-MM-dd HH:mm。
   ============================================================ */

export interface MeetingEventRow {
  id: number
  eventTitle: string
  eventType: string
  ownerId?: number | null
  ownerName?: string | null
  deptId?: number | null
  deptName?: string | null
  meetingId?: number | null
  startTime: string
  endTime: string
  allDay?: number | null
  location?: string | null
  description?: string | null
  remindMinutes?: number | null
  status?: string
  sourceType?: string
  createTime?: string
}

export interface MeetingParticipantRow {
  id: number
  meetingId: number
  userId: number
  username?: string | null
  nickname?: string | null
  deptName?: string | null
  participantRole?: string
  responseStatus?: string
  invitedTime?: string | null
  respondTime?: string | null
  remark?: string | null
}

export interface MeetingRow {
  id: number
  meetingTitle: string
  templateId?: number | null
  templateName?: string | null
  roomId?: number | null
  roomName?: string | null
  organizerId?: number | null
  organizerName?: string | null
  startTime: string
  endTime: string
  location?: string | null
  meetingLink?: string | null
  status: string
  minutesPageId?: number | null
  rescheduleCount?: number | null
  createTime?: string
  participantCount?: number | null
  agenda?: string | null
  participants?: MeetingParticipantRow[]
}

export interface MeetingRoomRow {
  id: number
  roomName: string
  roomCode: string
  location?: string | null
  capacity?: number | null
  equipment?: string | null
  status?: string
  sort?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface MeetingRoomAvailability extends MeetingRoomRow {
  available: boolean
  conflictCount: number
  conflicts?: Array<{
    bookingId?: number | null
    bookingTitle?: string | null
    startTime?: string
    endTime?: string
    bookerName?: string | null
    meetingTitle?: string | null
  }>
  conflictNote?: string | null
}

export interface MeetingBookingRow {
  id: number
  roomId: number
  roomName?: string | null
  meetingId?: number | null
  meetingTitle?: string | null
  bookingTitle: string
  bookerId?: number | null
  bookerName?: string | null
  startTime: string
  endTime: string
  status?: string
  cancelReason?: string | null
  createTime?: string
}

export interface MeetingTemplateRow {
  id: number
  templateCode: string
  templateName: string
  templateType: string
  description?: string | null
  agendaTemplate?: string | null
  defaultDurationMinutes?: number | null
  status?: string
  builtin?: number | null
  sort?: number | null
  remark?: string | null
  createTime?: string
}

export interface MeetingEventWriteRequest {
  eventTitle: string
  eventType?: string
  deptId?: number | null
  startTime: string
  endTime: string
  allDay?: boolean
  location?: string | null
  description?: string | null
  remindMinutes?: number | null
  remark?: string | null
}

export interface MeetingWriteRequest {
  meetingTitle?: string
  templateId?: number | null
  roomId?: number | null
  startTime?: string
  endTime?: string
  location?: string | null
  meetingLink?: string | null
  agenda?: string | null
  participantIds?: number[]
  remark?: string | null
}

export interface MeetingScheduleRequest {
  startTime: string
  endTime: string
  roomId?: number | null
  onlineOnly?: boolean
  remark?: string | null
}

export interface MeetingRoomWriteRequest {
  roomName?: string
  roomCode?: string
  location?: string | null
  capacity?: number | null
  equipment?: string | null
  status?: string
  sort?: number | null
  remark?: string | null
}

export interface MeetingBookingWriteRequest {
  roomId: number
  meetingId?: number | null
  bookingTitle: string
  bookerId?: number | null
  startTime: string
  endTime: string
  remark?: string | null
}

export interface MeetingTemplateWriteRequest {
  templateCode?: string
  templateName?: string
  templateType?: string
  description?: string | null
  agendaTemplate?: string | null
  defaultDurationMinutes?: number | null
  status?: string
  sort?: number | null
  remark?: string | null
}

/** 会议结束结果：会生成主持人「撰写会议纪要」待办（经 M10 投递）。 */
export interface MeetingEndResult {
  meetingId: number
  status: string
  minutesTodoId?: number | null
  organizerId?: number | null
}

/** 纪要归档结果：正文写入知识模块，本模块只保存 minutesPageId。 */
export interface MeetingMinutesResult {
  meetingId: number
  minutesPageId: number
  created?: boolean
  versionNo?: number
  pageType?: string
}

export function createMeetingApi(client: HttpClient) {
  return {
    events: {
      list: (query?: QueryParams) => client.get<PageResult<MeetingEventRow>>('/meeting/events', query),
      detail: (id: number) => client.get<MeetingEventRow>(`/meeting/events/${id}`),
      create: (body: MeetingEventWriteRequest) => client.post<IdResponse>('/meeting/events', body),
      update: (id: number, body: MeetingEventWriteRequest) => client.put<void>(`/meeting/events/${id}`, body),
      cancel: (id: number) => client.delete<void>(`/meeting/events/${id}`),
    },
    meetings: {
      list: (query?: QueryParams) => client.get<PageResult<MeetingRow>>('/meeting/meetings', query),
      detail: (id: number) => client.get<MeetingRow>(`/meeting/meetings/${id}`),
      create: (body: MeetingWriteRequest) => client.post<IdResponse>('/meeting/meetings', body),
      update: (id: number, body: MeetingWriteRequest) => client.put<void>(`/meeting/meetings/${id}`, body),
      reschedule: (id: number, body: MeetingScheduleRequest) =>
        client.put<MeetingRow>(`/meeting/meetings/${id}/schedule`, body),
      cancel: (id: number, cancelReason?: string) =>
        client.post<void>(`/meeting/meetings/${id}/cancel`, { cancelReason: cancelReason ?? null }),
      remove: (id: number) => client.delete<void>(`/meeting/meetings/${id}`),
      end: (id: number) => client.post<MeetingEndResult>(`/meeting/meetings/${id}/end`),
      archiveMinutes: (id: number, body: { content: string; summary?: string | null; tags?: string[] }) =>
        client.post<MeetingMinutesResult>(`/meeting/meetings/${id}/minutes`, body),
      addParticipants: (id: number, body: { userIds: number[]; participantRole?: string }) =>
        client.post<void>(`/meeting/meetings/${id}/participants`, body),
      removeParticipant: (id: number, userId: number) =>
        client.delete<void>(`/meeting/meetings/${id}/participants/${userId}`),
      respond: (id: number, responseStatus: 'accepted' | 'declined') =>
        client.post<void>(`/meeting/meetings/${id}/respond`, { responseStatus }),
    },
    rooms: {
      list: (query?: QueryParams) => client.get<MeetingRoomRow[]>('/meeting/rooms', query),
      availability: (query: QueryParams) =>
        client.get<MeetingRoomAvailability[]>('/meeting/rooms/availability', query),
      detail: (id: number) => client.get<MeetingRoomRow>(`/meeting/rooms/${id}`),
      create: (body: MeetingRoomWriteRequest) => client.post<IdResponse>('/meeting/rooms', body),
      update: (id: number, body: MeetingRoomWriteRequest) => client.put<void>(`/meeting/rooms/${id}`, body),
      remove: (id: number) => client.delete<void>(`/meeting/rooms/${id}`),
    },
    bookings: {
      list: (query?: QueryParams) => client.get<MeetingBookingRow[]>('/meeting/bookings', query),
      create: (body: MeetingBookingWriteRequest) => client.post<IdResponse>('/meeting/bookings', body),
      cancel: (id: number, cancelReason?: string) =>
        client.post<void>(`/meeting/bookings/${id}/cancel`, { cancelReason: cancelReason ?? null }),
    },
    templates: {
      list: (query?: QueryParams) => client.get<MeetingTemplateRow[]>('/meeting/templates', query),
      detail: (id: number) => client.get<MeetingTemplateRow>(`/meeting/templates/${id}`),
      create: (body: MeetingTemplateWriteRequest) => client.post<IdResponse>('/meeting/templates', body),
      update: (id: number, body: MeetingTemplateWriteRequest) => client.put<void>(`/meeting/templates/${id}`, body),
      remove: (id: number) => client.delete<void>(`/meeting/templates/${id}`),
    },
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const meetingApi = createMeetingApi(httpClient)
