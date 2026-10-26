/* M06 日程与会议 · 页面注册表
   已交付页面：
     meeting/calendar/index  日程日历   → views/meeting/Calendar.vue
     meeting/list/index      会议管理   → views/meeting/MeetingList.vue
     meeting/room/index      会议室     → views/meeting/RoomList.vue
     meeting/template/index  会议模板   → views/meeting/TemplateList.vue
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/meeting/calendar/index` → 这里登记 `'meeting/calendar/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const meetingComponents: ModuleComponentMap = {
  'meeting/calendar/index': () => import('@/views/meeting/Calendar.vue'),
  'meeting/list/index': () => import('@/views/meeting/MeetingList.vue'),
  'meeting/room/index': () => import('@/views/meeting/RoomList.vue'),
  'meeting/template/index': () => import('@/views/meeting/TemplateList.vue'),
}
