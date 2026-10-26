/* M13 统计与洞察 · 页面注册表
   计划页面：task(任务总览) / load(团队负载) / metric(指标口径)；cross 属 M3 暂不接。
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/insight/task/index` → 这里登记 `'insight/task/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const insightComponents: ModuleComponentMap = {
  'insight/task/index': () => import('@/views/insight/TaskOverview.vue'),
  'insight/load/index': () => import('@/views/insight/TeamLoad.vue'),
  'insight/metric/index': () => import('@/views/insight/Metrics.vue'),
}
