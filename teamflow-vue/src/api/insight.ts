import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M13 统计与洞察 · 接口层
   ------------------------------------------------------------
   三类能力（与后端 Controller 一一对应）：
     · 任务总览   GET  /api/insight/tasks/overview      insight:task:view
     · 团队负载   GET  /api/insight/teams/options       insight:team:view
                  GET  /api/insight/teams/efficiency    insight:team:view
     · 指标口径   CRUD /api/insight/metrics              insight:metric:list/add/edit/remove
   字段名与后端返回的 Map 键完全一致，不做二次改名，便于排错时对照接口。

   只读模型约定：看板接口是实时聚合结果，前端不落缓存、不写回、不把返回值
   当成"第二数据源"；唯一可写的是指标字典（口径登记本身）。
   ============================================================ */

/** 指标类别：task 任务统计 / team 团队效能 / cross 跨域（跨域看板属后续里程碑） */
export type InsightMetricCategory = 'task' | 'team' | 'cross'
/** 指标状态：draft 草稿 / online 已上线 / offline 已下线；只有 online 参与看板出数 */
export type InsightMetricStatus = 'draft' | 'online' | 'offline'
/** 刷新频率：realtime 实时 / 5min / 1h / daily */
export type InsightRefreshFrequency = 'realtime' | '5min' | '1h' | 'daily'

/** 指标字典行（列表 / 详情共用，详情另有 implemented） */
export interface InsightMetricRow {
  id: number
  metricCode: string
  metricName: string
  metricCategory: InsightMetricCategory | string
  metricDefinition: string
  formula: string
  sourceModule: string
  sourceDetail: string
  timeGrain: string
  missingPolicy: string
  refreshFrequency: InsightRefreshFrequency | string
  cacheTtlSeconds: number
  ownerName: string
  status: InsightMetricStatus | string
  remark?: string | null
  createTime?: string
  updateTime?: string
  /** 后端装饰字段：状态 / 类别 / 刷新频率的中文文案 */
  statusLabel?: string
  categoryLabel?: string
  refreshFrequencyLabel?: string
  cacheStrategy?: string
  /** 详情接口返回：该编码是否已在 M13 指标实现中登记（未登记不能上线） */
  implemented?: boolean
}

/** 指标登记请求：上线时后端要求定义 / 公式 / 来源模块 / 来源接口 / 时间口径 / 缺失值策略齐全 */
export interface InsightMetricWriteRequest {
  metricCode?: string
  metricName?: string
  metricCategory?: string
  metricDefinition?: string
  formula?: string
  sourceModule?: string
  sourceDetail?: string
  timeGrain?: string
  missingPolicy?: string
  refreshFrequency?: string
  cacheTtlSeconds?: number
  ownerName?: string | null
  status?: string
  remark?: string | null
}

/** 看板指标读数：字典口径（四要素）+ 本次统计值一起返回，前端可直接展示 */
export interface InsightMetricReadout {
  code: string
  name: string
  category: string
  definition: string
  formula: string
  sourceModule: string
  sourceDetail: string
  timeGrain: string
  missingPolicy: string
  refreshFrequency: string
  cacheTtlSeconds: number
  ownerName: string
  status: string
  value: number | null
  unit: string | null
  /** 无数据时后端返回 value=0 且 missing=true；界面必须显示「暂无数据」而不是 0 */
  missing: boolean
  note: string | null
}

/** 任务总览成员分布行 */
export interface InsightTaskMemberRow {
  userId: number
  username?: string | null
  nickname?: string | null
  deptId?: number | null
  deptName?: string | null
  taskTotal: number
  taskDone: number
  taskOnTime: number
  taskOverdue: number
  taskCompletionRate: number | null
  timeComplianceRate: number | null
  overdueRate: number | null
  krCount: number
  krAchievement: number | null
  missing: boolean
  noDataNote?: string | null
}

/** 任务周期趋势行：按自然日展开 */
export interface InsightTaskTrendRow {
  date: string
  dueCount: number
  doneCount: number
  lateDueCount: number
}

export interface InsightTaskOverview {
  period: { startDate: string; endDate: string; timezone: string; asOf: string; rangeDays: number }
  scope: { allScope: boolean; assigneeId: number | null; visibleUserCount: number; dataScopeRule: string }
  totals: {
    memberCount: number
    visibleUserCount: number
    taskTotal: number
    taskDone: number
    taskOnTime: number
    taskOverdue: number
    krCount: number
  }
  /** 指标编码 → 读数载荷（口径四要素 + 值 + 缺失标记） */
  metrics: Record<string, InsightMetricReadout>
  members: InsightTaskMemberRow[]
  trend: InsightTaskTrendRow[]
  readOnly: boolean
  cache: string
}

/** 团队下拉选项（M04 团队选项的读模型代理） */
export interface InsightTeamOption {
  id: number
  teamName: string
  teamCode?: string | null
}

export interface InsightTeamMemberRow {
  userId: number
  username?: string | null
  nickname?: string | null
  deptName?: string | null
  teamRole?: string | null
  teamRoleLabel?: string | null
  taskTotal: number
  taskInProgress: number
  completedTaskCount: number
  responseHours: number | null
  sampled: boolean
}

/** 负载均衡：承接量分布的变异系数与判定 */
export interface InsightLoadBalance {
  memberCount: number
  avgTasks: number | null
  maxTasks: number | null
  minTasks: number | null
  range: number | null
  /** 变异系数 CV：越接近 0 越均衡 */
  cv: number | null
  level: string | null
  levelLabel: string | null
  busiestUserId: number | null
  busiestName: string | null
  missing: boolean
  note: string
}

export interface InsightTeamEfficiency {
  team: {
    id: number
    teamName: string
    teamCode?: string | null
    deptName?: string | null
    ownerName?: string | null
    status?: string | null
    memberCount: number
    statMemberCount: number
    filteredMemberCount: number
  }
  period: { startDate: string; endDate: string; timezone: string; asOf: string; rangeDays: number; rangeNote: string }
  totals: {
    memberCount: number
    taskTotal: number
    taskInProgress: number
    completedTaskCount: number
    avgResponseHours: number | null
  }
  metrics: Record<string, InsightMetricReadout>
  members: InsightTeamMemberRow[]
  loadBalance: InsightLoadBalance
  notes: string[]
  readOnly: boolean
  cache: string
}

export interface InsightApi {
  tasks: {
    /** 任务统计总览：startDate / endDate 必填（yyyy-MM-dd，最长 366 天） */
    overview: (query: { startDate: string; endDate: string; assigneeId?: number | null }) => Promise<InsightTaskOverview>
  }
  teams: {
    options: () => Promise<InsightTeamOption[]>
    efficiency: (query: { teamId: number; startDate?: string; endDate?: string }) => Promise<InsightTeamEfficiency>
  }
  metrics: {
    list: (query?: QueryParams) => Promise<PageResult<InsightMetricRow>>
    detail: (id: number) => Promise<InsightMetricRow>
    create: (body: InsightMetricWriteRequest) => Promise<IdResponse>
    update: (id: number, body: InsightMetricWriteRequest) => Promise<void>
    remove: (id: number) => Promise<void>
  }
}

export function createInsightApi(client: HttpClient): InsightApi {
  return {
    tasks: {
      overview: query => client.get<InsightTaskOverview>('/insight/tasks/overview', {
        startDate: query.startDate,
        endDate: query.endDate,
        assigneeId: query.assigneeId ?? undefined,
      }),
    },
    teams: {
      options: () => client.get<InsightTeamOption[]>('/insight/teams/options'),
      efficiency: query => client.get<InsightTeamEfficiency>('/insight/teams/efficiency', {
        teamId: query.teamId,
        startDate: query.startDate,
        endDate: query.endDate,
      }),
    },
    metrics: {
      list: query => client.get<PageResult<InsightMetricRow>>('/insight/metrics', query),
      detail: id => client.get<InsightMetricRow>(`/insight/metrics/${id}`),
      create: body => client.post<IdResponse>('/insight/metrics', body),
      update: (id, body) => client.put<void>(`/insight/metrics/${id}`, body),
      remove: id => client.delete<void>(`/insight/metrics/${id}`),
    },
  }
}

/** 页面直接使用的单例（与会话令牌、统一错误处理一致） */
export const insightApi = createInsightApi(httpClient)
