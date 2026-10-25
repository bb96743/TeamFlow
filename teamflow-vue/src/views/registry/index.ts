/* ============================================================
   业务模块页面注册表
   ------------------------------------------------------------
   背景：sys_menu.component 由 V6 统一写为 `placeholder/<模块>/<页面>/index`。
   为了让「前端交付真实页面」与「尚未交付时仍显示占位页」共存，
   auth.ts 的 resolveComponent 会剥掉 placeholder/ 前缀后再查本注册表：

     placeholder/okr/period/index  →  查 'okr/period/index'
       命中 → 用真实组件
       未命中 → 落回 Placeholder.vue（规划中页面）

   因此**新增真实页面时不需要改数据库**，也不需要改 auth.ts，
   只需在自己的模块注册表里登记一行即可。

   约定：每个业务模块一个文件，模块之间不共享文件，避免并发编辑冲突。
   ============================================================ */

import type { Component } from 'vue'

/** 单个页面组件的懒加载签名，与 COMPONENT_MAP 保持一致 */
export type PageComponentLoader = () => Promise<{ default: Component }>

/** 模块注册表：key 必须是去掉 `placeholder/` 前缀后的 component 值 */
export type ModuleComponentMap = Record<string, PageComponentLoader>

import { okrComponents } from './okr'
import { taskComponents } from './task'
import { perfComponents } from './perf'
import { personalComponents } from './personal'
import { teamComponents } from './team'

/** 全部业务模块注册表的聚合结果（供 auth.ts 合并进 COMPONENT_MAP） */
export const moduleComponentMap: ModuleComponentMap = {
  ...okrComponents,
  ...taskComponents,
  ...perfComponents,
  ...personalComponents,
  ...teamComponents,
}
