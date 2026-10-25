/* M04 团队与成员 · 页面注册表
   已交付页面：
     team/list/index      团队管理（含成员管理抽屉） → views/team/TeamList.vue
     team/workload/index  成员负载                   → views/team/TeamWorkload.vue
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/team/list/index` → 这里登记 `'team/list/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const teamComponents: ModuleComponentMap = {
  'team/list/index': () => import('@/views/team/TeamList.vue'),
  'team/workload/index': () => import('@/views/team/TeamWorkload.vue'),
}
