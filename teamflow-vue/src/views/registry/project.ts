/* M14 项目管理 · 页面注册表
   已交付页面：
     project/list/index       项目列表 → views/project/ProjectList.vue
     project/milestone/index  里程碑   → views/project/MilestoneList.vue
     project/gantt/index      甘特视图 → views/project/GanttView.vue（需求 2.5.2）
     project/risk/index       风险登记 → views/project/RiskList.vue（需求 2.5.3）
     project/retro/index      项目复盘 → views/project/RetroView.vue（需求 2.5.4）

   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值。 */

import type { ModuleComponentMap } from './index'

export const projectComponents: ModuleComponentMap = {
  'project/list/index': () => import('@/views/project/ProjectList.vue'),
  'project/milestone/index': () => import('@/views/project/MilestoneList.vue'),
  // M3 扩展页面（需求 2.5.2 甘特 / 2.5.3 风险 / 2.5.4 复盘）
  'project/gantt/index': () => import('@/views/project/GanttView.vue'),
  'project/risk/index': () => import('@/views/project/RiskList.vue'),
  'project/retro/index': () => import('@/views/project/RetroView.vue'),
}
