import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M04 团队与成员 · 接口层
   ------------------------------------------------------------
   团队 CRUD / 成员关系 / 团队选项 / 成员负载。
   注意：团队角色（OWNER/MEMBER/VIEWER）只是团队内部分工，
   不等于系统角色或权限；系统里能不能看/改由 team:* 权限码决定。
   ============================================================ */

export type TeamRole = 'OWNER' | 'MEMBER' | 'VIEWER'

export interface TeamRow {
  id: number
  teamName: string
  teamCode?: string | null
  teamDesc?: string | null
  deptId?: number | null
  deptName?: string | null
  ownerId?: number | null
  ownerName?: string | null
  status: string
  statusLabel?: string
  sort?: number
  remark?: string | null
  memberCount?: number
  createTime?: string
  updateTime?: string
}

export interface TeamWriteRequest {
  teamName?: string
  teamCode?: string | null
  teamDesc?: string | null
  deptId?: number | null
  ownerId?: number | null
  status?: string
  sort?: number
  remark?: string | null
}

export interface TeamMemberRow {
  id: number
  teamId: number
  userId: number
  username?: string | null
  nickname?: string | null
  deptId?: number | null
  deptName?: string | null
  teamRole: TeamRole
  teamRoleLabel?: string
  joinTime?: string | null
  leaveTime?: string | null
  remark?: string | null
  active?: boolean
  /** 负载来自任务是只读端口（M03 提供），前端不自行统计 */
  taskTotal?: number
  taskInProgress?: number
}

export interface TeamLoadSummary {
  memberCount: number
  taskTotal: number
  taskInProgress: number
}

export interface TeamDetail extends TeamRow {
  members: TeamMemberRow[]
  loadSummary: TeamLoadSummary
}

export interface TeamOption {
  id: number
  teamName: string
  teamCode?: string | null
}

export function createTeamApi(client: HttpClient) {
  return {
    teams: {
      list: (query?: QueryParams) => client.get<PageResult<TeamRow>>('/team/teams', query),
      options: () => client.get<TeamOption[]>('/team/teams/options'),
      detail: (id: number) => client.get<TeamDetail>(`/team/teams/${id}`),
      create: (body: TeamWriteRequest) => client.post<IdResponse>('/team/teams', body),
      update: (id: number, body: TeamWriteRequest) => client.put<void>(`/team/teams/${id}`, body),
      setOwner: (id: number, userId: number) => client.put<IdResponse>(`/team/teams/${id}/owner`, { userId }),
      dissolve: (id: number) => client.delete<void>(`/team/teams/${id}`),
    },
    members: {
      list: (teamId: number, includeLeft = false) =>
        client.get<TeamMemberRow[]>(`/team/teams/${teamId}/members`, { includeLeft }),
      add: (teamId: number, body: { userId: number; teamRole?: TeamRole; remark?: string }) =>
        client.post<IdResponse>(`/team/teams/${teamId}/members`, body),
      changeRole: (teamId: number, memberId: number, teamRole: TeamRole) =>
        client.put<void>(`/team/teams/${teamId}/members/${memberId}/role`, { teamRole }),
      leave: (teamId: number, memberId: number) =>
        client.delete<void>(`/team/teams/${teamId}/members/${memberId}`),
    },
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const teamApi = createTeamApi(httpClient)
