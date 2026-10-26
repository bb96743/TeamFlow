import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M14 项目 · 接口层
   ------------------------------------------------------------
   项目立项（2.5.1）：项目 CRUD + 状态机 + 里程碑 + 成员。
   注意：
     · 项目只存 objectiveId 引用，目标标题/进度由后端实时向目标模块读取，
       前端直接展示接口返回值，不缓存目标标题（刷新即最新）。
     · 里程碑完成度由后端经任务模块只读端口实时统计，接口不接受手填完成度，
       同一项目在不同账号下看到的完成度一致。
     · 甘特/风险/复盘（2.5.2–2.5.4）属后续里程碑，本接口层不包含。
   ============================================================ */

export interface ProjectRow {
  id: number
  projectCode: string
  projectName: string
  projectDesc?: string | null
  objectiveId?: number | null
  noObjectiveReason?: string | null
  ownerId?: number | null
  ownerName?: string | null
  deptId?: number | null
  deptName?: string | null
  startDate?: string | null
  endDate?: string | null
  status: string
  statusLabel?: string
  priority?: string
  remark?: string | null
  createTime?: string
  updateTime?: string
  milestoneCount?: number
  completedMilestoneCount?: number
  memberCount?: number
  /** 后端给出的允许流转目标（项目状态机唯一来源） */
  nextStatuses?: string[]
  /** 结项/归档后只读（后端 400），前端据此禁用编辑 */
  editable?: boolean
  /** 进行中且计划结束日期已过 */
  delayed?: boolean
}

export interface ProjectObjectiveView {
  id: number
  objectiveTitle?: string | null
  objectiveDesc?: string | null
  progress?: number | null
  status?: string | null
  ownerId?: number | null
  missing?: boolean
  note?: string | null
  source?: string
}

export interface ProjectMilestoneRow {
  id: number
  projectId: number
  milestoneName: string
  milestoneDesc?: string | null
  krId?: number | null
  planStartDate?: string | null
  planEndDate?: string | null
  actualStartDate?: string | null
  actualEndDate?: string | null
  status: string
  statusLabel?: string
  nextStatuses?: string[]
  sort?: number
  remark?: string | null
  createTime?: string
  delayed?: boolean
  /** 是否绑定了 KR */
  krLinked?: boolean
  taskTotal?: number
  taskDone?: number
  /** 完成度（百分比，后端实时计算；未绑 KR 或无可统计任务时为 null） */
  completion?: number | null
  sampled?: boolean
  /** 完成度为空时的原因说明 */
  completionNote?: string | null
}

export interface ProjectMemberRow {
  id: number
  projectId: number
  userId: number
  username?: string | null
  nickname?: string | null
  deptName?: string | null
  memberRole: string
  memberRoleLabel?: string
  joinTime?: string | null
  leaveTime?: string | null
  remark?: string | null
}

export interface ProjectProgressSummary {
  milestoneCount: number
  completedMilestoneCount: number
  cancelledMilestoneCount: number
  delayedMilestoneCount: number
  linkedMilestoneCount: number
  projectProgress: number | null
  note?: string | null
}

export interface ProjectDetail extends ProjectRow {
  objective?: ProjectObjectiveView | null
  milestones: ProjectMilestoneRow[]
  members: ProjectMemberRow[]
  progress: ProjectProgressSummary
  objectiveTitleSource?: string
  completionSource?: string
}

export interface ProjectWriteRequest {
  projectCode?: string
  projectName?: string
  projectDesc?: string | null
  objectiveId?: number | null
  noObjectiveReason?: string | null
  ownerId?: number | null
  deptId?: number | null
  startDate?: string | null
  endDate?: string | null
  priority?: string
  remark?: string | null
}

export interface ProjectMilestoneWriteRequest {
  milestoneName?: string
  milestoneDesc?: string | null
  krId?: number | null
  planStartDate?: string | null
  planEndDate?: string | null
  actualStartDate?: string | null
  actualEndDate?: string | null
  status?: string
  sort?: number
  remark?: string | null
}

export interface ProjectStatusState {
  code: string
  label: string
  terminal: boolean
}

export interface ProjectStatusBlock {
  states: ProjectStatusState[]
  transitions: Record<string, string[]>
}

export interface ProjectStatusMachine {
  project: ProjectStatusBlock
  milestone: ProjectStatusBlock
  memberRoles: string[]
}

/** 关联目标选项（目标模块公开只读接口，用于立项时选择目标） */
export interface ObjectiveOption {
  id: number
  objectiveTitle: string
  progress?: number | null
  status?: string | null
}

/** 关联关键结果选项（目标模块公开只读接口，用于里程碑绑定 KR） */
export interface KrOption {
  id: number
  krTitle: string
  krType?: string | null
  progress?: number | null
}

/* ============================================================
   M3 扩展（需求 2.5.2 甘特 / 2.5.3 风险 / 2.5.4 复盘）
   ------------------------------------------------------------
   甘特：任务标题/起止/状态/完成度由后端从任务模块实时读取，
   前端只做时间轴定位，**不在前端计算完成度**；任务不可见时后端
   返回 missing=true，界面标注而不是整页失败。
   风险：状态流转以每行 nextStatuses / 状态机为准，非法流转后端 400。
   复盘：正文归档到知识库，本模块只返回 retroPageId；页面不保存正文。
   ============================================================ */

export interface ProjectTaskLinkRow {
  id: number
  projectId: number
  taskId: number
  linkType: string
  linkTypeLabel?: string | null
  remark?: string | null
  createTime?: string
}

export interface ProjectTaskLinkRequest {
  taskId: number
  linkType?: string
  remark?: string | null
}

export interface ProjectGanttTaskRow {
  linkId: number
  taskId: number
  linkType: string
  linkTypeLabel?: string | null
  linkRemark?: string | null
  taskTitle?: string | null
  status?: string | null
  statusLabel?: string | null
  priority?: string | null
  startDate?: string | null
  dueDate?: string | null
  doneTime?: string | null
  assigneeName?: string | null
  /** 完成度由后端按任务模块口径给出，前端只展示 */
  completionPercent?: number | null
  delayed?: boolean
  /** 任务已删除或当前账号无任务可见权限：界面标注「任务不可见」，不视为整页错误 */
  missing?: boolean
  missingReason?: string | null
}

export interface ProjectGanttSummary {
  linkedTaskCount: number
  visibleTaskCount: number
  completedTaskCount: number
  inProgressTaskCount: number
  notStartedTaskCount: number
  delayedTaskCount: number
  taskCompletionPercent?: number | null
  milestoneCount?: number
  completedMilestoneCount?: number
  delayedMilestoneCount?: number
  projectProgress?: number | null
}

export interface ProjectGanttData {
  projectId: number
  projectCode?: string
  projectName?: string
  status: string
  statusLabel?: string
  startDate?: string | null
  endDate?: string | null
  ownerName?: string | null
  tasks: ProjectGanttTaskRow[]
  milestones: ProjectMilestoneRow[]
  summary: ProjectGanttSummary
  taskSource?: string
  /** 后端明示关键路径未实现，界面如实说明、不假装有依赖图 */
  criticalPathSupported?: boolean
  criticalPathNote?: string | null
}

export interface ProjectRiskLogRow {
  id: number
  riskId: number
  fromStatus?: string | null
  toStatus: string
  actionNote?: string | null
  operatorId?: number | null
  operatorName?: string | null
  createTime?: string
}

export interface ProjectRiskRow {
  id: number
  projectId: number
  riskTitle: string
  riskDesc?: string | null
  riskLevel: string
  riskLevelLabel?: string
  riskType: string
  riskTypeLabel?: string
  ownerId?: number | null
  ownerName?: string | null
  responsePlan?: string | null
  dueDate?: string | null
  status: string
  statusLabel?: string
  /** 允许流转到的目标状态（风险状态机唯一来源，非法流转按钮不展示） */
  nextStatuses?: string[]
  delayed?: boolean
  remark?: string | null
  createTime?: string
  updateTime?: string
  logs?: ProjectRiskLogRow[]
}

export interface ProjectRiskWriteRequest {
  riskTitle?: string
  riskDesc?: string | null
  riskLevel?: string
  riskType?: string
  ownerId?: number | null
  responsePlan?: string | null
  dueDate?: string | null
  remark?: string | null
}

export interface ProjectRiskStatusMachine {
  states: Array<{ code: string; label: string; terminal: boolean }>
  transitions: Record<string, string[]>
  levels: string[]
  types: string[]
}

export interface ProjectRetroData {
  projectId: number
  projectName?: string
  projectCode?: string
  status: string
  statusLabel?: string
  retroPageId?: number | null
  retroArchivedTime?: string | null
  retroArchivedBy?: string | null
  retroArchived: boolean
  /** 项目结项（closed）或归档（archived）后才允许归档 */
  canArchive: boolean
  archiveRule?: string
}

export interface ProjectRetroTemplate extends ProjectRetroData {
  templateTitle: string
  templateContent: string
  templateNote?: string
}

export interface ProjectRetroArchiveRequest {
  title?: string | null
  content: string
  tags?: string[] | null
  summary?: string | null
}

export interface ProjectRetroArchiveResult {
  projectId: number
  retroPageId: number
  created: boolean
  versionNo?: number | null
  pageType?: string
  spaceId?: number | null
  nodeId?: number | null
  contentStoredIn?: string
}
export function createProjectApi(client: HttpClient) {
  return {
    projects: {
      list: (query?: QueryParams) => client.get<PageResult<ProjectRow>>('/project/projects', query),
      detail: (id: number) => client.get<ProjectDetail>(`/project/projects/${id}`),
      create: (body: ProjectWriteRequest) => client.post<IdResponse>('/project/projects', body),
      update: (id: number, body: ProjectWriteRequest) => client.put<void>(`/project/projects/${id}`, body),
      remove: (id: number) => client.delete<void>(`/project/projects/${id}`),
      changeStatus: (id: number, status: string) =>
        client.put<ProjectRow>(`/project/projects/${id}/status`, { status }),
      statusMachine: () => client.get<ProjectStatusMachine>('/project/projects/status-machine'),
    },
    members: {
      list: (projectId: number) =>
        client.get<ProjectMemberRow[]>(`/project/projects/${projectId}/members`),
      add: (projectId: number, body: { userId: number; memberRole?: string; remark?: string }) =>
        client.post<IdResponse>(`/project/projects/${projectId}/members`, body),
      remove: (projectId: number, memberId: number) =>
        client.delete<void>(`/project/projects/${projectId}/members/${memberId}`),
    },
    milestones: {
      list: (projectId: number) =>
        client.get<ProjectMilestoneRow[]>(`/project/projects/${projectId}/milestones`),
      create: (projectId: number, body: ProjectMilestoneWriteRequest) =>
        client.post<IdResponse>(`/project/projects/${projectId}/milestones`, body),
      update: (projectId: number, milestoneId: number, body: ProjectMilestoneWriteRequest) =>
        client.put<ProjectMilestoneRow>(`/project/projects/${projectId}/milestones/${milestoneId}`, body),
      remove: (projectId: number, milestoneId: number) =>
        client.delete<void>(`/project/projects/${projectId}/milestones/${milestoneId}`),
    },
    /* ── M3 扩展：甘特/任务关联、风险、复盘 ── */
    gantt: {
      get: (projectId: number) => client.get<ProjectGanttData>(`/project/projects/${projectId}/gantt`),
    },
    taskLinks: {
      list: (projectId: number) => client.get<ProjectTaskLinkRow[]>(`/project/projects/${projectId}/tasks`),
      add: (projectId: number, body: ProjectTaskLinkRequest) =>
        client.post<IdResponse>(`/project/projects/${projectId}/tasks`, body),
      remove: (projectId: number, linkId: number) =>
        client.delete<void>(`/project/projects/${projectId}/tasks/${linkId}`),
    },
    risks: {
      statusMachine: () => client.get<ProjectRiskStatusMachine>('/project/projects/risk-status-machine'),
      list: (projectId: number, query?: QueryParams) =>
        client.get<PageResult<ProjectRiskRow>>(`/project/projects/${projectId}/risks`, query),
      detail: (projectId: number, riskId: number) =>
        client.get<ProjectRiskRow>(`/project/projects/${projectId}/risks/${riskId}`),
      create: (projectId: number, body: ProjectRiskWriteRequest) =>
        client.post<IdResponse>(`/project/projects/${projectId}/risks`, body),
      update: (projectId: number, riskId: number, body: ProjectRiskWriteRequest) =>
        client.put<void>(`/project/projects/${projectId}/risks/${riskId}`, body),
      changeStatus: (projectId: number, riskId: number, body: { status: string; note?: string | null }) =>
        client.put<ProjectRiskRow>(`/project/projects/${projectId}/risks/${riskId}/status`, body),
      logs: (projectId: number, riskId: number) =>
        client.get<ProjectRiskLogRow[]>(`/project/projects/${projectId}/risks/${riskId}/logs`),
      remove: (projectId: number, riskId: number) =>
        client.delete<void>(`/project/projects/${projectId}/risks/${riskId}`),
    },
    retro: {
      get: (projectId: number) => client.get<ProjectRetroData>(`/project/projects/${projectId}/retro`),
      template: (projectId: number) =>
        client.get<ProjectRetroTemplate>(`/project/projects/${projectId}/retro/template`),
      archive: (projectId: number, body: ProjectRetroArchiveRequest) =>
        client.post<ProjectRetroArchiveResult>(`/project/projects/${projectId}/retro/archive`, body),
    },    /** 立项时可选的目标（目标模块公开只读接口；不作为本模块数据副本） */
    objectives: () => client.get<PageResult<ObjectiveOption>>('/okr/objectives', { pageNum: 1, pageSize: 100 }),
    /** 里程碑可绑定的 KR（目标模块公开只读接口） */
    keyResults: (objectiveId: number) =>
      client.get<KrOption[]>(`/okr/objectives/${objectiveId}/krs`),
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const projectApi = createProjectApi(httpClient)
