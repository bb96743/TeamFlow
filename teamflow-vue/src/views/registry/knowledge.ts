/* M07 知识与文档 · 页面注册表
   计划页面：space(知识空间) / page(页面编辑) / search(全文检索)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/knowledge/space/index` → 这里登记 `'knowledge/space/index'`。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const knowledgeComponents: ModuleComponentMap = {
  'knowledge/space/index': () => import('@/views/knowledge/Spaces.vue'),
  'knowledge/page/index': () => import('@/views/knowledge/PageEditor.vue'),
  'knowledge/search/index': () => import('@/views/knowledge/Search.vue'),
}
