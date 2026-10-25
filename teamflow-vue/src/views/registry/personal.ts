/* M10 个人效率 · 页面注册表
   已交付页面：
     personal/todo/index     待办收件箱   → views/personal/TodoInbox.vue
     personal/time/index     工时记录     → views/personal/TimeLog.vue
     personal/review/index   工作复盘     → views/personal/ReviewLog.vue
     personal/profile/index  效能画像     → views/personal/Profile.vue
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/personal/todo/index` → 这里登记 `'personal/todo/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const personalComponents: ModuleComponentMap = {
  'personal/todo/index': () => import('@/views/personal/TodoInbox.vue'),
  'personal/time/index': () => import('@/views/personal/TimeLog.vue'),
  'personal/review/index': () => import('@/views/personal/ReviewLog.vue'),
  'personal/profile/index': () => import('@/views/personal/Profile.vue'),
}
