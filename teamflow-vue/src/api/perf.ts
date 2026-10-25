import { httpClient, type HttpClient, type QueryParams } from './http'
import type { IdResponse, PageResult } from './system'

/* ============================================================
   M05 绩效接口层：对接后端 /api/perf 的 28 个端点。
   口径提示（L0 §9.1 D-2026-10-25-06）：
   · 可归集指标由系统归集，前端只读；只有主观类可人工评分且必须填评语。
   · 缺失数据默认按 0 计；仅 HR/管理者显式标记「不适用」后按剩余权重归一化。
   · 周期一旦锁定不可逆，任何修改只能通过申诉流程。
   ============================================================ */

export type CycleStatus = 'draft' | 'active' | 'scoring' | 'archived' | 'locked'
export type ItemCategory = 'collectable' | 'subjective'
export type RaterType = 'system' | 'self' | 'manager'
export type ResultStatus = 'draft' | 'archived' | 'locked'
export type AppealStatus = 'pending' | 'approved' | 'rejected'

/** 绩效周期（列表行与详情同构；详情额外带 allowedTransitions/locked/editable） */
export interface PerfCycle {
  id: number
  cycleCode: string
  cycleName: string
  cycleType: string
  startDate: string
  endDate: string
  templateId: number | null
  templateName: string | null
  templateTotalWeight: number | null
  templateStatus: string | null
  status: CycleStatus
  collectTime: string | null
  archiveTime: string | null
  lockTime: string | null
  remark: string | null
  createTime: string
  updateTime: string
  statusLabel?: string
  allowedTransitions?: string[]
  locked?: boolean
  editable?: boolean
}

export interface PerfCycleWrite {
  cycleCode?: string
  cycleName?: string
  cycleType?: string
  startDate?: string
  endDate?: string
  templateId?: number | null
  remark?: string
}

/** 指标项（模板详情带 id；提交时不需要） */
export interface PerfTemplateItem {
  id: number
  templateId?: number
  itemCode: string
  itemName: string
  itemCategory: ItemCategory
  metricCode: string | null
  weight: number
  maxScore: number
  scoreStandard: string | null
  requireComment: boolean | number
  sort: number
  remark: string | null
  itemCategoryLabel?: string
}

export interface PerfTemplate {
  id: number
  templateCode: string
  templateName: string
  description: string | null
  status: 'draft' | 'enabled' | 'disabled'
  totalWeight: number
  remark: string | null
  createTime: string
  updateTime: string
  itemCount: number
  statusLabel?: string
  items?: PerfTemplateItem[]
}

export interface PerfTemplateItemWrite {
  itemCode: string
  itemName: string
  itemCategory: ItemCategory
  metricCode?: string | null
  weight: number
  maxScore?: number
  scoreStandard?: string | null
  requireComment?: boolean
  sort?: number
  remark?: string | null
}

export interface PerfTemplateWrite {
  templateCode?: string
  templateName?: string
  description?: string | null
  status?: string
  remark?: string | null
  items?: PerfTemplateItemWrite[]
}

/** 指标字典条目（R2 四要素：数据来源模块 / 计算公式 / 取数时间范围 / 缺失值处理） */
export interface PerfMetricDefinition {
  metricCode: string
  metricName: string
  sourceModule: string
  formula: string
  timeRange: string
  missingPolicy: string
  drillDownType: 'kr' | 'task'
}

/** 归集下钻：KR 来源行（含人工校准量与原因，D-06.3） */
export interface PerfKrSource {
  krId: number
  objectiveId: number
  objectiveTitle: string
  periodId: number
  periodName: string
  krTitle: string
  krType: string
  progress: number
  progressSource: string
  taskCount: number
  doneTaskCount: number
  calibrationFrom: number | null
  calibrationTo: number | null
  calibrationReason: string | null
  calibrationOperator: string | null
  calibrationTime: string | null
}

/** 归集下钻：任务来源行（含按时/逾期判定，D-06.1） */
export interface PerfTaskSource {
  taskId: number
  taskTitle: string
  status: string
  krId: number | null
  dueDate: string | null
  doneTime: string | null
  assigneeId: number | null
  completed: boolean
  onTime: boolean
}

export interface PerfCollectItem {
  itemId: number
  itemCode: string
  itemName: string
  itemCategory: ItemCategory
  weight: number
  metricCode?: string | null
  metricName?: string
  drillDownType?: 'kr' | 'task' | null
  sources?: Array<PerfKrSource | PerfTaskSource>
  missing?: boolean
  sourceNote?: string | null
  notApplicable?: boolean
  naReason?: string | null
  autoScore?: number | null
  score?: number | null
  countedInTotal?: boolean
}

export interface PerfCollectEmployee {
  employeeId: number
  employeeName: string
  employeeUsername: string
  items: PerfCollectItem[]
  previewTotal: number | null
  scoredWeight: number
  naWeight: number
  krCount: number
  taskCount: number
  warnings: string[]
}

export interface PerfCollectPreview {
  cycleId: number
  cycleCode: string
  cycleName: string
  startDate: string
  endDate: string
  templateId: number
  templateName: string | null
  mode: 'preview' | 'recalculate'
  employeeCount: number
  employees: PerfCollectEmployee[]
  warnings: string[]
}

/** 评分当前版本行（system 为归集只读，self 自评，manager 上级评） */
export interface PerfScoreRow {
  id: number
  cycleId: number
  employeeId: number
  employeeName: string | null
  templateItemId: number
  itemCode: string
  itemName: string
  itemCategory: ItemCategory
  weight: number
  raterType: RaterType
  raterId: number | null
  raterName: string | null
  autoScore: number | null
  score: number | null
  previousScore: number | null
  version: number
  comment: string | null
  notApplicable: boolean
  naReason: string | null
  naMarkedBy: string | null
  naMarkedTime: string | null
  createTime: string
  raterTypeLabel?: string
  itemCategoryLabel?: string
}

export interface PerfScoreWrite {
  itemId: number
  score: number
  comment: string
}

/** 绩效结果（归档/锁定后的固化结果） */
export interface PerfResult {
  id: number
  cycleId: number
  cycleCode: string
  cycleName: string
  employeeId: number
  employeeName: string | null
  employeeUsername: string | null
  templateId: number
  totalScore: number
  selfScore: number | null
  collectScore: number | null
  subjectiveScore: number | null
  effectiveWeight: number
  naWeight: number
  status: ResultStatus
  archiveTime: string | null
  lockTime: string | null
  remark: string | null
  createTime: string
  statusLabel?: string
}

/** 结果下钻：结果 + 周期 + 该员工当前归集明细（可追溯到 KR / 任务清单） */
export interface PerfResultTrace {
  result: PerfResult
  cycle: PerfCycle
  detail: PerfCollectEmployee
}

export interface PerfAppeal {
  id: number
  cycleId: number
  cycleCode: string
  cycleName: string
  resultId: number
  employeeId: number
  employeeName: string | null
  appealReason: string
  appealStatus: AppealStatus
  beforeScore: number | null
  afterScore: number | null
  handlerId: number | null
  handlerName: string | null
  handleComment: string | null
  handleTime: string | null
  applied: boolean
  createTime: string
  appealStatusLabel?: string
}

export interface PerfAppealHandle {
  approved: boolean
  comment?: string
  afterScore?: number | null
}

export function createPerfApi(client: HttpClient) {
  return {
    cycles: {
      list: (query?: QueryParams) => client.get<PageResult<PerfCycle>>('/perf/cycles', query),
      detail: (id: number) => client.get<PerfCycle>(`/perf/cycles/${id}`),
      create: (body: PerfCycleWrite) => client.post<IdResponse>('/perf/cycles', body),
      update: (id: number, body: PerfCycleWrite) => client.put<void>(`/perf/cycles/${id}`, body),
      /** 状态流转：仅接受 active（开启）/ scoring（进入评分）；归档与锁定走 results.archive/lock */
      updateStatus: (id: number, status: 'active' | 'scoring') => client.put<PerfCycle>(`/perf/cycles/${id}/status`, { status }),
      remove: (id: number) => client.delete<void>(`/perf/cycles/${id}`),
      statusMachine: () => client.get<Record<string, unknown>>('/perf/cycles/status-machine'),
    },
    templates: {
      list: (query?: QueryParams) => client.get<PageResult<PerfTemplate>>('/perf/templates', query),
      detail: (id: number) => client.get<PerfTemplate>(`/perf/templates/${id}`),
      create: (body: PerfTemplateWrite) => client.post<IdResponse>('/perf/templates', body),
      update: (id: number, body: PerfTemplateWrite) => client.put<void>(`/perf/templates/${id}`, body),
      remove: (id: number) => client.delete<void>(`/perf/templates/${id}`),
    },
    metrics: {
      list: () => client.get<PerfMetricDefinition[]>('/perf/metrics'),
    },
    collect: {
      /** 归集预览：只算不落库，返回每人的指标归集值、下钻来源与提示 */
      preview: (cycleId: number, employeeIds?: number[]) =>
        client.post<PerfCollectPreview>('/perf/collect/preview', employeeIds?.length ? { cycleId, employeeIds } : { cycleId }),
      /** 归集重算：覆盖写入当前版本归集值（幂等），固定周期末快照 */
      recalculate: (cycleId: number, employeeIds?: number[]) =>
        client.post<PerfCollectPreview>('/perf/collect/recalculate', employeeIds?.length ? { cycleId, employeeIds } : { cycleId }),
    },
    scores: {
      list: (query: QueryParams) => client.get<PageResult<PerfScoreRow>>('/perf/scores', query),
      /** 本人自评：被评人由后端取当前登录用户，只提交主观类指标项 */
      saveSelf: (cycleId: number, items: PerfScoreWrite[]) =>
        client.put<{ cycleId: number; employeeId: number; raterType: string; savedCount: number }>('/perf/scores/self', { cycleId, items }),
      /** 上级评分：需本人已完成自评（后端校验） */
      saveManager: (cycleId: number, employeeId: number, items: PerfScoreWrite[]) =>
        client.put<{ cycleId: number; employeeId: number; raterType: string; savedCount: number }>('/perf/scores/manager', { cycleId, employeeId, items }),
      /** D-06.5 例外状态标记：只有可归集指标可标「不适用」，标记后按剩余权重归一化 */
      markNotApplicable: (body: { cycleId: number; employeeId: number; itemId: number; notApplicable: boolean; reason?: string }) =>
        client.put<{ cycleId: number; employeeId: number; itemId: number; notApplicable: boolean; naReason: string | null }>('/perf/scores/not-applicable', body),
    },
    results: {
      list: (query: QueryParams) => client.get<PageResult<PerfResult>>('/perf/results', query),
      detail: (id: number) => client.get<PerfResult>(`/perf/results/${id}`),
      /** 可归集指标下钻：KR 清单 / 任务清单 / 人工校准量（DoD） */
      trace: (id: number) => client.get<PerfResultTrace>(`/perf/results/${id}/trace`),
      /** 结果归档：要求周期处于评分中，归档时按周期末快照重算并加权固化 */
      archive: (cycleId: number) => client.post<{ cycleId: number; status: string; archivedCount: number }>('/perf/results/archive', { cycleId }),
      /** 结果锁定：归档后执行，锁定不可逆（locked） */
      lock: (cycleId: number) => client.post<{ cycleId: number; status: string; lockedCount: number }>('/perf/results/lock', { cycleId }),
    },
    appeals: {
      list: (query: QueryParams) => client.get<PageResult<PerfAppeal>>('/perf/appeals', query),
      detail: (id: number) => client.get<PerfAppeal>(`/perf/appeals/${id}`),
      /** 提交申诉：锁定后修改结果的唯一通道，只能对本人结果发起 */
      create: (resultId: number, reason: string) => client.post<IdResponse>('/perf/appeals', { resultId, reason }),
      handle: (id: number, body: PerfAppealHandle) => client.put<PerfAppeal>(`/perf/appeals/${id}/handle`, body),
    },
  }
}

export const perfApi = createPerfApi(httpClient)
