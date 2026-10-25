/* M02 目标与 OKR · 页面注册表
   页面：period(目标周期) / objective(目标制定) / key-result(关键结果) / align(对齐检查)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值。
   注意：M02 的菜单在 V4 中已写为不带前缀的 `okr/period/index`，
   因此这里的 key 与菜单 component 完全一致，登记即生效。 */

import type { ModuleComponentMap } from './index'

export const okrComponents: ModuleComponentMap = {
  'okr/period/index': () => import('@/views/okr/Periods.vue'),
  'okr/objective/index': () => import('@/views/okr/Objectives.vue'),
  'okr/key-result/index': () => import('@/views/okr/KeyResults.vue'),
  'okr/align/index': () => import('@/views/okr/AlignCheck.vue'),
}
