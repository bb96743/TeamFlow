/* M08 招聘 · 页面注册表
   页面：job(职位管理) / candidate(候选人库) / interview(面试安排) / offer(Offer 管理)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值，
   例如 V6 菜单的 `placeholder/recruit/job/index` → 这里登记 `'recruit/job/index'`。 */

import type { ModuleComponentMap } from './index'

export const recruitComponents: ModuleComponentMap = {
  'recruit/job/index': () => import('@/views/recruit/Jobs.vue'),
  'recruit/candidate/index': () => import('@/views/recruit/Candidates.vue'),
  'recruit/interview/index': () => import('@/views/recruit/Interviews.vue'),
  'recruit/offer/index': () => import('@/views/recruit/Offers.vue'),
}
