<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  DataTable,
  DeltaPill,
  Drawer,
  Field,
  Pagination,
  Panel,

  Select,
  SpecPlate,
  Switch,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import PerfDrilldown from './components/PerfDrilldown.vue'
import {
  APPEAL_STATUS_OPTIONS,
  appealStatusLabel,
  appealStatusTone,
  cycleStatusLabel,
  cycleStatusTone,
  resultStatusLabel,
  scoreText,
} from './labels'
import {
  perfApi,
  type PerfAppeal,
  type PerfCollectEmployee,
  type PerfCycle,
  type PerfResult,
} from '@/api/perf'
import { useListPage } from '@/composables/list'
import { currentUser } from '@/composables/auth'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'

/* 结果归档：按周期末快照归集并加权固化总分 → 归档（只读）→ 锁定（不可逆）。
   锁定后所有编辑入口关闭，只能对本人结果提交申诉；申诉通过可定向调整总分并留痕。 */

/* ── 周期选择 ── */
const cycles = ref<PerfCycle[]>([])
const cycleId = ref<number | null>(null)
const cycle = computed(() => cycles.value.find(item => item.id === cycleId.value) ?? null)
const cycleStatus = computed(() => cycle.value?.status ?? null)
const cycleLocked = computed(() => cycleStatus.value === 'locked')
const canArchive = computed(() => cycleStatus.value === 'scoring')
const canLock = computed(() => cycleStatus.value === 'archived')
const cycleOptions = computed(() => cycles.value.map(item => ({
  label: item.cycleName,
  value: item.id,
  hint: cycleStatusLabel(item.status),
})))

async function loadCycles() {
  try {
    const page = await perfApi.cycles.list({ pageNum: 1, pageSize: 100 })
    cycles.value = page.rows
    if (!cycleId.value && page.rows.length) {
      const preferred = page.rows.find(item => item.status === 'scoring')
        ?? page.rows.find(item => item.status === 'archived')
        ?? page.rows[0]
      cycleId.value = preferred.id
    }
  } catch (error) {
    toastError(error, '绩效周期加载失败')
  }
}

/* ── 结果列表 ── */
const resultsList = useListPage<PerfResult, { cycleId: number }>({
  query: { cycleId: 0 },
  immediate: false,
  fetch: (params) => {
    const id = Number(params.cycleId ?? 0)
    if (!id) return Promise.resolve({ rows: [], total: 0, pageNum: 1, pageSize: 10 })
    return perfApi.results.list(params)
  },
})
const resultRows = computed(() => resultsList.rows.value)
const resultTotal = computed(() => resultsList.total.value)

const resultColumns: Column[] = [
  { key: 'employeeName', title: '员工', minWidth: 180 },
  { key: 'totalScore', title: '总分', width: 110 },
  { key: 'selfScore', title: '自评参考', width: 170 },
  { key: 'collectScore', title: '可归集加权', width: 120 },
  { key: 'subjectiveScore', title: '主观加权', width: 110 },
  { key: 'effectiveWeight', title: '生效权重', width: 100 },
  { key: 'naWeight', title: '不适用权重', width: 116 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'archiveTime', title: '归档时间', width: 150 },
  { key: 'actions', title: '操作', width: 120, className: 'col-actions' },
]

const specRows = computed(() => {
  const rows = resultRows.value
  const totals = rows.map(row => Number(row.totalScore)).filter(value => !Number.isNaN(value))
  const average = totals.length ? totals.reduce((sum, value) => sum + value, 0) / totals.length : null
  return [
    { label: '周期状态', value: cycleStatusLabel(cycleStatus.value) },
    { label: '结果数', value: `${resultTotal.value} 人` },
    { label: '平均总分（本页）', value: average === null ? '—' : scoreText(average) },
    { label: '已锁定（本页）', value: `${rows.filter(row => row.status === 'locked').length} 人` },
  ]
})

const selfEmployeeId = computed(() => currentUser.value?.id ?? null)

function isOwnResult(row: PerfResult) {
  return selfEmployeeId.value !== null && row.employeeId === selfEmployeeId.value
}

function appealDisabledReason(row: PerfResult) {
  if (row.status !== 'locked') return '结果锁定后才可申诉'
  if (!isOwnResult(row)) return '只能对本人的绩效结果提交申诉'
  return '提交申诉'
}

async function loadResults() {
  if (!cycleId.value) return
  resultsList.query.cycleId = cycleId.value
  await resultsList.search()
}

/* ── 归档 / 锁定 ── */
const archiving = ref(false)
const locking = ref(false)

async function archive() {
  if (!cycleId.value || !cycle.value) return
  const ok = await confirm({
    title: '归档绩效结果',
    message: `归档「${cycle.value.cycleName}」会按周期末快照重新归集并加权计算总分，随后结果只读。归档后仍可锁定，锁定不可逆。`,
    confirmText: '归档结果',
  })
  if (!ok) return
  archiving.value = true
  try {
    const result = await perfApi.results.archive(cycleId.value)
    pushToast(`已归档 ${result.archivedCount} 人绩效结果`)
    await Promise.all([loadCycles(), loadResults()])
  } catch (error) {
    toastError(error, '结果归档失败')
  } finally {
    archiving.value = false
  }
}

async function lock() {
  if (!cycleId.value || !cycle.value) return
  const ok = await confirm({
    title: '锁定绩效结果',
    message: `锁定「${cycle.value.cycleName}」后结果永久只读，任何修改只能通过申诉流程，且锁定不可逆。确认锁定吗？`,
    confirmText: '确认锁定',
    danger: true,
  })
  if (!ok) return
  locking.value = true
  try {
    const result = await perfApi.results.lock(cycleId.value)
    pushToast(`已锁定 ${result.lockedCount} 人绩效结果，修改只能走申诉`)
    await Promise.all([loadCycles(), loadResults()])
  } catch (error) {
    toastError(error, '结果锁定失败')
  } finally {
    locking.value = false
  }
}

/* ── 结果下钻（可追溯 KR / 任务 / 人工校准量） ── */
const traceOpen = ref(false)
const traceLoading = ref(false)
const traceDetail = ref<PerfCollectEmployee | null>(null)
const traceResult = ref<PerfResult | null>(null)

async function openTrace(row: PerfResult) {
  traceOpen.value = true
  traceLoading.value = true
  traceResult.value = row
  traceDetail.value = null
  try {
    const trace = await perfApi.results.trace(row.id)
    traceResult.value = trace.result
    traceDetail.value = trace.detail
  } catch (error) {
    toastError(error, '结果下钻加载失败')
  } finally {
    traceLoading.value = false
  }
}

/* ── 申诉（提交） ── */
const appealOpen = ref(false)
const appealSaving = ref(false)
const appealTarget = ref<PerfResult | null>(null)
const appealReason = ref('')
const appealError = ref('')

function openAppeal(row: PerfResult) {
  appealTarget.value = row
  appealReason.value = ''
  appealError.value = ''
  appealOpen.value = true
}

async function submitAppeal() {
  if (!appealTarget.value) return
  if (!appealReason.value.trim()) {
    appealError.value = '请填写申诉理由'
    return
  }
  appealError.value = ''
  appealSaving.value = true
  try {
    await perfApi.appeals.create(appealTarget.value.id, appealReason.value.trim())
    pushToast('申诉已提交，等待处理；处理结果会记录处理人与意见')
    appealOpen.value = false
    await loadAppeals()
  } catch (error) {
    toastError(error, '申诉提交失败')
  } finally {
    appealSaving.value = false
  }
}

/* ── 申诉记录（抽屉内列表 + 处理） ── */
const appealsOpen = ref(false)
const appealsList = useListPage<PerfAppeal, { cycleId: number; status: string }>({
  query: { cycleId: 0, status: '' },
  immediate: false,
  fetch: (params) => {
    const id = Number(params.cycleId ?? 0)
    if (!id) return Promise.resolve({ rows: [], total: 0, pageNum: 1, pageSize: 10 })
    return perfApi.appeals.list(params)
  },
})
const appealRows = computed(() => appealsList.rows.value)
const appealTotal = computed(() => appealsList.total.value)
const appealLoading = appealsList.loading

const appealColumns: Column[] = [
  { key: 'employeeName', title: '申诉人', width: 120 },
  { key: 'appealReason', title: '申诉理由', minWidth: 220 },
  { key: 'beforeScore', title: '申诉前', width: 90 },
  { key: 'afterScore', title: '调整后', width: 90 },
  { key: 'appealStatus', title: '状态', width: 96 },
  { key: 'handleComment', title: '处理意见', minWidth: 200 },
  { key: 'handleTime', title: '处理时间', width: 150 },
  { key: 'actions', title: '操作', width: 90, className: 'col-actions' },
]

function loadAppeals() {
  if (!cycleId.value) return Promise.resolve()
  appealsList.query.cycleId = cycleId.value
  return appealsList.search()
}

function openAppeals() {
  appealsOpen.value = true
  loadAppeals()
}

const handleTarget = ref<PerfAppeal | null>(null)
const handleSaving = ref(false)
const handleForm = reactive<{ approved: boolean; afterScore: number | null; comment: string }>({
  approved: true, afterScore: null, comment: '',
})
const handleError = ref('')

function openHandle(row: PerfAppeal) {
  handleTarget.value = row
  handleForm.approved = true
  handleForm.afterScore = row.afterScore ?? null
  handleForm.comment = ''
  handleError.value = ''
}

async function submitHandle() {
  if (!handleTarget.value) return
  if (handleForm.approved && !handleForm.comment.trim()) {
    handleError.value = '通过申诉必须填写处理意见'
    return
  }
  if (handleForm.afterScore !== null && (Number(handleForm.afterScore) < 0 || Number(handleForm.afterScore) > 100)) {
    handleError.value = '调整分必须在 0 - 100 之间'
    return
  }
  handleError.value = ''
  handleSaving.value = true
  try {
    await perfApi.appeals.handle(handleTarget.value.id, {
      approved: handleForm.approved,
      comment: handleForm.comment.trim() || undefined,
      afterScore: handleForm.approved ? handleForm.afterScore : null,
    })
    pushToast(handleForm.approved ? '申诉已通过，调整分已应用到结果' : '申诉已驳回，结果保持不变')
    handleTarget.value = null
    await Promise.all([loadAppeals(), loadResults()])
  } catch (error) {
    toastError(error, '申诉处理失败')
  } finally {
    handleSaving.value = false
  }
}

/* ── 联动 ── */
watch(() => appealsList.query.status, () => {
  if (appealsOpen.value) loadAppeals()
})

watch(cycleId, () => {
  traceOpen.value = false
  traceDetail.value = null
  loadResults()
  if (appealsOpen.value) loadAppeals()
})

onMounted(loadCycles)
</script>

<template>
  <ListPage
    title="结果归档"
    desc="按周期末快照归集并加权固化总分；归档后只读，锁定不可逆——锁定后任何修改只能通过申诉流程。"
    table-title="绩效结果"
    :list="resultsList"
    :columns="resultColumns"
    :min-width="1360"
  >
    <template #spec>
      <SpecPlate :rows="specRows" title="结果概况" />
    </template>

    <template #actions>
      <PermissionButton perm="perf:result:archive" variant="primary" icon="file" :loading="archiving" :disabled="!canArchive" @click="archive">
        归档结果
      </PermissionButton>
      <PermissionButton perm="perf:result:lock" variant="secondary" icon="lock" :loading="locking" :disabled="!canLock" @click="lock">
        锁定结果
      </PermissionButton>
      <PermissionButton perm="perf:result:list" icon="log" @click="openAppeals">申诉记录</PermissionButton>
    </template>

    <template #before>
      <div class="board-toolbar reveal">
        <Field label="绩效周期" style="width: 260px">
          <Select v-model="cycleId" :options="cycleOptions" placeholder="选择绩效周期" filterable />
        </Field>
        <Tag v-if="cycleStatus" :tone="cycleStatusTone(cycleStatus)" dot>{{ cycleStatusLabel(cycleStatus) }}</Tag>
        <span class="spacer" />
        <span v-if="cycleLocked" class="soft" style="font-size: 12.5px">
          周期已锁定：结果永久只读，任何修改只能通过申诉流程；锁定不可逆。
        </span>
        <span v-else-if="canArchive" class="soft" style="font-size: 12.5px">周期处于评分中，可归档结果（归档后只读）。</span>
        <span v-else-if="canLock" class="soft" style="font-size: 12.5px">结果已归档，确认无误后可锁定（锁定不可逆）。</span>
      </div>
    </template>

    <template #cell-employeeName="{ row }">
      <div>
        <div class="cell-title">{{ row.employeeName || '未知员工' }}</div>
        <div class="cell-sub">{{ row.employeeUsername ? `@${row.employeeUsername}` : '—' }}</div>
      </div>
    </template>
    <template #cell-totalScore="{ row }">
      <span class="cell-title tnum" style="font-size: 15px">{{ scoreText(row.totalScore) }}</span>
    </template>
    <template #cell-selfScore="{ row }">
      <div v-if="row.selfScore !== null" style="display: flex; align-items: center; gap: 6px">
        <span class="tnum soft">{{ scoreText(row.selfScore) }}</span>
        <DeltaPill :value="Number(row.totalScore) - Number(row.selfScore)" suffix="分" label="最终总分与自评参考的差值" />
      </div>
      <span v-else class="faint">—</span>
    </template>
    <template #cell-collectScore="{ row }"><span class="tnum">{{ scoreText(row.collectScore) }}</span></template>
    <template #cell-subjectiveScore="{ row }"><span class="tnum">{{ scoreText(row.subjectiveScore) }}</span></template>
    <template #cell-effectiveWeight="{ row }"><span class="tnum">{{ scoreText(row.effectiveWeight, '%') }}</span></template>
    <template #cell-naWeight="{ row }">
      <Tag v-if="Number(row.naWeight) > 0" tone="danger" dot>{{ scoreText(row.naWeight, '%') }}</Tag>
      <span v-else class="tnum">{{ scoreText(row.naWeight, '%') }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'locked' ? 'danger' : row.status === 'archived' ? 'ok' : 'neutral'" dot>
        {{ resultStatusLabel(row.status) }}
      </Tag>
    </template>
    <template #cell-archiveTime="{ row }"><span class="tnum soft">{{ row.archiveTime || '—' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="perf:result:list" icon-only icon="eye" label="结果下钻" @click="openTrace(row)" />
        <span :title="appealDisabledReason(row)">
          <PermissionButton
            perm="perf:result:appeal"
            icon-only
            icon="edit"
            label="提交申诉"
            :disabled="row.status !== 'locked' || !isOwnResult(row)"
            @click="openAppeal(row)"
          />
        </span>
      </div>
    </template>


    <!-- 结果下钻 -->
    <Drawer
      :open="traceOpen"
      title="结果下钻"
      :sub="traceResult ? `${traceResult.employeeName ?? '—'} · 总分 ${scoreText(traceResult.totalScore)} · ${resultStatusLabel(traceResult.status)}` : ''"
      :width="880"
      @close="traceOpen = false"
    >
      <PerfDrilldown :detail="traceDetail" :loading="traceLoading" readonly />
    </Drawer>

    <!-- 申诉记录（锁定后唯一修改通道） -->
    <Drawer
      :open="appealsOpen"
      title="绩效申诉记录"
      sub="锁定后任何修改只能走申诉：被评人提交理由 → 管理者处理 → 通过可按调整分定向修改总分并留痕。"
      :width="980"
      @close="appealsOpen = false"
    >
      <div class="board-toolbar" style="margin-bottom: 12px">
        <Select v-model="appealsList.query.status" :options="APPEAL_STATUS_OPTIONS" style="width: 160px" />
        <PermissionButton perm="perf:result:list" icon="refresh" size="sm" :loading="appealLoading" @click="loadAppeals">刷新</PermissionButton>
        <span class="spacer" />
        <span class="bento-meta">共 {{ appealTotal }} 条申诉</span>
      </div>
      <Panel title="申诉明细">
        <DataTable :columns="appealColumns" :data="appealRows" row-key="id" :loading="appealLoading" :min-width="900">
          <template #cell-employeeName="{ row }"><span class="cell-title">{{ row.employeeName || '未知员工' }}</span></template>
          <template #cell-appealReason="{ row }"><span class="soft">{{ row.appealReason }}</span></template>
          <template #cell-beforeScore="{ row }"><span class="tnum">{{ scoreText(row.beforeScore) }}</span></template>
          <template #cell-afterScore="{ row }"><span class="tnum">{{ scoreText(row.afterScore) }}</span></template>
          <template #cell-appealStatus="{ row }">
            <Tag :tone="appealStatusTone(row.appealStatus)" dot>{{ appealStatusLabel(row.appealStatus) }}</Tag>
          </template>
          <template #cell-handleComment="{ row }">
            <div v-if="row.handlerName">
              <div class="soft">{{ row.handleComment || '—' }}</div>
              <div class="cell-sub">处理人：{{ row.handlerName }}<template v-if="row.applied"> · 已应用到结果</template></div>
            </div>
            <span v-else class="faint">待处理</span>
          </template>
          <template #cell-handleTime="{ row }"><span class="tnum soft">{{ row.handleTime || '—' }}</span></template>
          <template #cell-actions="{ row }">
            <PermissionButton
              v-if="row.appealStatus === 'pending'"
              perm="perf:result:appealHandle"
              icon-only
              icon="check"
              label="处理申诉"
              @click="openHandle(row)"
            />
            <span v-else class="faint" style="font-size: 12px">已处理</span>
          </template>
        </DataTable>
        <Pagination
          :total="appealTotal"
          :page="appealsList.page.value"
          :page-size="appealsList.pageSize.value"
          @update:page="appealsList.setPage($event)"
          @update:page-size="appealsList.setPageSize($event)"
        />
      </Panel>
    </Drawer>

    <!-- 提交申诉 -->
    <FormDialog
      :open="appealOpen"
      title="提交绩效申诉"
      :sub="appealTarget ? `结果总分 ${scoreText(appealTarget.totalScore)} 分；申诉理由会随记录留痕，处理结果由管理者填写。` : ''"
      :width="560"
      :saving="appealSaving"
      submit-text="提交申诉"
      @close="appealOpen = false"
      @submit="submitAppeal"
    >
      <Field label="申诉理由" required span :error="appealError" hint="说明认为结果不合理的具体原因，便于复核">
        <TextInput v-model="appealReason" placeholder="如：某项主观指标评分依据未同步，申请复核" multiline :rows="4" :invalid="!!appealError" />
      </Field>
    </FormDialog>

    <!-- 处理申诉 -->
    <FormDialog
      :open="Boolean(handleTarget)"
      title="处理绩效申诉"
      :sub="handleTarget ? `申诉人：${handleTarget.employeeName || '—'}；申诉前总分 ${scoreText(handleTarget.beforeScore)} 分` : ''"
      :width="560"
      :saving="handleSaving"
      submit-text="提交处理结果"
      @close="handleTarget = null"
      @submit="submitHandle"
    >
      <Field label="处理方式" group span>
        <Switch v-model="handleForm.approved" :label="handleForm.approved ? '通过申诉（可定向调整总分）' : '驳回申诉（结果保持不变）'" />
      </Field>
      <Field
        v-if="handleForm.approved"
        label="调整后总分"
        span
        hint="选填，0-100；填写后会应用到该员工绩效结果并记录前后分值"
      >
        <TextInput v-model="handleForm.afterScore" type="number" placeholder="如 88" />
      </Field>
      <Field label="处理意见" :required="handleForm.approved" span :error="handleError">
        <TextInput v-model="handleForm.comment" placeholder="填写复核结论与依据" multiline :rows="3" :invalid="!!handleError" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
