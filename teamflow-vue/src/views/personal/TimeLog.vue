<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Field, Panel, Segmented, Select, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import {
  personalApi,
  type MyTaskOption,
  type TimeLogRow,
  type TimeLogWriteRequest,
  type TimeSummaryRow,
} from '@/api/personal'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   工时记录（M10 · 2.3.2）
   ------------------------------------------------------------
   工时以任务为粒度：每条记录必须选择任务，不接受无归属工时。
   支持「工时明细」与「按任务汇总」两种视图；汇总由后端实时计算。
   ============================================================ */

type ViewMode = 'detail' | 'summary'
const view = ref<ViewMode>('detail')
const VIEWS = [
  { label: '工时明细', value: 'detail' as ViewMode },
  { label: '按任务汇总', value: 'summary' as ViewMode },
]

const list = useListPage<TimeLogRow, { taskId: string; fromDate: string; toDate: string }>({
  query: { taskId: '', fromDate: '', toDate: '' },
  fetch: params => personalApi.timeLogs.list(params),
})

const tasks = ref<MyTaskOption[]>([])
const taskTitleOf = (taskId: number) => tasks.value.find(item => item.id === taskId)?.taskTitle ?? `任务 #${taskId}`
const taskOptions = computed(() => tasks.value.map(item => ({
  label: item.taskTitle,
  value: item.id,
  hint: item.statusLabel || item.status,
})))

onMounted(async () => {
  try {
    const page = await personalApi.myTasks()
    tasks.value = page.rows
  } catch {
    tasks.value = []
  }
})

/* ── 明细表 ── */
const columns: Column[] = [
  { key: 'workDate', title: '工作日期', width: 132 },
  { key: 'taskId', title: '关联任务', minWidth: 240 },
  { key: 'durationMinutes', title: '工时', width: 110, align: 'right' },
  { key: 'description', title: '工作内容', minWidth: 200 },
  { key: 'createTime', title: '填报时间', width: 148 },
  { key: 'actions', title: '操作', width: 96, className: 'col-actions' },
]

/* ── 按任务汇总（后端实时计算，前端不自行统计） ── */
const summaryRows = ref<TimeSummaryRow[]>([])
const summaryLoading = ref(false)
const summaryRange = reactive({ fromDate: '', toDate: '' })
const summaryColumns: Column[] = [
  { key: 'taskId', title: '关联任务', minWidth: 260 },
  { key: 'totalMinutes', title: '累计工时（分钟）', width: 168, align: 'right' },
  { key: 'totalHours', title: '折合小时', width: 140, align: 'right' },
  { key: 'logCount', title: '记录条数', width: 120, align: 'right' },
]
const summaryTotal = computed(() => summaryRows.value.reduce((sum, row) => sum + Number(row.totalMinutes ?? 0), 0))

async function loadSummary() {
  summaryLoading.value = true
  try {
    summaryRows.value = await personalApi.timeLogs.summary({
      fromDate: summaryRange.fromDate || undefined,
      toDate: summaryRange.toDate || undefined,
    })
  } catch (error) {
    toastError(error, '工时汇总加载失败')
  } finally {
    summaryLoading.value = false
  }
}

/* ── 填报 / 编辑 ── */
interface TimeForm {
  id?: number
  taskId: number | null
  workDate: string
  durationMinutes: number | null
  startTime: string
  endTime: string
  description: string
}
const dialog = ref(false)
const saving = ref(false)
const form = reactive<TimeForm>(emptyForm())
const errors = ref<Partial<Record<keyof TimeForm, string>>>({})

function today() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

function emptyForm(): TimeForm {
  return { taskId: null, workDate: today(), durationMinutes: null, startTime: '', endTime: '', description: '' }
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: TimeLogRow) {
  Object.assign(form, {
    id: row.id,
    taskId: row.taskId,
    workDate: row.workDate,
    durationMinutes: row.durationMinutes,
    startTime: row.startTime ?? '',
    endTime: row.endTime ?? '',
    description: row.description ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    taskId: [required('请选择这条工时对应的任务（不接受无归属工时）')],
    workDate: [required('请填写工作日期')],
    durationMinutes: [required('请填写工时分钟数')],
  })
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: TimeLogWriteRequest = {
    taskId: form.taskId,
    workDate: form.workDate,
    durationMinutes: form.durationMinutes,
    startTime: form.startTime || undefined,
    endTime: form.endTime || undefined,
    description: form.description.trim() || undefined,
  }
  try {
    if (form.id) {
      await personalApi.timeLogs.update(form.id, payload)
      pushToast('工时已更新')
    } else {
      await personalApi.timeLogs.create(payload)
      pushToast('工时已记录')
    }
    dialog.value = false
    await list.load()
    if (view.value === 'summary') loadSummary()
  } catch (error) {
    // 后端会拒绝无任务或非法时长的工时，原文直接展示
    toastError(error, '工时保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: TimeLogRow) {
  const ok = await confirm({
    title: '删除工时记录',
    message: `确认删除 ${row.workDate} 的 ${row.durationMinutes} 分钟工时吗？删除后不再计入成本核算。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await personalApi.timeLogs.remove(row.id)
    pushToast('工时记录已删除')
    await list.load()
    if (view.value === 'summary') loadSummary()
  } catch (error) {
    toastError(error, '工时删除失败')
  }
}

function switchView(next: ViewMode) {
  view.value = next
  if (next === 'summary') loadSummary()
}
</script>

<template>
  <ListPage
    title="工时记录"
    desc="工时按任务填报，用于真实投入与成本核算。每条工时都必须关联任务；按任务汇总由后端实时计算，页面不另行估算。"
    table-title="工时明细"
    :list="list"
    :columns="columns"
    :alt-view="view === 'summary'"
  >
    <template #actions>
      <Segmented :model-value="view" label="视图" :options="VIEWS" @update:model-value="switchView" />
      <PermissionButton perm="personal:time:add" variant="primary" icon="plus" @click="openCreate">填报工时</PermissionButton>
    </template>

    <template #alt>
      <Panel title="按任务汇总工时" :meta="`共 ${summaryRows.length} 个任务`">
        <template #tools>
          <TextInput v-model="summaryRange.fromDate" placeholder="开始日期 yyyy-MM-dd" clearable @enter="loadSummary" />
          <TextInput v-model="summaryRange.toDate" placeholder="结束日期 yyyy-MM-dd" clearable @enter="loadSummary" />
          <Button variant="secondary" icon="search" :loading="summaryLoading" @click="loadSummary">汇总</Button>
        </template>
        <div class="band-head">
          <span class="cell-title">区间累计 {{ summaryTotal }} 分钟</span>
          <span class="cell-sub">按任务归集；系统不允许填报无归属工时，因此汇总不会出现「未关联任务」的行。</span>
        </div>
        <DataTable :columns="summaryColumns" :data="summaryRows" row-key="taskId" :loading="summaryLoading">
          <template #cell-taskId="{ row }">
            <span class="cell-title">{{ taskTitleOf(row.taskId) }}</span>
          </template>
          <template #cell-totalMinutes="{ row }"><span class="tnum">{{ row.totalMinutes }}</span></template>
          <template #cell-totalHours="{ row }">
            <span class="tnum soft">{{ (Number(row.totalMinutes) / 60).toFixed(2) }}</span>
          </template>
          <template #cell-logCount="{ row }"><span class="tnum soft">{{ row.logCount }}</span></template>
        </DataTable>
      </Panel>
    </template>

    <template #search>
      <Field label="关联任务" wide>
        <Select v-model="list.query.taskId" :options="taskOptions" placeholder="全部任务" filterable clearable />
      </Field>
      <Field label="开始日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.fromDate" placeholder="如：2026-10-01" clearable @enter="list.search()" />
      </Field>
      <Field label="结束日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.toDate" placeholder="如：2026-10-31" clearable @enter="list.search()" />
      </Field>
    </template>

    <template #cell-workDate="{ row }"><span class="tnum">{{ row.workDate }}</span></template>
    <template #cell-taskId="{ row }">
      <span class="cell-title">{{ taskTitleOf(row.taskId) }}</span>
    </template>
    <template #cell-durationMinutes="{ row }"><span class="tnum">{{ row.durationMinutes }} 分钟</span></template>
    <template #cell-description="{ row }">
      <span class="muted">{{ row.description || '—' }}</span>
    </template>
    <template #cell-createTime="{ row }"><span class="tnum soft">{{ row.createTime || '—' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="personal:time:edit" icon-only icon="edit" label="编辑工时" @click="openEdit(row)" />
        <PermissionButton perm="personal:time:edit" icon-only icon="trash" label="删除工时" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑工时' : '填报工时'"
      sub="工时必须关联任务；时长为实际投入分钟数。"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="关联任务" required :error="errors.taskId" hint="工时必须关联任务；选项来自你名下的任务，没有可选项时请先让任务指派给你">
        <Select v-model="form.taskId" :options="taskOptions" placeholder="请选择任务" filterable :invalid="!!errors.taskId" />
      </Field>
      <Field label="工作日期" required :error="errors.workDate" hint="格式 yyyy-MM-dd">
        <TextInput v-model="form.workDate" placeholder="如：2026-10-26" :invalid="!!errors.workDate" />
      </Field>
      <Field label="工时（分钟）" required :error="errors.durationMinutes">
        <TextInput v-model="form.durationMinutes" type="number" placeholder="如：90" :invalid="!!errors.durationMinutes" />
      </Field>
      <Field label="开始时间" hint="可选，格式 yyyy-MM-dd HH:mm">
        <TextInput v-model="form.startTime" placeholder="如：2026-10-26 09:30" />
      </Field>
      <Field label="结束时间" hint="可选，需晚于开始时间">
        <TextInput v-model="form.endTime" placeholder="如：2026-10-26 11:00" />
      </Field>
      <Field label="工作内容" span>
        <TextInput v-model="form.description" multiline :rows="2" placeholder="简述这段时间完成的工作" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
