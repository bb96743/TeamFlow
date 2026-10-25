<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  DataTable,
  Drawer,
  Field,
  Metric,
  Panel,
  SectionLabel,
  Segmented,
  Select,
  Switch,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import PerfDrilldown from './components/PerfDrilldown.vue'
import { cycleStatusLabel, cycleStatusTone, scoreText } from './labels'
import {
  perfApi,
  type PerfCollectEmployee,
  type PerfCollectItem,
  type PerfCollectPreview,
  type PerfCycle,
  type PerfScoreRow,
  type PerfScoreWrite,
  type PerfTemplateItem,
} from '@/api/perf'
import { useListPage } from '@/composables/list'
import { useViewMode } from '@/composables/view'
import { currentUser } from '@/composables/auth'
import { pushToast, toastError } from '@/composables/toast'

/* 绩效评分：归集预览（含下钻与「不适用」标记）、本人自评、上级评分。
   可归集指标由系统计算、前端只读；主观指标可录入且评语必填（R1）。
   缺失值默认 0，只有显式标记「不适用」才按剩余权重归一化（D-06.5）。 */

type ViewMode = 'preview' | 'self' | 'manager'
const view = useViewMode<ViewMode>('perf.score', 'preview', ['preview', 'self', 'manager'])
const VIEWS = [
  { label: '归集预览', value: 'preview' as ViewMode },
  { label: '自评（本人）', value: 'self' as ViewMode },
  { label: '上级评分', value: 'manager' as ViewMode },
]

/* ── 周期选择 ── */
const cycles = ref<PerfCycle[]>([])
const cycleId = ref<number | null>(null)
const cycle = computed(() => cycles.value.find(item => item.id === cycleId.value) ?? null)
const cycleStatus = computed(() => cycle.value?.status ?? null)
const cycleLocked = computed(() => cycleStatus.value === 'archived' || cycleStatus.value === 'locked')
const scoringAllowed = computed(() => cycleStatus.value === 'active' || cycleStatus.value === 'scoring')
const cycleOptions = computed(() => cycles.value.map(item => ({
  label: item.cycleName,
  value: item.id,
  hint: cycleStatusLabel(item.status),
})))
const selfEmployeeId = computed(() => currentUser.value?.id ?? null)

async function loadCycles() {
  try {
    const page = await perfApi.cycles.list({ pageNum: 1, pageSize: 100 })
    const rows = page.rows
    // 优先打开处于评分中/进行中的周期，避免默认落到草稿或已锁定周期
    cycles.value = [...rows].sort((a, b) => rank(a.status) - rank(b.status))
    if (!cycleId.value && rows.length) {
      const preferred = rows.find(item => item.status === 'scoring') ?? rows.find(item => item.status === 'active') ?? rows[0]
      cycleId.value = preferred.id
    }
  } catch (error) {
    toastError(error, '绩效周期加载失败')
  }
}

function rank(status: string) {
  return ['scoring', 'active', 'draft', 'archived', 'locked'].indexOf(status)
}

/* ── 归集预览 ── */
const previewData = ref<PerfCollectPreview | null>(null)
const previewList = useListPage<PerfCollectEmployee, { cycleId: number }>({
  query: { cycleId: 0 },
  immediate: false,
  fetch: async (params) => {
    const id = Number(params.cycleId ?? 0)
    if (!id) return { rows: [], total: 0, pageNum: 1, pageSize: 10 }
    const data = await perfApi.collect.preview(id)
    previewData.value = data
    return { rows: data.employees, total: data.employees.length, pageNum: 1, pageSize: Math.max(1, data.employees.length) }
  },
})
const previewRows = computed(() => previewList.rows.value)
const previewLoading = previewList.loading
const previewMeta = computed(() => previewData.value
  ? `${previewData.value.cycleName} · ${previewData.value.startDate} → ${previewData.value.endDate} · 模板 ${previewData.value.templateName ?? '—'}`
  : '选择周期后加载归集预览')

const previewColumns: Column[] = [
  { key: 'employeeName', title: '员工', minWidth: 190 },
  { key: 'krCount', title: '关联 KR', width: 96 },
  { key: 'taskCount', title: '顶层任务', width: 100 },
  { key: 'scoredWeight', title: '计入权重', width: 108 },
  { key: 'naWeight', title: '不适用权重', width: 116 },
  { key: 'previewTotal', title: '归集预览分', width: 126 },
  { key: 'warnings', title: '提示', minWidth: 220 },
  { key: 'actions', title: '操作', width: 160, className: 'col-actions' },
]

const previewSummary = computed(() => {
  const employees = previewData.value?.employees ?? []
  const totals = employees.map(item => item.previewTotal).filter((value): value is number => value !== null && value !== undefined)
  const average = totals.length ? totals.reduce((sum, value) => sum + value, 0) / totals.length : null
  const naCount = employees.reduce((count, employee) => count + employee.items.filter(item => item.notApplicable).length, 0)
  return {
    employees: employees.length,
    average,
    naCount,
    warnings: previewData.value?.warnings.length ?? 0,
  }
})

async function loadPreview() {
  if (!cycleId.value) return
  previewList.query.cycleId = cycleId.value
  await previewList.load()
}

const recalculating = ref(false)
async function recalculate() {
  if (!cycleId.value) return
  recalculating.value = true
  try {
    await perfApi.collect.recalculate(cycleId.value)
    pushToast('归集已重算：覆盖写入当前指标值（同一周期多次重算结果一致）')
    await loadPreview()
  } catch (error) {
    toastError(error, '归集重算失败')
  } finally {
    recalculating.value = false
  }
}

/* ── 下钻 ── */
const drillOpen = ref(false)
const drillEmployee = ref<PerfCollectEmployee | null>(null)

function openDrill(row: PerfCollectEmployee) {
  drillEmployee.value = row
  drillOpen.value = true
}

/* ── 不适用标记（D-06.5） ── */
const naOpen = ref(false)
const naEmployeeId = ref<number | null>(null)
const naSaving = ref(false)
const naForm = reactive<{ itemId: number | null; notApplicable: boolean; reason: string }>({
  itemId: null, notApplicable: true, reason: '',
})
const naError = ref('')

function naEmployee() {
  return previewData.value?.employees.find(item => item.employeeId === naEmployeeId.value) ?? null
}

function naItemOptions() {
  const employee = naEmployee()
  if (!employee) return []
  return employee.items
    .filter(item => item.itemCategory === 'collectable')
    .map(item => ({ label: `${item.itemName}（权重 ${item.weight}%）`, value: item.itemId }))
}

function syncNaFromItem() {
  const item = naEmployee()?.items.find(entry => entry.itemId === naForm.itemId)
  naForm.notApplicable = !item?.notApplicable
  naForm.reason = item?.naReason ?? ''
  naError.value = ''
}

function openNa(employee: PerfCollectEmployee, item?: PerfCollectItem) {
  naEmployeeId.value = employee.employeeId
  naForm.itemId = item?.itemId ?? employee.items.find(entry => entry.itemCategory === 'collectable')?.itemId ?? null
  naOpen.value = true
  syncNaFromItem()
}

async function submitNa() {
  if (!cycleId.value || !naForm.itemId || !naEmployeeId.value) return
  if (naForm.notApplicable && !naForm.reason.trim()) {
    naError.value = '标记不适用必须填写原因（新入职当年 / 长假 / 借调 / 试用期未定目标等）'
    return
  }
  naError.value = ''
  naSaving.value = true
  try {
    await perfApi.scores.markNotApplicable({
      cycleId: cycleId.value,
      employeeId: naEmployeeId.value,
      itemId: naForm.itemId,
      notApplicable: naForm.notApplicable,
      reason: naForm.notApplicable ? naForm.reason.trim() : undefined,
    })
    pushToast(naForm.notApplicable ? '已标记不适用，该指标将按剩余权重归一化' : '已取消不适用标记')
    naOpen.value = false
    const employeeId = naEmployeeId.value
    await loadPreview()
    const updated = previewData.value?.employees.find(entry => entry.employeeId === employeeId)
    if (updated) drillEmployee.value = updated
  } catch (error) {
    toastError(error, '不适用标记失败')
  } finally {
    naSaving.value = false
  }
}

/* ── 评分（自评 / 上级评） ── */
const scoreRows = ref<PerfScoreRow[]>([])
const templateItems = ref<PerfTemplateItem[]>([])
const contextCycleId = ref<number | null>(null)
const scoreLoading = ref(false)
const managerEmployeeId = ref<number | null>(null)
const draft = reactive<Record<number, { score: number | null; comment: string }>>({})
const fieldErrors = ref<Record<number, { score?: string; comment?: string }>>({})

const collectableItems = computed(() => templateItems.value.filter(item => item.itemCategory === 'collectable'))
const subjectiveItems = computed(() => templateItems.value.filter(item => item.itemCategory === 'subjective'))

const collectableRows = computed(() => collectableItems.value.map(item => {
  const systemRow = scoreRows.value.find(row => row.templateItemId === item.id && row.raterType === 'system')
  return {
    itemId: item.id,
    itemName: item.itemName,
    itemCode: item.itemCode,
    weight: item.weight,
    autoScore: systemRow?.autoScore ?? null,
    notApplicable: Boolean(systemRow?.notApplicable),
    naReason: systemRow?.naReason ?? null,
    missing: systemRow ? systemRow.autoScore === null : true,
  }
}))

const collectableColumns: Column[] = [
  { key: 'itemName', title: '可归集指标', minWidth: 200 },
  { key: 'weight', title: '权重', width: 90 },
  { key: 'autoScore', title: '系统归集值', width: 130 },
  { key: 'state', title: '状态', minWidth: 200 },
]

function selfRowOf(itemId: number) {
  return scoreRows.value.find(row => row.templateItemId === itemId && row.raterType === 'self')
}

function managerRowOf(itemId: number) {
  return scoreRows.value.find(row => row.templateItemId === itemId && row.raterType === 'manager')
}

function resetDraft() {
  Object.keys(draft).forEach(key => { delete draft[Number(key)] })
  fieldErrors.value = {}
  subjectiveItems.value.forEach(item => {
    const source = view.value === 'manager' ? managerRowOf(item.id) : selfRowOf(item.id)
    draft[item.id] = {
      score: source?.score ?? null,
      comment: source?.comment ?? '',
    }
  })
}

/** 模板指标项变化后补齐评分草稿，保证模板输入框始终有绑定对象 */
function ensureDraftItems() {
  fieldErrors.value = {}
  subjectiveItems.value.forEach(item => {
    if (!draft[item.id]) draft[item.id] = { score: null, comment: '' }
  })
}

async function ensureCycleContext() {
  if (!cycleId.value) return false
  if (contextCycleId.value === cycleId.value && templateItems.value.length) return true
  const detail = await perfApi.cycles.detail(cycleId.value)
  if (!detail.templateId) {
    pushToast('该周期尚未绑定指标模板，无法评分', 'danger')
    templateItems.value = []
    return false
  }
  const template = await perfApi.templates.detail(detail.templateId)
  templateItems.value = template.items ?? []
  contextCycleId.value = cycleId.value
  ensureDraftItems()
  return true
}

async function loadScoresFor(employeeId: number | null) {
  if (!cycleId.value || !employeeId) {
    scoreRows.value = []
    resetDraft()
    return
  }
  scoreLoading.value = true
  try {
    const page = await perfApi.scores.list({ cycleId: cycleId.value, employeeId, pageNum: 1, pageSize: 200 })
    scoreRows.value = page.rows
    resetDraft()
  } catch (error) {
    toastError(error, '评分明细加载失败')
  } finally {
    scoreLoading.value = false
  }
}

async function loadScoring() {
  if (!cycleId.value) return
  try {
    const ready = await ensureCycleContext()
    if (!ready) return
    const employeeId = view.value === 'self' ? selfEmployeeId.value : managerEmployeeId.value
    await loadScoresFor(employeeId)
    ensureDraftItems()
  } catch (error) {
    toastError(error, '评分数据加载失败')
  }
}

function collectWrites(): PerfScoreWrite[] {
  const writes: PerfScoreWrite[] = []
  const errors: Record<number, { score?: string; comment?: string }> = {}
  for (const item of subjectiveItems.value) {
    const entry = draft[item.id] ?? { score: null, comment: '' }
    const maxScore = Number(item.maxScore ?? 100)
    const score = entry.score === null || entry.score === undefined || Number.isNaN(Number(entry.score)) ? null : Number(entry.score)
    if (score === null) {
      errors[item.id] = { ...errors[item.id], score: '请填写分值' }
      continue
    }
    if (score < 0 || score > maxScore) {
      errors[item.id] = { ...errors[item.id], score: `分值需在 0 - ${maxScore} 之间` }
      continue
    }
    const comment = entry.comment.trim()
    if (!comment) {
      errors[item.id] = { ...errors[item.id], comment: '主观指标必须填写评语' }
      continue
    }
    writes.push({ itemId: item.id, score, comment })
  }
  fieldErrors.value = errors
  return writes
}

const submitting = ref(false)

async function submitSelf() {
  if (!cycleId.value) return
  if (!scoringAllowed.value) {
    pushToast(`当前周期状态为「${cycleStatusLabel(cycleStatus.value)}」，只有进行中/评分中可评分`, 'danger')
    return
  }
  if (!subjectiveItems.value.length) {
    pushToast('当前模板没有主观指标项，无需自评', 'info')
    return
  }
  const writes = collectWrites()
  if (Object.keys(fieldErrors.value).length) return
  submitting.value = true
  try {
    const result = await perfApi.scores.saveSelf(cycleId.value, writes)
    pushToast(`自评已提交：${result.savedCount} 项主观指标（评语与前后值均已留痕）`)
    await loadScoresFor(selfEmployeeId.value)
  } catch (error) {
    toastError(error, '自评提交失败')
  } finally {
    submitting.value = false
  }
}

async function submitManager() {
  if (!cycleId.value || !managerEmployeeId.value) {
    pushToast('请先选择被评人', 'danger')
    return
  }
  if (!scoringAllowed.value) {
    pushToast(`当前周期状态为「${cycleStatusLabel(cycleStatus.value)}」，只有进行中/评分中可评分`, 'danger')
    return
  }
  if (!subjectiveItems.value.length) {
    pushToast('当前模板没有主观指标项，无需上级评分', 'info')
    return
  }
  const writes = collectWrites()
  if (Object.keys(fieldErrors.value).length) return
  submitting.value = true
  try {
    const result = await perfApi.scores.saveManager(cycleId.value, managerEmployeeId.value, writes)
    pushToast(`上级评分已提交：${result.savedCount} 项主观指标（需本人已自评，后端已校验）`)
    await loadScoresFor(managerEmployeeId.value)
  } catch (error) {
    toastError(error, '上级评分提交失败')
  } finally {
    submitting.value = false
  }
}

/* ── 联动 ── */
function refreshCurrent() {
  if (!cycleId.value) return
  if (view.value === 'preview') loadPreview()
  else loadScoring()
}

watch(cycleId, () => {
  previewData.value = null
  drillEmployee.value = null
  contextCycleId.value = null
  templateItems.value = []
  scoreRows.value = []
  resetDraft()
  refreshCurrent()
})

watch(view, () => {
  refreshCurrent()
})

watch(managerEmployeeId, () => {
  if (view.value === 'manager') loadScoresFor(managerEmployeeId.value)
})

onMounted(loadCycles)
</script>

<template>
  <ListPage
    title="绩效评分"
    desc="归集预览可下钻到来源 KR / 任务并标记例外状态；可归集指标由系统计算且只读，只有主观指标可录入评分且必须填写评语。"
    table-title="归集预览"
    :list="previewList"
    :columns="previewColumns"
    alt-view
  >
    <template #actions>
      <Segmented v-model="view" label="评分视图" :options="VIEWS" />
    </template>

    <template #before>
      <div class="board-toolbar reveal">
        <Field label="绩效周期" style="width: 260px">
          <Select v-model="cycleId" :options="cycleOptions" placeholder="选择绩效周期" filterable />
        </Field>
        <Tag v-if="cycleStatus" :tone="cycleStatusTone(cycleStatus)" dot>{{ cycleStatusLabel(cycleStatus) }}</Tag>
        <Field v-if="view === 'manager'" label="被评人" style="width: 220px">
          <UserSelect v-model="managerEmployeeId" placeholder="选择被评人" />
        </Field>
        <span class="spacer" />
        <span v-if="cycleLocked" class="soft" style="font-size: 12.5px">
          周期已归档/锁定：评分为只读，锁定后修改只能通过申诉流程。
        </span>
        <span v-else-if="cycleStatus === 'draft'" class="soft" style="font-size: 12.5px">
          周期尚未开启：请先在「绩效周期」页开启后评分。
        </span>
      </div>
    </template>

    <template #alt>
      <!-- 归集预览 -->
      <div v-if="view === 'preview'">
        <section class="band reveal">
          <div class="band-head">
            <SectionLabel title="归集概览" />
            <span class="band-tools">
              <span class="bento-meta">{{ previewMeta }}</span>
              <PermissionButton perm="perf:cycle:collect" icon="refresh" size="sm" :loading="previewLoading" @click="loadPreview">
                归集预览
              </PermissionButton>
              <PermissionButton perm="perf:cycle:collect" variant="primary" icon="activity" size="sm" :loading="recalculating" @click="recalculate">
                重算归集
              </PermissionButton>
            </span>
          </div>
          <div class="readout-strip">
            <Metric label="员工数" :value="previewSummary.employees" unit="人" />
            <Metric label="平均归集分" :value="previewSummary.average" :decimals="2" unit="分" />
            <Metric label="不适用标记" :value="previewSummary.naCount" unit="项" />
            <Metric label="口径提示" :value="previewSummary.warnings" unit="条" :class="{ 'is-alert': previewSummary.warnings }" />
          </div>
        </section>

        <Panel title="员工归集明细" :meta="`共 ${previewRows.length} 人 · 点击「下钻」可追溯 KR / 任务来源`">
          <template #tools>
            <PermissionButton perm="perf:cycle:collect" icon="refresh" size="sm" :disabled="!cycleId" @click="loadPreview">刷新</PermissionButton>
          </template>
          <DataTable
            :columns="previewColumns"
            :data="previewRows"
            row-key="employeeId"
            :loading="previewLoading"
            :min-width="1100"
          >
            <template #cell-employeeName="{ row }">
              <div>
                <div class="cell-title">{{ row.employeeName }}</div>
                <div class="cell-sub">@{{ row.employeeUsername }}</div>
              </div>
            </template>
            <template #cell-krCount="{ row }"><span class="tnum">{{ row.krCount }}</span></template>
            <template #cell-taskCount="{ row }"><span class="tnum">{{ row.taskCount }}</span></template>
            <template #cell-scoredWeight="{ row }"><span class="tnum">{{ scoreText(row.scoredWeight, '%') }}</span></template>
            <template #cell-naWeight="{ row }">
              <Tag v-if="row.naWeight > 0" tone="danger" dot>{{ scoreText(row.naWeight, '%') }}</Tag>
              <span v-else class="tnum">{{ scoreText(row.naWeight, '%') }}</span>
            </template>
            <template #cell-previewTotal="{ row }"><span class="cell-title tnum">{{ scoreText(row.previewTotal) }}</span></template>
            <template #cell-warnings="{ row }">
              <Tag v-if="row.warnings.length" tone="danger" :title="row.warnings.join('；')">{{ row.warnings.length }} 条提示</Tag>
              <Tag v-else tone="ok">无异常</Tag>
            </template>
            <template #cell-actions="{ row }">
              <div class="row-actions">
                <PermissionButton perm="perf:score:list" icon-only icon="eye" label="下钻来源" @click="openDrill(row)" />
                <PermissionButton
                  perm="perf:score:submit"
                  icon-only
                  icon="ban"
                  label="标记不适用"
                  :disabled="cycleLocked"
                  @click="openNa(row)"
                />
              </div>
            </template>
          </DataTable>
        </Panel>
      </div>

      <!-- 自评 -->
      <div v-else-if="view === 'self'">
        <section class="band reveal">
          <div class="band-head">
            <SectionLabel title="本人自评" />
            <span class="band-tools">
              <span class="bento-meta">{{ cycle?.cycleName }} · 可归集指标由系统计算（只读），主观指标需填写评语</span>
            </span>
          </div>
          <p v-if="!selfEmployeeId" class="soft" style="font-size: 13px">当前登录账号缺少用户身份，无法自评。</p>
          <p v-else-if="!scoringAllowed" class="soft" style="font-size: 13px">
            当前周期状态为「{{ cycleStatusLabel(cycleStatus) }}」，只有进行中/评分中的周期可以自评。
          </p>
        </section>

        <Panel title="可归集指标（系统计算，只读）" meta="禁止手工覆盖；如数据缺失且属于例外状态，可标记不适用">
          <DataTable :columns="collectableColumns" :data="collectableRows" row-key="itemId" :loading="scoreLoading" :min-width="720">
            <template #cell-itemName="{ row }">
              <div>
                <div class="cell-title">{{ row.itemName }}</div>
                <div class="cell-sub tnum">{{ row.itemCode }}</div>
              </div>
            </template>
            <template #cell-weight="{ row }"><span class="tnum">{{ scoreText(row.weight, '%') }}</span></template>
            <template #cell-autoScore="{ row }"><span class="cell-title tnum">{{ scoreText(row.autoScore) }}</span></template>
            <template #cell-state="{ row }">
              <Tag v-if="row.notApplicable" tone="danger" dot>不适用（按剩余权重归一化）</Tag>
              <Tag v-else-if="row.missing" tone="neutral">尚未归集</Tag>
              <Tag v-else tone="ok">系统归集 · 只读</Tag>
            </template>
          </DataTable>
        </Panel>

        <Panel title="主观指标评分" meta="分值 0-100，评语必填；每次保存都会留痕（评分人/时间/前后值）">
          <template #tools>
            <PermissionButton
              perm="perf:score:self"
              variant="primary"
              icon="check"
              :loading="submitting"
              :disabled="!scoringAllowed || !selfEmployeeId"
              @click="submitSelf"
            >提交自评</PermissionButton>
          </template>
          <p v-if="!subjectiveItems.length" class="soft" style="font-size: 13px">当前模板没有主观指标项，无需自评。</p>
          <div v-for="item in subjectiveItems" :key="item.id" class="form-grid" style="padding: 14px 0; border-top: 1px solid var(--line)">
            <Field :label="`${item.itemName}（权重 ${scoreText(item.weight, '%')}）`" required :error="fieldErrors[item.id]?.score">
              <TextInput v-model="draft[item.id].score" type="number" placeholder="0 - 100" :invalid="!!fieldErrors[item.id]?.score" />
            </Field>
            <Field label="评语" required span :error="fieldErrors[item.id]?.comment" hint="说明评分依据，提交后留痕">
              <TextInput v-model="draft[item.id].comment" placeholder="如：跨团队协同主动，风险提前暴露" multiline :rows="2" :invalid="!!fieldErrors[item.id]?.comment" />
            </Field>
            <p v-if="selfRowOf(item.id)" class="faint" style="font-size: 11.5px; grid-column: 1 / -1; margin: 0">
              上次评分：{{ scoreText(selfRowOf(item.id)?.score) }} 分 · {{ selfRowOf(item.id)?.comment || '无评语' }}
            </p>
          </div>
        </Panel>
      </div>

      <!-- 上级评分 -->
      <div v-else>
        <section class="band reveal">
          <div class="band-head">
            <SectionLabel title="上级评分" />
            <span class="band-tools">
              <span class="bento-meta">需本人已完成自评（后端校验）；可归集指标只读，主观指标评分与评语会留痕</span>
            </span>
          </div>
          <p v-if="!managerEmployeeId" class="soft" style="font-size: 13px">请先在上方选择被评人。</p>
          <p v-else-if="!scoringAllowed" class="soft" style="font-size: 13px">
            当前周期状态为「{{ cycleStatusLabel(cycleStatus) }}」，只有进行中/评分中的周期可以评分。
          </p>
        </section>

        <Panel title="可归集指标（系统计算，只读）" meta="归集口径与来源见「归集预览」页的下钻">
          <DataTable :columns="collectableColumns" :data="collectableRows" row-key="itemId" :loading="scoreLoading" :min-width="720">
            <template #cell-itemName="{ row }">
              <div>
                <div class="cell-title">{{ row.itemName }}</div>
                <div class="cell-sub tnum">{{ row.itemCode }}</div>
              </div>
            </template>
            <template #cell-weight="{ row }"><span class="tnum">{{ scoreText(row.weight, '%') }}</span></template>
            <template #cell-autoScore="{ row }"><span class="cell-title tnum">{{ scoreText(row.autoScore) }}</span></template>
            <template #cell-state="{ row }">
              <Tag v-if="row.notApplicable" tone="danger" dot>不适用（按剩余权重归一化）</Tag>
              <Tag v-else-if="row.missing" tone="neutral">尚未归集</Tag>
              <Tag v-else tone="ok">系统归集 · 只读</Tag>
            </template>
          </DataTable>
        </Panel>

        <Panel title="主观指标评分" meta="自评分为参考；上级评分参与最终总分">
          <template #tools>
            <PermissionButton
              perm="perf:score:submit"
              variant="primary"
              icon="check"
              :loading="submitting"
              :disabled="!scoringAllowed || !managerEmployeeId"
              @click="submitManager"
            >提交上级评分</PermissionButton>
          </template>
          <p v-if="!subjectiveItems.length" class="soft" style="font-size: 13px">当前模板没有主观指标项，无需上级评分。</p>
          <div v-for="item in subjectiveItems" :key="item.id" class="form-grid" style="padding: 14px 0; border-top: 1px solid var(--line)">
            <Field :label="`${item.itemName}（权重 ${scoreText(item.weight, '%')}）`" required :error="fieldErrors[item.id]?.score">
              <TextInput v-model="draft[item.id].score" type="number" placeholder="0 - 100" :invalid="!!fieldErrors[item.id]?.score" />
            </Field>
            <Field label="评语" required span :error="fieldErrors[item.id]?.comment" hint="说明评分依据，提交后留痕">
              <TextInput v-model="draft[item.id].comment" placeholder="如：目标达成质量高，协作主动" multiline :rows="2" :invalid="!!fieldErrors[item.id]?.comment" />
            </Field>
            <p class="faint" style="font-size: 11.5px; grid-column: 1 / -1; margin: 0">
              本人自评：
              <template v-if="selfRowOf(item.id)">{{ scoreText(selfRowOf(item.id)?.score) }} 分 · {{ selfRowOf(item.id)?.comment || '无评语' }}</template>
              <template v-else>尚未自评（提交上级评分前需本人先完成自评）</template>
            </p>
          </div>
        </Panel>
      </div>
    </template>

    <!-- 下钻抽屉：可归集指标的来源 KR / 任务清单与人工校准量 -->
    <Drawer
      :open="drillOpen"
      title="归集下钻"
      :sub="drillEmployee ? `${drillEmployee.employeeName} · 可归集指标来源明细` : ''"
      :width="860"
      @close="drillOpen = false"
    >
      <PerfDrilldown :detail="drillEmployee" :loading="previewLoading" @mark-na="(item) => drillEmployee && openNa(drillEmployee, item)" />
    </Drawer>

    <!-- 不适用标记（D-06.5） -->
    <FormDialog
      :open="naOpen"
      title="标记指标不适用"
      sub="仅「经 HR/管理者显式标记」的例外状态（新入职当年 / 长假 / 借调 / 试用期未定目标）可标记；标记后该指标按剩余权重归一化。"
      :width="560"
      :saving="naSaving"
      submit-text="保存标记"
      @close="naOpen = false"
      @submit="submitNa"
    >
      <Field label="指标项" span>
        <Select
          v-model="naForm.itemId"
          :options="naItemOptions()"
          placeholder="选择可归集指标"
          @update:model-value="syncNaFromItem"
        />
      </Field>
      <Field label="处理方式" group span>
        <Switch v-model="naForm.notApplicable" :label="naForm.notApplicable ? '标记为不适用（按剩余权重归一化）' : '取消不适用标记（恢复按 0 / 归集值计）'" />
      </Field>
      <Field
        v-if="naForm.notApplicable"
        label="不适用原因"
        required
        span
        :error="naError"
        hint="将随标记一起留痕，示例：新入职当年未设定目标"
      >
        <TextInput v-model="naForm.reason" placeholder="填写原因" multiline :rows="2" :invalid="!!naError" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
