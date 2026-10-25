/* M03 任务与工作流 · 页面注册表
   页面：list(任务列表) / board(任务看板) / flow-def(流程定义) / approvals(审批中心)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/task/list/index` → 这里登记 `'task/list/index'`。 */

import type { ModuleComponentMap } from './index'

export const taskComponents: ModuleComponentMap = {
  'task/list/index': () => import('@/views/task/TaskList.vue'),
  'task/board/index': () => import('@/views/task/TaskBoard.vue'),
  'task/flow-def/index': () => import('@/views/task/FlowDefinitions.vue'),
  'task/approvals/index': () => import('@/views/task/Approvals.vue'),
}
