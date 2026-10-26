/* M11 AI 助手 · 页面注册表
   计划页面：chat(对话助手) / qa(知识问答) / report(日报周报)
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值。
   未登记的页面仍显示占位页（规划中），因此可以逐页交付。 */

import type { ModuleComponentMap } from './index'

export const aiComponents: ModuleComponentMap = {
  'ai/chat/index': () => import('@/views/ai/AiChat.vue'),
  'ai/qa/index': () => import('@/views/ai/AiQa.vue'),
  'ai/report/index': () => import('@/views/ai/AiReport.vue'),
}
