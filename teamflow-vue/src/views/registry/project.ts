/* M14 项目管理 · 页面注册表
   已交付页面（仅立项 2.5.1）：
     project/list/index       项目列表 → views/project/ProjectList.vue
     project/milestone/index  里程碑   → views/project/MilestoneList.vue

   甘特视图、风险登记、项目复盘（需求 2.5.2 / 2.5.3 / 2.5.4）属后续里程碑、尚无接口，
   **保持占位页，不在本注册表登记**。

   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值。 */

import type { ModuleComponentMap } from './index'

export const projectComponents: ModuleComponentMap = {
  'project/list/index': () => import('@/views/project/ProjectList.vue'),
  'project/milestone/index': () => import('@/views/project/MilestoneList.vue'),
}
