import { httpClient, type HttpClient, type QueryParams } from './http.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M02 目标与 OKR 接口（/api/okr）
   ------------------------------------------------------------
   与后端 OkrPeriodController / OkrObjectiveController / OkrKeyResultController 一一对应，
   权限码见 V4__okr_menu.sql（okr:period:* / okr:objective:* / okr:kr:*）。
   字段名与后端返回的 Map 键完全一致，不做二次改名，便于排错时对照接口。
   ============================================================ */

/** 目标周期状态：draft 未开启 / active 进行中 / archived 已归档 */
export type OkrPeriodStatus = 'draft' | 'active' | 'archived'
/** 周期类型：quarter 季度 / month 月度 */
export type OkrPeriodType = 'quarter' | 'month'
/** 目标层级：company 公司 / dept 部门 / personal 个人 */
export type OkrObjectiveLevel = 'company' | 'dept' | 'personal'
/** 目标状态：draft 草稿 / active 进行中 / done 已完成 */
export type OkrObjectiveStatus = 'draft' | 'active' | 'done'
/** 关键结果类型：quantitative 量化型 / task_driven 任务驱动型 */
export type OkrKrType = 'quantitative' | 'task_driven'
/** 指标方向：positive 越大越好 / negative 越小越好 */
export type OkrDirection = 'positive' | 'negative'
/** 进度来源：quantitative 公式计算 / task_writeback 任务回写 / manual 人工校准 */
export type OkrProgressSource = 'quantitative' | 'task_writeback' | 'manual'

export interface OkrPeriodRow {
  id: number
  periodName: string
  periodType: OkrPeriodType | string
  startDate?: string | null
  endDate?: string | null
  status: OkrPeriodStatus | string
  sort?: number | null
  remark?: string | null
  /** 周期下的目标数量（后端聚合） */
  objectiveCount?: number | null
  createTime?: string
  updateTime?: string
}

export interface OkrPeriodWriteRequest {
  periodName?: string
  periodType?: string
  /** yyyy-MM-dd */
  startDate?: string
  /** yyyy-MM-dd */
  endDate?: string
  status?: string
  sort?: number | null
  remark?: string | null
}

export interface OkrObjectiveRow {
  id: number
  periodId: number
  periodName?: string | null
  parentId?: number | null
  objectiveTitle: string
  objectiveDesc?: string | null
  level: OkrObjectiveLevel | string
  deptId?: number | null
  deptName?: string | null
  ownerId?: number | null
  ownerName?: string | null
  weight?: number | null
  progress?: number | null
  status: OkrObjectiveStatus | string
  sort?: number | null
  childCount?: number | null
  krCount?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
  /** 目标树接口返回的子树 */
  children?: OkrObjectiveRow[]
}

export interface OkrObjectiveWriteRequest {
  periodId?: number | null
  parentId?: number | null
  objectiveTitle?: string
  objectiveDesc?: string | null
  level?: string
  deptId?: number | null
  ownerId?: number | null
  weight?: number | null
  status?: string
  sort?: number | null
  remark?: string | null
}

export interface OkrAlignRow {
  id: number
  childObjectiveId: number
  childTitle?: string | null
  parentObjectiveId: number
  parentTitle?: string | null
  alignType?: string | null
  remark?: string | null
  createTime?: string
}

export interface OkrAlignWriteRequest {
  parentObjectiveId?: number | null
  alignType?: string
  remark?: string | null
}

export interface OkrKeyResultRow {
  id: number
  objectiveId: number
  objectiveTitle?: string | null
  krTitle: string
  krType: OkrKrType | string
  metricUnit?: string | null
  targetValue?: number | null
  currentValue?: number | null
  direction: OkrDirection | string
  ownerId?: number | null
  ownerName?: string | null
  progress?: number | null
  progressSource?: OkrProgressSource | string
  /** linked 已关联任务 / unlinked 尚未关联 */
  taskLinkStatus?: string | null
  taskCount?: number | null
  doneTaskCount?: number | null
  sort?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface OkrKeyResultWriteRequest {
  krTitle?: string
  krType?: string
  metricUnit?: string | null
  targetValue?: number | null
  currentValue?: number | null
  direction?: string
  ownerId?: number | null
  sort?: number | null
  remark?: string | null
}

/**
 * 进度更新请求（M02 RULE R1）：
 * · 传 currentValue → 按公式重算（量化型），进度来源记为「公式计算」；
 * · 传 progress → 人工校准，必须同时给 reason，且只能在自动值 ±20 个百分点内（D-06.3）；
 * · 两者只能给一个；任务驱动型不允许人工填写。
 */
export interface OkrKeyResultProgressRequest {
  currentValue?: number | null
  progress?: number | null
  reason?: string
}

export interface OkrKeyResultProgressResult {
  id: number
  fromProgress?: number | null
  progress: number
  progressSource: string
}

export interface OkrProgressLogRow {
  id: number
  objectiveId: number
  krId: number
  fromProgress?: number | null
  toProgress?: number | null
  progressSource: string
  reason?: string | null
  operatorId?: number | null
  operatorName?: string | null
  createTime?: string
}

/** 对齐度检查的断点清单行 */
export interface OkrAlignCheckRow {
  id: number
  objectiveTitle: string
  periodId?: number | null
  periodName?: string | null
  level?: string | null
  ownerId?: number | null
  status?: string | null
  createTime?: string
}

export interface OkrAlignCheck {
  periodId?: number | null
  unownedObjectives: OkrAlignCheckRow[]
  objectivesWithoutKr: OkrAlignCheckRow[]
  tasksWithoutObjective: Array<Record<string, unknown>>
  tasksWithoutObjectiveNote?: string | null
  summary?: {
    unownedObjectiveCount?: number
    objectivesWithoutKrCount?: number
    tasksWithoutObjectiveCount?: number
  }
  generatedAt?: string
}

export interface OkrApi {
  periods: {
    list: (query?: QueryParams) => Promise<PageResult<OkrPeriodRow>>
    detail: (id: number) => Promise<OkrPeriodRow>
    create: (body: OkrPeriodWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: OkrPeriodWriteRequest) => Promise<void>
    updateStatus: (id: number, status: string) => Promise<void>
    remove: (id: number) => Promise<void>
  }
  objectives: {
    list: (query?: QueryParams) => Promise<PageResult<OkrObjectiveRow>>
    tree: (periodId?: number | null) => Promise<OkrObjectiveRow[]>
    detail: (id: number) => Promise<OkrObjectiveRow>
    create: (body: OkrObjectiveWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: OkrObjectiveWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
    aligns: (id: number) => Promise<OkrAlignRow[]>
    createAlign: (id: number, body: OkrAlignWriteRequest) => Promise<{ id: number }>
    removeAlign: (alignId: number) => Promise<void>
  }
  krs: {
    list: (objectiveId: number) => Promise<OkrKeyResultRow[]>
    detail: (objectiveId: number, krId: number) => Promise<OkrKeyResultRow>
    create: (objectiveId: number, body: OkrKeyResultWriteRequest) => Promise<{ id: number }>
    update: (objectiveId: number, krId: number, body: OkrKeyResultWriteRequest) => Promise<void>
    remove: (objectiveId: number, krId: number) => Promise<void>
    updateProgress: (objectiveId: number, krId: number, body: OkrKeyResultProgressRequest) => Promise<OkrKeyResultProgressResult>
    progressLogs: (objectiveId: number, krId: number) => Promise<OkrProgressLogRow[]>
  }
  alignCheck: (periodId?: number | null) => Promise<OkrAlignCheck>
}

export function createOkrApi(client: HttpClient): OkrApi {
  return {
    periods: {
      list: query => client.get<PageResult<OkrPeriodRow>>('/okr/periods', query),
      detail: id => client.get<OkrPeriodRow>(`/okr/periods/${id}`),
      create: body => client.post<{ id: number }>('/okr/periods', body),
      update: (id, body) => client.put<void>(`/okr/periods/${id}`, body),
      updateStatus: (id, status) => client.put<void>(`/okr/periods/${id}/status`, { status }),
      remove: id => client.delete<void>(`/okr/periods/${id}`),
    },
    objectives: {
      list: query => client.get<PageResult<OkrObjectiveRow>>('/okr/objectives', query),
      tree: periodId => client.get<OkrObjectiveRow[]>('/okr/objectives/tree', { periodId: periodId ?? undefined }),
      detail: id => client.get<OkrObjectiveRow>(`/okr/objectives/${id}`),
      create: body => client.post<{ id: number }>('/okr/objectives', body),
      update: (id, body) => client.put<void>(`/okr/objectives/${id}`, body),
      remove: id => client.delete<void>(`/okr/objectives/${id}`),
      aligns: id => client.get<OkrAlignRow[]>(`/okr/objectives/${id}/aligns`),
      createAlign: (id, body) => client.post<{ id: number }>(`/okr/objectives/${id}/aligns`, body),
      removeAlign: alignId => client.delete<void>(`/okr/aligns/${alignId}`),
    },
    krs: {
      list: objectiveId => client.get<OkrKeyResultRow[]>(`/okr/objectives/${objectiveId}/krs`),
      detail: (objectiveId, krId) => client.get<OkrKeyResultRow>(`/okr/objectives/${objectiveId}/krs/${krId}`),
      create: (objectiveId, body) => client.post<{ id: number }>(`/okr/objectives/${objectiveId}/krs`, body),
      update: (objectiveId, krId, body) => client.put<void>(`/okr/objectives/${objectiveId}/krs/${krId}`, body),
      remove: (objectiveId, krId) => client.delete<void>(`/okr/objectives/${objectiveId}/krs/${krId}`),
      updateProgress: (objectiveId, krId, body) =>
        client.put<OkrKeyResultProgressResult>(`/okr/objectives/${objectiveId}/krs/${krId}/progress`, body),
      progressLogs: (objectiveId, krId) =>
        client.get<OkrProgressLogRow[]>(`/okr/objectives/${objectiveId}/krs/${krId}/progress-logs`),
    },
    alignCheck: periodId => client.get<OkrAlignCheck>('/okr/align-check', { periodId: periodId ?? undefined }),
  }
}

export const okrApi = createOkrApi(httpClient)
