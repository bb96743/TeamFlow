import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectApi, type ProjectRow } from '@/api/project'
import { toastError } from '@/composables/toast'

/* ============================================================
   扩展页共用的项目选择器（甘特 / 风险 / 复盘）
   ------------------------------------------------------------
   页面按项目维护，进入时读取当前账号可访问的项目；
   地址栏带 projectId 时优先预选（便于从项目列表跳转）。
   ============================================================ */
export function useProjectPicker() {
  const route = useRoute()
  const projects = ref<ProjectRow[]>([])
  const selectedProjectId = ref<number | null>(null)
  const loading = ref(false)

  const options = computed(() => projects.value.map(item => ({
    label: `${item.projectName}（${item.projectCode}）`,
    value: item.id,
    hint: item.statusLabel,
  })))
  const selectedProject = computed(
    () => projects.value.find(item => item.id === selectedProjectId.value) ?? null,
  )

  async function loadProjects() {
    loading.value = true
    try {
      const page = await projectApi.projects.list({ pageNum: 1, pageSize: 100 })
      projects.value = page.rows
      const queryProjectId = Number(route.query.projectId)
      if (queryProjectId && projects.value.some(item => item.id === queryProjectId)) {
        selectedProjectId.value = queryProjectId
      } else if (projects.value.length) {
        selectedProjectId.value = projects.value[0].id
      }
    } catch (error) {
      toastError(error, '项目列表加载失败')
    } finally {
      loading.value = false
    }
  }

  function selectProject(value: string | number | null | undefined) {
    selectedProjectId.value = value === null || value === undefined || value === '' ? null : Number(value)
  }

  return { projects, selectedProjectId, selectedProject, options, loading, loadProjects, selectProject }
}
