<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { DataTable, Drawer, Field, Metric, SectionLabel, Segmented, Select, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  okrApi,
  type OkrKeyResultRow,
  type OkrObjectiveRow,
  type OkrPeriodRow,
  type OkrProgressLogRow,
} from '@/api/okr'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  DIRECTION_TEXT,
  KR_TYPE_TEXT,
  PROGRESS_SOURCE_HINT,
  PROGRESS_SOURCE_TEXT,
  autoProgressOf,
  calibrationRangeOf,
  dateTimeText,
  numberText,
  percentText,
} from './support'

/* ============================================================
   关键结果（KR）：目标的衡量口径。
   进度只有三种来源，页面必须说清来源，不允许「凭空填一个数」：
   · 公式计算（量化型，由当前值与目标值算出）
   · 任务回写（任务驱动型，由关联任务的完成情况自动回写）
   · 人工校准（在自动值 ±20 个百分点内，必须写原因，逐次留痕）
   ============================================================ */
const route = useRoute()

const periods = ref<OkrPeriodRow[]>([])
const objectives = ref<OkrObjectiveRow[]>([])
const filterPeriodId = ref<number | null>(null)

const initialObjectiveId = Number(route.query.objectiveId)

const list = useListPage<OkrKeyResultRow, { objectiveId: number | null }>({
  query: { objectiveId: Number.isFinite(initialObjectiveId) && initialObjectiveId > 0 ? initialObjectiveId : null },
  toParams: query => ({ objectiveId: query.objectiveId ?? undefined }),
  fetch: async (params) => {
    const objectiveId = params.objectiveId ? Number(params.objectiveId) : null
    if (!objectiveId) return { pageNum: 1, pageSize: 10, total: 0, rows: [] }
    const rows = await okrApi.krs.list(objectiveId)
    return { pageNum: 1, pageSize: Math.max(rows.length, 10), total: rows.length, rows }
  },
})

const columns: Column[] = [
  { key: 'krTitle', title: '关键结果', minWidth: 240 },
  { key: 'krType', title: '类型', width: 110 },
  { key: 'metric', title: '衡量口径', minWidth: 200 },
  { key: 'progress', title: '进度', width: 90, align: 'right' },
  { key: 'progressSource', title: '进度来源', width: 110 },
  { key: 'ownerName', title: '负责人', width: 100 },
  { key: 'actions', title: '操作', width: 190, className: 'col-actions' },
]

const periodOptions = computed(() => periods.value.map(p => ({ label: p.periodName, value: p.id })))
const objectiveOptions = computed(() => objectives.value.map(o => ({ label: `${o.objectiveTitle}（${o.periodName || '—'}）`, value: o.id })))
const currentObjective = computed(() => objectives.value.find(o => o.id === list.query.objectiveId) || null)

onMounted(async () => {
  try {
    const page = await okrApi.periods.list({ pageNum: 1, pageSize: 200 })
    periods.value = page.rows
  } catch (error) {
    toastError(error, '目标周期加载失败')
  }
  await loadObjectives()
})

async function loadObjectives() {
  try {
    const page = await okrApi.objectives.list({ pageNum: 1, pageSize: 200, periodId: filterPeriodId.value ?? undefined })
    objectives.value = page.rows
    if (list.query.objectiveId && !objectives.value.some(o => o.id === list.query.objectiveId)) {
      list.query.objectiveId = null
    }
    if (!list.query.objectiveId && objectives.value.length) {
      list.query.objectiveId = objectives.value[0].id
    }
    await list.search()
  } catch (error) {
    toastError(error, '目标列表加载失败')
  }
}

function pickPeriod(value: string | number | null | undefined) {
  filterPeriodId.value = value === null || value === undefined || value === '' ? null : Number(value)
  loadObjectives()
}

function pickObjective(value: string | number | null | undefined) {
  list.query.objectiveId = value === null || value === undefined || value === '' ? null : Number(value)
  list.search()
}

/* ── 展示文案 ── */
function metricText(row: OkrKeyResultRow): string {
  if (row.krType === 'task_driven') {
    return `关联任务：已完成 ${row.doneTaskCount ?? 0} / 共 ${row.taskCount ?? 0} 个`
  }
  const unit = row.metricUnit || ''
  return `${numberText(row.currentValue)}${unit} / ${numberText(row.targetValue)}${unit}（${DIRECTION_TEXT[row.direction] || row.direction}）`
}

/* ── 新增 / 编辑 KR ── */
interface KrForm {
  id?: number
  krTitle: string
  krType: string
  metricUnit: string
  targetValue: number | null
  currentValue: number | null
  direction: string
  ownerId: number | null
  sort: number | null
  remark: string
}
const emptyKr = (): KrForm => ({ krTitle: '', krType: 'quantitative', metricUnit: '', targetValue: null, currentValue: 0, direction: 'positive', ownerId: null, sort: 10, remark: '' })

const dialog = ref(false)
const saving = ref(false)
const form = reactive<KrForm>(emptyKr())
const errors = ref<Partial<Record<keyof KrForm, string>>>({})

function pickKrType(value: string | number | null | undefined) {
  form.krType = String(value ?? 'quantitative')
  if (form.krType === 'task_driven') {
    form.targetValue = null
    form.metricUnit = ''
  }
}
function pickDirection(value: string | number | null | undefined) {
  form.direction = String(value ?? 'positive')
}
function pickKrOwner(value: string | number | null) {
  form.ownerId = value === null || value === '' ? null : Number(value)
}

function openCreate() {
  if (!list.query.objectiveId) {
    pushToast('请先选择目标', 'danger')
    return
  }
  Object.assign(form, emptyKr(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: OkrKeyResultRow) {
  Object.assign(form, {
    id: row.id,
    krTitle: row.krTitle,
    krType: row.krType || 'quantitative',
    metricUnit: row.metricUnit ?? '',
    targetValue: row.targetValue ?? null,
    currentValue: row.currentValue ?? 0,
    direction: row.direction || 'positive',
    ownerId: row.ownerId ?? null,
    sort: row.sort ?? 0,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  const objectiveId = list.query.objectiveId
  if (!objectiveId) return
  errors.value = validate(form, {
    krTitle: [required('请输入关键结果标题')],
    targetValue: [required('量化型必须填写目标值')],
  })
  if (form.krType === 'task_driven') delete errors.value.targetValue
  if (form.krType === 'quantitative' && Number(form.targetValue ?? 0) <= 0) {
    errors.value.targetValue = '目标值必须大于 0'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    krTitle: form.krTitle.trim(),
    krType: form.krType,
    metricUnit: form.krType === 'quantitative' ? form.metricUnit : null,
    targetValue: form.krType === 'quantitative' ? form.targetValue : null,
    currentValue: form.krType === 'quantitative' ? form.currentValue : null,
    direction: form.direction,
    ownerId: form.ownerId,
    sort: form.sort,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await okrApi.krs.update(objectiveId, form.id, payload)
      pushToast('关键结果已更新')
    } else {
      await okrApi.krs.create(objectiveId, payload)
      pushToast('关键结果已新增')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '关键结果保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: OkrKeyResultRow) {
  const objectiveId = list.query.objectiveId
  if (!objectiveId) return
  const ok = await confirm({
    title: '删除关键结果',
    message: `确认删除「${row.krTitle}」吗？删除后进度归零并留下记录。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await okrApi.krs.remove(objectiveId, row.id)
    pushToast('关键结果已删除')
    await list.load()
  } catch (error) {
    toastError(error, '关键结果删除失败')
  }
}

/* ── 进度更新（三种来源必须可解释） ── */
const progressOpen = ref(false)
const progressTarget = ref<OkrKeyResultRow | null>(null)
const progressMode = ref<'value' | 'manual'>('value')
const progressSaving = ref(false)
const progressForm = reactive<{ currentValue: number | null; progress: number | null; reason: string }>({ currentValue: null, progress: null, reason: '' })

const targetAutoProgress = computed(() => progressTarget.value ? autoProgressOf(progressTarget.value) : null)
const targetCalibrationRange = computed(() => calibrationRangeOf(targetAutoProgress.value))

function openProgress(row: OkrKeyResultRow) {
  progressTarget.value = row
  progressMode.value = 'value'
  progressForm.currentValue = row.currentValue ?? 0
  progressForm.progress = row.progress ?? 0
  progressForm.reason = ''
  progressOpen.value = true
}

async function submitProgress() {
  const target = progressTarget.value
  const objectiveId = list.query.objectiveId
  if (!target || !objectiveId) return
  if (target.krType === 'task_driven') {
    progressOpen.value = false
    return
  }
  progressSaving.value = true
  try {
    if (progressMode.value === 'value') {
      if (progressForm.currentValue === null) {
        pushToast('请填写当前值', 'danger')
        return
      }
      await okrApi.krs.updateProgress(objectiveId, target.id, {
        currentValue: progressForm.currentValue,
        reason: progressForm.reason || undefined,
      })
      pushToast('已按公式重算进度')
    } else {
      if (progressForm.progress === null) {
        pushToast('请填写校准后的进度', 'danger')
        return
      }
      if (!progressForm.reason.trim()) {
        pushToast('人工校准必须填写原因', 'danger')
        return
      }
      await okrApi.krs.updateProgress(objectiveId, target.id, {
        progress: progressForm.progress,
        reason: progressForm.reason.trim(),
      })
      pushToast('人工校准已保存并留痕')
    }
    progressOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '进度更新失败')
  } finally {
    progressSaving.value = false
  }
}

/* ── 进度留痕 ── */
const logOpen = ref(false)
const logTarget = ref<OkrKeyResultRow | null>(null)
const logs = ref<OkrProgressLogRow[]>([])
const logLoading = ref(false)

async function openLogs(row: OkrKeyResultRow) {
  logTarget.value = row
  logOpen.value = true
  logLoading.value = true
  try {
    const objectiveId = list.query.objectiveId
    logs.value = objectiveId ? await okrApi.krs.progressLogs(objectiveId, row.id) : []
  } catch (error) {
    toastError(error, '进度留痕加载失败')
    logs.value = []
  } finally {
    logLoading.value = false
  }
}

watch(() => list.query.objectiveId, () => {
  // 切换目标后关闭可能残留的详情弹层，避免看到上一个目标的数据
  progressOpen.value = false
  logOpen.value = false
})
</script>

<template>
  <ListPage
    title="关键结果"
    desc="关键结果衡量目标是否达成：量化型按公式算进度，任务驱动型由关联任务的完成情况自动回写；任何人工调整都会留痕。"
    table-title="关键结果列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="okr:kr:add" variant="primary" icon="plus" @click="openCreate">新增关键结果</PermissionButton>
    </template>

    <template #search>
      <Field label="所属周期">
        <Select
          :model-value="filterPeriodId"
          :options="[{ label: '全部周期', value: '' }, ...periodOptions]"
          filterable
          @update:model-value="pickPeriod"
        />
      </Field>
      <Field label="目标" wide>
        <Select
          :model-value="list.query.objectiveId"
          :options="objectiveOptions"
          placeholder="选择目标后查看其关键结果"
          filterable
          @update:model-value="pickObjective"
        />
      </Field>
    </template>

    <template #cell-krTitle="{ row }">
      <span class="cell-title">{{ row.krTitle }}</span>
      <span v-if="row.remark" class="cell-sub">{{ row.remark }}</span>
    </template>
    <template #cell-krType="{ row }">
      <Tag :tone="row.krType === 'task_driven' ? 'info' : 'neutral'">{{ KR_TYPE_TEXT[row.krType] || row.krType }}</Tag>
    </template>
    <template #cell-metric="{ row }">
      <span class="soft">{{ metricText(row) }}</span>
    </template>
    <template #cell-progress="{ row }">
      <span class="tnum">{{ percentText(row.progress) }}</span>
    </template>
    <template #cell-progressSource="{ row }">
      <Tag :tone="row.progressSource === 'manual' ? 'danger' : row.progressSource === 'task_writeback' ? 'info' : 'ok'">
        {{ PROGRESS_SOURCE_TEXT[row.progressSource || ''] || row.progressSource || '—' }}
      </Tag>
    </template>
    <template #cell-ownerName="{ row }">
      <span>{{ row.ownerName || '—' }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="okr:kr:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
        <PermissionButton
          :perm="row.krType === 'task_driven' ? 'okr:kr:list' : 'okr:kr:edit'"
          icon-only
          icon="activity"
          label="进度与来源"
          @click="openProgress(row)"
        />
        <PermissionButton perm="okr:kr:list" icon-only icon="clock" label="进度留痕" @click="openLogs(row)" />
        <PermissionButton perm="okr:kr:remove" icon-only icon="trash" label="删除" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑关键结果' : '新增关键结果'"
      :sub="currentObjective ? `所属目标：${currentObjective.objectiveTitle}` : ''"
      :width="700"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="关键结果" required :error="errors.krTitle" span>
        <TextInput v-model="form.krTitle" placeholder="如：核心接口 P95 降到 200ms 以内" :invalid="!!errors.krTitle" :maxlength="120" />
      </Field>
      <Field label="衡量类型" hint="任务驱动型由关联任务完成度决定进度">
        <Select
          :model-value="form.krType"
          :options="[{ label: '量化型（按数值公式）', value: 'quantitative' }, { label: '任务驱动型（按任务完成度）', value: 'task_driven' }]"
          @update:model-value="pickKrType"
        />
      </Field>
      <Field label="指标方向">
        <Select
          :model-value="form.direction"
          :options="[{ label: '越大越好', value: 'positive' }, { label: '越小越好（成本、耗时类）', value: 'negative' }]"
          @update:model-value="pickDirection"
        />
      </Field>
      <template v-if="form.krType === 'quantitative'">
        <Field label="目标值" required :error="errors.targetValue">
          <TextInput v-model="form.targetValue" type="number" placeholder="如：200" :invalid="!!errors.targetValue" />
        </Field>
        <Field label="当前值">
          <TextInput v-model="form.currentValue" type="number" placeholder="如：180" />
        </Field>
        <Field label="单位">
          <TextInput v-model="form.metricUnit" placeholder="如：ms、个、%" :maxlength="16" />
        </Field>
      </template>
      <Field label="负责人" hint="可留空，留空时不计入个人目标承接">
        <UserSelect :model-value="form.ownerId" placeholder="选择负责人" @update:model-value="pickKrOwner" />
      </Field>
      <Field label="排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="口径说明（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="progressOpen"
      title="进度与来源"
      :sub="progressTarget ? progressTarget.krTitle : ''"
      :width="720"
      :saving="progressSaving"
      :submit-text="progressTarget?.krType === 'task_driven' ? '关闭' : '保存进度'"
      @close="progressOpen = false"
      @submit="submitProgress"
    >
      <template v-if="progressTarget">
        <div class="span-2" style="width: 100%">
          <div class="band" style="margin: 0 0 14px">
            <div class="band-head" style="margin-bottom: 10px">
              <SectionLabel title="当前状态" />
              <Tag :tone="progressTarget.progressSource === 'manual' ? 'danger' : progressTarget.progressSource === 'task_writeback' ? 'info' : 'ok'">
                {{ PROGRESS_SOURCE_TEXT[progressTarget.progressSource || ''] || progressTarget.progressSource || '—' }}
              </Tag>
            </div>
            <div class="readout-strip">
              <Metric label="当前进度" :value="progressTarget.progress ?? 0" :decimals="1" unit="%" />
              <Metric v-if="progressTarget.krType === 'quantitative'" label="公式自动值" :value="targetAutoProgress ?? 0" :decimals="1" unit="%" />
              <Metric v-if="progressTarget.krType === 'task_driven'" label="已完成顶层任务" :value="progressTarget.doneTaskCount ?? 0" unit="个" />
              <Metric v-if="progressTarget.krType === 'task_driven'" label="关联顶层任务" :value="progressTarget.taskCount ?? 0" unit="个" />
            </div>
            <p class="faint" style="margin-top: 10px; font-size: 12px; line-height: 1.7">
              {{ PROGRESS_SOURCE_HINT[progressTarget.progressSource || ''] || '进度来源由系统记录。' }}
            </p>
          </div>
        </div>

        <template v-if="progressTarget.krType === 'task_driven'">
          <p class="span-2" style="width: 100%; font-size: 13px; line-height: 1.8; color: var(--ink-muted)">
            任务驱动型的进度 = 已完成顶层任务数 / 关联顶层任务总数，由任务状态变更自动回写，<b style="color: var(--ink)">不支持人工填写</b>。
            如需调整进度，请到「任务列表」处理关联任务（任务创建或编辑时可关联本关键结果）。
          </p>
          <p v-if="!progressTarget.taskCount" class="span-2 faint" style="width: 100%; font-size: 13px">
            当前还没有任务关联到本关键结果，进度保持 0，状态标记为「待关联」。
          </p>
        </template>

        <template v-else>
          <div class="span-2" style="width: 100%">
            <Segmented
              v-model="progressMode"
              label="更新方式"
              :options="[{ label: '更新当前值（按公式重算）', value: 'value' }, { label: '人工校准', value: 'manual' }]"
            />
          </div>
          <template v-if="progressMode === 'value'">
            <Field label="当前值" required hint="按公式重算进度，进度来源记为「公式计算」">
              <TextInput v-model="progressForm.currentValue" type="number" />
            </Field>
            <Field label="说明">
              <TextInput v-model="progressForm.reason" placeholder="如：本期完成两次压测优化（可选）" :maxlength="200" />
            </Field>
          </template>
          <template v-else>
            <Field
              label="校准后进度（%）"
              required
              :hint="targetCalibrationRange ? `允许区间 ${targetCalibrationRange.min}% ~ ${targetCalibrationRange.max}%（自动值 ±20 个百分点，超限需走绩效申诉复核）` : '自动值不可计算时无法校准'"
            >
              <TextInput v-model="progressForm.progress" type="number" />
            </Field>
            <Field label="校准原因" required hint="人工校准必须写明原因，会进入进度留痕">
              <TextInput v-model="progressForm.reason" placeholder="如：客户验收延迟，按实际情况下调" :maxlength="200" />
            </Field>
          </template>
        </template>
      </template>
    </FormDialog>

    <Drawer :open="logOpen" title="进度留痕" :sub="logTarget ? logTarget.krTitle : ''" :width="760" @close="logOpen = false">
      <DataTable
        :columns="[
          { key: 'createTime', title: '时间', width: 140 },
          { key: 'fromProgress', title: '调整前', width: 90, align: 'right' },
          { key: 'toProgress', title: '调整后', width: 90, align: 'right' },
          { key: 'progressSource', title: '来源', width: 100 },
          { key: 'reason', title: '原因', minWidth: 200 },
          { key: 'operatorName', title: '操作人', width: 100 }
        ]"
        :data="logs"
        :loading="logLoading"
        empty-text="还没有进度变更记录"
      >
        <template #cell-createTime="{ row }">
          <span class="tnum soft">{{ dateTimeText(row.createTime) }}</span>
        </template>
        <template #cell-fromProgress="{ row }">
          <span class="tnum">{{ percentText(row.fromProgress) }}</span>
        </template>
        <template #cell-toProgress="{ row }">
          <span class="tnum">{{ percentText(row.toProgress) }}</span>
        </template>
        <template #cell-progressSource="{ row }">
          <Tag :tone="row.progressSource === 'manual' ? 'danger' : row.progressSource === 'task_writeback' ? 'info' : 'ok'">
            {{ PROGRESS_SOURCE_TEXT[row.progressSource] || row.progressSource }}
          </Tag>
        </template>
        <template #cell-reason="{ row }">
          <span class="muted">{{ row.reason || '—' }}</span>
        </template>
        <template #cell-operatorName="{ row }">
          <span>{{ row.operatorName || '—' }}</span>
        </template>
      </DataTable>
    </Drawer>
  </ListPage>
</template>
