/* M09 协作与消息 · 页面注册表
   页面：message(站内信) / feed(动态流) / announcement(公告)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/collab/message/index` → 这里登记 `'collab/message/index'`。 */

import type { ModuleComponentMap } from './index'

export const collabComponents: ModuleComponentMap = {
  'collab/message/index': () => import('@/views/collab/Messages.vue'),
  'collab/feed/index': () => import('@/views/collab/Feed.vue'),
  'collab/announcement/index': () => import('@/views/collab/Announcements.vue'),
}
