/* M12 集成与开放 · 页面注册表
   已交付页面：
     integration/app/index  集成中心    → views/integration/IntegrationAppList.vue
     integration/key/index  开放 API Key → views/integration/ApiKeyList.vue
   登记方式：key 为去掉 placeholder/ 前缀后的 sys_menu.component 值。 */

import type { ModuleComponentMap } from './index'

export const integrationComponents: ModuleComponentMap = {
  'integration/app/index': () => import('@/views/integration/IntegrationAppList.vue'),
  'integration/key/index': () => import('@/views/integration/ApiKeyList.vue'),
}