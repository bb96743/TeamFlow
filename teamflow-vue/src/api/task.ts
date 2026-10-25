import { httpClient, type HttpClient, type QueryParams } from './http.ts'
import type { PageResult } from './system.ts'

/* ============================================================
   M03 任务与工作流接口（/api/task）
   ------------------------------------------------------------
   与后端 TaskController / TaskFlowController 一一对应，权限码见 V8__task_menu.sql
   （task:task:* / flow:def:* / flow:instance:*）。
   状态流转的合法目标**不在这里判断**：统一取 `/task/flow/status-machine`
   返回的迁移表（后端 TaskStatusMachine 是唯一来源），前端只负责渲染。
   ============================================================ */

/** 任务优先级 */
export type TaskPriority = 'low' | 'normal' | 'high' | 'urgent'

export interface TaskRow {
  id: number
  taskTitle: string
  taskDesc?: string | null
  taskType?: string | null
  status: string
  statusLabel?: string | null
  priority?: TaskPriority | string | null
  creatorId?: number | null
  creatorName?: string | null
  assigneeId?: number | null
  assigneeName?: string | null
  deptId?: number | null
  /** 关联的关键结果（任务↔KR 权威关联，可空） */
  krId?: number | null
  parentTaskId?: number | null
  rootTaskId?: number | null
  isRoot?: boolean | null
  startDate?: string | null
  dueDate?: string | null
  doneTime?: string | null
  subTaskCount?: number | null
  doneSubTaskCount?: number | null
  subProgress?: number | null
  sort?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface TaskAssigneeRow {
  id: number
  taskId: number
  userId: number
  userName?: string | null
  assignType?: string | null
  assignTime?: string
  assignedBy?: number | null
  remark?: string | null
}

export interface TaskDetail extends TaskRow {
  assignees?: TaskAssigneeRow[]
  children?: TaskRow[]
  /** 后端按状态机算出的合法目标状态（前端不自行推断） */
  allowedTransitions?: string[]
}

export interface TaskTraceRow {
  id: number
  instanceId: number
  instanceType?: string | null
  nodeKey?: string | null
  nodeName?: string | null
  nodeStatus?: string | null
  fromStatus?: string | null
  toStatus?: string | null
  operatorId?: number | null
  operatorName?: string | null
  opinion?: string | null
  operatedTime?: string
}

export interface TaskWriteRequest {
  taskTitle?: string
  taskDesc?: string | null
  krId?: number | null
  assigneeId?: number | null
  parentTaskId?: number | null
  priority?: string
  /** yyyy-MM-dd */
  startDate?: string | null
  /** yyyy-MM-dd */
  dueDate?: string | null
  sort?: number | null
  remark?: string | null
}

export interface TaskUpdateRequest {
  taskTitle?: string
  taskDesc?: string | null
  krId?: number | null
  priority?: string
  startDate?: string | null
  dueDate?: string | null
  sort?: number | null
  remark?: string | null
}

export interface TaskAssignRequest {
  /** 传 0 或空表示收回指派，任务回到待认领池 */
  assigneeId?: number | null
  remark?: string | null
}

export interface TaskStatusRequest {
  toStatus: string
  opinion?: string | null
}

export interface TaskApprovalRequest {
  targetStatus: string
  approverId?: number | null
  opinion?: string | null
}

/** 状态机定义（后端 TaskStatusMachine 导出的可查询视图） */
export interface TaskStatusState {
  code: string
  label: string
  terminal: boolean
  requiresAssignee: boolean
}

export interface TaskStatusMachine {
  definitionCode?: string
  states: TaskStatusState[]
  /** from 状态 → 允许迁移到的目标状态列表 */
  transitions: Record<string, string[]>
}

export interface FlowDefinitionRow {
  id: number
  defCode: string
  defName: string
  businessType?: string | null
  nodesJson?: string | null
  status?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface FlowDefinitionWriteRequest {
  defCode?: string
  defName?: string
  businessType?: string
  nodesJson?: string | null
  status?: number | null
  remark?: string | null
}

export interface FlowInstanceRow {
  id: number
  definitionId?: number | null
  defCode?: string | null
  instanceType?: string | null
  bizId?: number | null
  bizTitle?: string | null
  currentNode?: string | null
  targetStatus?: string | null
  targetStatusLabel?: string | null
  status?: string | null
  startBy?: number | null
  startByName?: string | null
  startTime?: string
  endTime?: string | null
  remark?: string | null
}

export interface FlowTaskRow {
  id: number
  instanceId: number
  bizId?: number | null
  nodeKey?: string | null
  taskTitle?: string | null
  assigneeId?: number | null
  assigneeName?: string | null
  status?: string | null
  opinion?: string | null
  operateTime?: string | null
}

export interface FlowInstanceDetail extends FlowInstanceRow {
  nodes?: TaskTraceRow[]
  flowTasks?: FlowTaskRow[]
}

export interface TaskApprovalDecisionRequest {
  approved?: boolean
  opinion?: string | null
}

export interface TaskApi {
  tasks: {
    list: (query?: QueryParams) => Promise<PageResult<TaskRow>>
    my: (query?: QueryParams) => Promise<PageResult<TaskRow>>
    pool: (query?: QueryParams) => Promise<PageResult<TaskRow>>
    detail: (id: number) => Promise<TaskDetail>
    traces: (id: number) => Promise<TaskTraceRow[]>
    create: (body: TaskWriteRequest) => Promise<{ id: number }>
    update: (id: number, body: TaskUpdateRequest) => Promise<void>
    assign: (id: number, body: TaskAssignRequest) => Promise<Record<string, unknown>>
    claim: (id: number) => Promise<Record<string, unknown>>
    transition: (id: number, body: TaskStatusRequest) => Promise<Record<string, unknown>>
    startApproval: (id: number, body: TaskApprovalRequest) => Promise<{ id: number }>
    remove: (id: number) => Promise<void>
  }
  flow: {
    statusMachine: () => Promise<TaskStatusMachine>
    definitions: (query?: QueryParams) => Promise<PageResult<FlowDefinitionRow>>
    createDefinition: (body: FlowDefinitionWriteRequest) => Promise<{ id: number }>
    updateDefinition: (id: number, body: FlowDefinitionWriteRequest) => Promise<void>
    instances: (query?: QueryParams) => Promise<PageResult<FlowInstanceRow>>
    instanceDetail: (id: number) => Promise<FlowInstanceDetail>
    approve: (id: number, body: TaskApprovalDecisionRequest) => Promise<Record<string, unknown>>
  }
}

export function createTaskApi(client: HttpClient): TaskApi {
  return {
    tasks: {
      list: query => client.get<PageResult<TaskRow>>('/task/tasks', query),
      my: query => client.get<PageResult<TaskRow>>('/task/tasks/my', query),
      pool: query => client.get<PageResult<TaskRow>>('/task/tasks/pool', query),
      detail: id => client.get<TaskDetail>(`/task/tasks/${id}`),
      traces: id => client.get<TaskTraceRow[]>(`/task/tasks/${id}/traces`),
      create: body => client.post<{ id: number }>('/task/tasks', body),
      update: (id, body) => client.put<void>(`/task/tasks/${id}`, body),
      assign: (id, body) => client.put<Record<string, unknown>>(`/task/tasks/${id}/assign`, body),
      claim: id => client.put<Record<string, unknown>>(`/task/tasks/${id}/claim`),
      transition: (id, body) => client.put<Record<string, unknown>>(`/task/tasks/${id}/status`, body),
      startApproval: (id, body) => client.post<{ id: number }>(`/task/tasks/${id}/approvals`, body),
      remove: id => client.delete<void>(`/task/tasks/${id}`),
    },
    flow: {
      statusMachine: () => client.get<TaskStatusMachine>('/task/flow/status-machine'),
      definitions: query => client.get<PageResult<FlowDefinitionRow>>('/task/flow/definitions', query),
      createDefinition: body => client.post<{ id: number }>('/task/flow/definitions', body),
      updateDefinition: (id, body) => client.put<void>(`/task/flow/definitions/${id}`, body),
      instances: query => client.get<PageResult<FlowInstanceRow>>('/task/flow/instances', query),
      instanceDetail: id => client.get<FlowInstanceDetail>(`/task/flow/instances/${id}`),
      approve: (id, body) => client.put<Record<string, unknown>>(`/task/flow/instances/${id}/approve`, body),
    },
  }
}

export const taskApi = createTaskApi(httpClient)
