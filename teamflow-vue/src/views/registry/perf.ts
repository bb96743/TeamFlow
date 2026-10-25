/* M05 绩效 · 页面注册表
   页面：cycle(绩效周期) / template(指标模板) / score(绩效评分) / result(结果归档)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/perf/cycle/index` → 这里登记 `'perf/cycle/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const perfComponents: ModuleComponentMap = {
  'perf/cycle/index': () => import('@/views/perf/Cycles.vue'),
  'perf/template/index': () => import('@/views/perf/Templates.vue'),
  'perf/score/index': () => import('@/views/perf/Scores.vue'),
  'perf/result/index': () => import('@/views/perf/Results.vue'),
}
