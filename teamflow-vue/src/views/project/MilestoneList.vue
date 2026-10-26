<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Button,
  DataTable,
  EmptyState,
  Field,
  PageHeader,
  Panel,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, PermissionButton } from '@/components/business'
import {
  projectApi,
  type KrOption,
  type ProjectDetail,
  type ProjectMilestoneRow,
  type ProjectMilestoneWriteRequest,
  type ProjectRow,
  type ProjectStatusMachine,
} from '@/api/project'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   里程碑（M14 · 2.5.1 里程碑）
   ------------------------------------------------------------
   里程碑按项目维护。完成度 = 关联 KR 的顶层任务完成率，由任务模块
   实时汇总（对所有人一致，接口不接受手填），页面只展示结果；
   延期判定由后端统一口径，页面不自行计算。
   甘特/时间线视图属 M3（2.5.2），本页不涉及。
   ============================================================ */

const route = useRoute()

const projects = ref<ProjectRow[]>([])
const selectedProjectId = ref<number | null>(null)
const projectDetail = ref<ProjectDetail | null>(null)
const milestones = ref<ProjectMilestoneRow[]>([])
const loading = ref(false)
const projectsLoading = ref(true)
const statusMachine = ref<ProjectStatusMachine | null>(null)
const krOptions = ref<KrOption[]>([])

const projectOptions = computed(() => projects.value.map(item => ({
  label: `${item.projectName}（${item.projectCode}）`,
  value: item.id,
  hint: item.statusLabel,
})))
/* 状态机加载失败时的兜底文案（仅展示用；允许迁移以后端返回为准） */
const PROJECT_LABEL_FALLBACK: Record<string, string> = {
  draft: '立项草稿', active: '进行中', closed: '已结项', archived: '已归档',
}
const MILESTONE_LABEL_FALLBACK: Record<string, string> = {
  planned: '计划中', in_progress: '进行中', completed: '已完成', cancelled: '已取消',
}
const milestoneLabelOf = (code: string | null | undefined) =>
  statusMachine.value?.milestone.states.find(state => state.code === code)?.label
  ?? (code ? MILESTONE_LABEL_FALLBACK[code] : undefined)
  ?? code
  ?? '—'
const projectLabelOf = (code: string | null | undefined) =>
  statusMachine.value?.project.states.find(state => state.code === code)?.label
  ?? (code ? PROJECT_LABEL_FALLBACK[code] : undefined)
  ?? code
  ?? '—'

const columns: Column[] = [
  { key: 'milestoneName', title: '里程碑', minWidth: 230 },
  { key: 'status', title: '状态', width: 110 },
  { key: 'plan', title: '计划起止', width: 200 },
  { key: 'actual', title: '实际起止', width: 200 },
  { key: 'completion', title: '完成度（按关联 KR 顶层任务）', minWidth: 220 },
  { key: 'delayed', title: '延期', width: 88 },
  { key: 'actions', title: '操作', width: 96, className: 'col-actions' },
]

const editable = computed(() => projectDetail.value?.editable !== false)
const currentProject = computed(() => projects.value.find(item => item.id === selectedProjectId.value) ?? null)

async function loadBasics() {
  try {
    statusMachine.value = await projectApi.projects.statusMachine()
  } catch {
    statusMachine.value = null
  }
  projectsLoading.value = true
  try {
    const page = await projectApi.projects.list({ pageNum: 1, pageSize: 100 })
    projects.value = page.rows
    const queryProjectId = Number(route.query.projectId)
    if (queryProjectId && projects.value.some(item => item.id === queryProjectId)) {
      selectedProjectId.value = queryProjectId
    } else if (projects.value.length) {
      selectedProjectId.value = projects.value[0].id
    }
    if (selectedProjectId.value) await loadProject()
  } catch (error) {
    toastError(error, '项目列表加载失败')
  } finally {
    projectsLoading.value = false
  }
}

async function loadProject() {
  if (!selectedProjectId.value) return
  loading.value = true
  try {
    projectDetail.value = await projectApi.projects.detail(selectedProjectId.value)
    milestones.value = projectDetail.value.milestones ?? []
    await loadKeyResults()
  } catch (error) {
    toastError(error, '里程碑加载失败')
  } finally {
    loading.value = false
  }
}

/** 里程碑可绑定的 KR 来自项目关联目标（目标模块公开只读接口，不复制数据）。 */
async function loadKeyResults() {
  const objectiveId = projectDetail.value?.objectiveId
  if (!objectiveId) {
    krOptions.value = []
    return
  }
  try {
    krOptions.value = await projectApi.keyResults(objectiveId)
  } catch {
    krOptions.value = []
  }
}

function switchProject() {
  projectDetail.value = null
  milestones.value = []
  loadProject()
}

/* ── 里程碑表单（完成度不可手填，故表单无该字段） ── */
interface MilestoneForm {
  id?: number
  milestoneName: string
  milestoneDesc: string
  krId: number | null
  planStartDate: string
  planEndDate: string
  actualStartDate: string
  actualEndDate: string
  status: string
  sort: number | null
  remark: string
}
const emptyForm = (): MilestoneForm => ({
  milestoneName: '',
  milestoneDesc: '',
  krId: null,
  planStartDate: '',
  planEndDate: '',
  actualStartDate: '',
  actualEndDate: '',
  status: 'planned',
  sort: 10,
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<MilestoneForm>(emptyForm())
const errors = ref<Partial<Record<keyof MilestoneForm, string>>>({})

const krSelectOptions = computed(() => {
  const list = krOptions.value.map(item => ({ label: item.krTitle, value: item.id, hint: item.krType === 'task_driven' ? '任务驱动型' : '量化型' }))
  if (form.krId && !list.some(item => item.value === form.krId)) {
    list.unshift({ label: `KR #${form.krId}（当前绑定）`, value: form.krId, hint: '' })
  }
  return list
})

const statusChoices = computed(() => {
  const current = form.status
  const allowed = statusMachine.value?.milestone.transitions[current] ?? []
  return [current, ...allowed].filter((code, index, all) => all.indexOf(code) === index).map(code => ({
    label: milestoneLabelOf(code),
    value: code,
  }))
})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: ProjectMilestoneRow) {
  Object.assign(form, {
    id: row.id,
    milestoneName: row.milestoneName,
    milestoneDesc: row.milestoneDesc ?? '',
    krId: row.krId ?? null,
    planStartDate: row.planStartDate ?? '',
    planEndDate: row.planEndDate ?? '',
    actualStartDate: row.actualStartDate ?? '',
    actualEndDate: row.actualEndDate ?? '',
    status: row.status,
    sort: row.sort ?? 10,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitMilestone() {
  if (!selectedProjectId.value) return
  const nextErrors = validate(form, { milestoneName: [required('请填写里程碑名称')] })
  if (form.planStartDate && form.planEndDate && form.planEndDate < form.planStartDate) {
    nextErrors.planEndDate = '计划完成日期不能早于计划开始日期'
  }
  errors.value = nextErrors
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: ProjectMilestoneWriteRequest = {
    milestoneName: form.milestoneName.trim(),
    milestoneDesc: form.milestoneDesc.trim() || null,
    krId: form.krId,
    planStartDate: form.planStartDate || null,
    planEndDate: form.planEndDate || null,
    actualStartDate: form.actualStartDate || null,
    actualEndDate: form.actualEndDate || null,
    status: form.id ? form.status : 'planned',
    sort: form.sort ?? 0,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await projectApi.milestones.update(selectedProjectId.value, form.id, payload)
      pushToast('里程碑已更新')
    } else {
      await projectApi.milestones.create(selectedProjectId.value, payload)
      pushToast('里程碑已创建，状态为计划中')
    }
    dialog.value = false
    await loadProject()
  } catch (error) {
    toastError(error, '里程碑保存失败')
  } finally {
    saving.value = false
  }
}

async function removeMilestone(row: ProjectMilestoneRow) {
  if (!selectedProjectId.value) return
  const ok = await confirm({
    title: '删除里程碑',
    message: `确认删除里程碑「${row.milestoneName}」吗？删除后不再计入项目完成度汇总。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await projectApi.milestones.remove(selectedProjectId.value, row.id)
    pushToast('里程碑已删除')
    await loadProject()
  } catch (error) {
    toastError(error, '里程碑删除失败')
  }
}

onMounted(loadBasics)
</script>

<template>
  <div class="page">
    <PageHeader
      title="里程碑"
      desc="按项目维护阶段性交付节点。完成度按里程碑所关联 KR 的顶层任务完成率由任务模块实时汇总，同一项目对不同账号结果一致；完成度不可手填，延期判定也以后端统一口径为准。"
    >
      <Select
        :model-value="selectedProjectId"
        :options="projectOptions"
        :placeholder="projectsLoading ? '项目加载中…' : '请选择项目'"
        filterable
        @update:model-value="selectedProjectId = $event === null || $event === undefined || $event === '' ? null : Number($event); switchProject()"
      />
      <Button variant="secondary" icon="refresh" :loading="loading" @click="loadProject">刷新</Button>
    </PageHeader>

    <template v-if="!selectedProjectId && !projectsLoading">
      <EmptyState
        title="请先选择项目"
        desc="里程碑按项目维护；如果还没有项目，可以到「项目列表」先完成立项。"
        icon="grid"
      />
    </template>

    <template v-else-if="projectDetail">
      <section class="band reveal">
        <div class="band-head">
          <span class="section-label">
            {{ projectDetail.projectName }} · {{ projectDetail.statusLabel || projectLabelOf(projectDetail.status) }}
          </span>
          <div class="band-tools">
            <Tag v-if="!editable" tone="neutral">项目已结项/已归档，里程碑只读</Tag>
            <PermissionButton
              v-if="editable"
              perm="project:milestone:add"
              variant="primary"
              icon="plus"
              @click="openCreate"
            >新增里程碑</PermissionButton>
          </div>
        </div>
        <p class="cell-sub">
          项目关联目标：{{ projectDetail.objective && !projectDetail.objective.missing ? projectDetail.objective.objectiveTitle : (projectDetail.noObjectiveReason || '未关联目标') }}
          <span v-if="projectDetail.progress.projectProgress != null">
            · 项目完成度 {{ projectDetail.progress.projectProgress }}%（已绑定 KR 的里程碑完成度平均）
          </span>
          <span v-else> · {{ projectDetail.progress.note || '暂无可汇总完成度' }}</span>
        </p>
      </section>

      <Panel title="里程碑列表" :meta="`共 ${milestones.length} 个`">
        <DataTable :columns="columns" :data="milestones" row-key="id" :loading="loading" :min-width="980">
          <template #cell-milestoneName="{ row }">
            <div>
              <div class="cell-title">{{ row.milestoneName }}</div>
              <div class="cell-sub">{{ row.milestoneDesc || (row.krId ? `关联 KR #${row.krId}` : '未绑定 KR') }}</div>
            </div>
          </template>
          <template #cell-status="{ row }">
            <Tag :tone="row.status === 'completed' ? 'ok' : row.status === 'cancelled' ? 'neutral' : 'info'" dot>
              {{ row.statusLabel || milestoneLabelOf(row.status) }}
            </Tag>
          </template>
          <template #cell-plan="{ row }">
            <span class="tnum soft">{{ row.planStartDate || '—' }} 至 {{ row.planEndDate || '—' }}</span>
          </template>
          <template #cell-actual="{ row }">
            <span class="tnum soft">{{ row.actualStartDate || '—' }} 至 {{ row.actualEndDate || '—' }}</span>
          </template>
          <template #cell-completion="{ row }">
            <div>
              <template v-if="row.completion != null">
                <span class="tnum">{{ row.completion }}%</span>
                <div class="cell-sub">已完成 {{ row.taskDone ?? 0 }} / 顶层任务 {{ row.taskTotal ?? 0 }}</div>
              </template>
              <span v-else class="cell-sub">{{ row.completionNote || '暂无可汇总的完成度' }}</span>
            </div>
          </template>
          <template #cell-delayed="{ row }">
            <Tag v-if="row.delayed" tone="danger" dot>延期</Tag>
            <span v-else class="muted">—</span>
          </template>
          <template #cell-actions="{ row }">
            <div class="row-actions">
              <PermissionButton
                v-if="editable"
                perm="project:milestone:edit"
                icon-only
                icon="edit"
                label="编辑里程碑"
                @click="openEdit(row)"
              />
              <PermissionButton
                v-if="editable"
                perm="project:milestone:remove"
                icon-only
                icon="trash"
                label="删除里程碑"
                danger
                @click="removeMilestone(row)"
              />
              <span v-if="!editable" class="cell-sub">只读</span>
            </div>
          </template>
        </DataTable>
        <p class="faint" style="margin: 14px 0 0">
          完成度按关联 KR 的顶层任务统计：由任务模块实时汇总，页面不提供手填入口；未绑定 KR 或关联 KR 暂无顶层任务时不显示百分比，改为说明原因。
        </p>
      </Panel>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑里程碑' : '新增里程碑'"
      sub="里程碑完成度由关联 KR 的顶层任务实时汇总，不能手工填写；状态推进到「进行中 / 已完成」时，实际日期由后端自动补齐。"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submitMilestone"
    >
      <Field label="里程碑名称" required :error="errors.milestoneName" wide>
        <TextInput v-model="form.milestoneName" placeholder="如：一期功能上线" :invalid="!!errors.milestoneName" :maxlength="200" />
      </Field>
      <Field label="关联 KR" hint="可空；绑定后完成度按该 KR 的顶层任务统计">
        <Select v-model="form.krId" :options="krSelectOptions" placeholder="选择关键结果（可空）" filterable clearable />
      </Field>
      <Field label="状态" group :hint="form.id ? '仅可流转到允许的下一个状态' : '新建固定为计划中'">
        <Select v-if="form.id" v-model="form.status" :options="statusChoices" />
        <TextInput v-else :model-value="milestoneLabelOf('planned')" readonly />
      </Field>
      <Field label="显示排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="计划开始" hint="yyyy-MM-dd">
        <TextInput v-model="form.planStartDate" placeholder="如：2026-11-01" />
      </Field>
      <Field label="计划完成" :error="errors.planEndDate" hint="yyyy-MM-dd">
        <TextInput v-model="form.planEndDate" placeholder="如：2026-12-15" :invalid="!!errors.planEndDate" />
      </Field>
      <Field label="实际开始" hint="可空，留空由后端在流转时自动补">
        <TextInput v-model="form.actualStartDate" placeholder="如：2026-11-03" />
      </Field>
      <Field label="实际完成" hint="可空，完成时由后端自动补">
        <TextInput v-model="form.actualEndDate" placeholder="如：2026-12-14" />
      </Field>
      <Field label="里程碑说明" span>
        <TextInput v-model="form.milestoneDesc" multiline :rows="2" placeholder="这个里程碑的交付内容与验收标准" :maxlength="1000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>
  </div>
</template>
