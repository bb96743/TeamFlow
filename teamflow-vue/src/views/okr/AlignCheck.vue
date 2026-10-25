<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, DataTable, Field, Metric, PageHeader, Panel, SectionLabel, Select, Tag } from '@/components/ui'
import { okrApi, type OkrAlignCheck, type OkrAlignCheckRow, type OkrPeriodRow } from '@/api/okr'
import { pathOf } from '@/composables/auth'
import { pushToast, toastError } from '@/composables/toast'
import { LEVEL_TEXT, OBJECTIVE_STATUS_TEXT, OBJECTIVE_STATUS_TONE, dateTimeText } from './support'

/* ============================================================
   对齐检查：把「目标管理上的断点」列成可处理的清单——
   1) 有目标但没有承接人（目标无人负责）
   2) 有目标但没有关键结果（目标无法衡量）
   3) 有任务但没有关联目标（执行与目标脱节；依赖任务侧数据，本期返回空并注明）
   这是一张体检报告：只读、按周期检查，便于在复盘会上逐条补齐。
   ============================================================ */
const router = useRouter()

const periods = ref<OkrPeriodRow[]>([])
const periodId = ref<number | null>(null)
const report = ref<OkrAlignCheck | null>(null)
const loading = ref(false)

const periodOptions = computed(() => periods.value.map(p => ({ label: p.periodName, value: p.id })))

async function loadPeriods() {
  try {
    const page = await okrApi.periods.list({ pageNum: 1, pageSize: 200 })
    periods.value = page.rows
    if (periodId.value === null && page.rows.length) {
      const active = page.rows.find(p => p.status === 'active')
      periodId.value = (active ?? page.rows[0]).id
    }
  } catch (error) {
    toastError(error, '目标周期加载失败')
  }
}

async function loadReport() {
  loading.value = true
  try {
    report.value = await okrApi.alignCheck(periodId.value)
  } catch (error) {
    toastError(error, '对齐检查失败')
    report.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadPeriods()
  await loadReport()
})

function pickPeriod(value: string | number | null | undefined) {
  periodId.value = value === null || value === undefined || value === '' ? null : Number(value)
  loadReport()
}

async function refresh() {
  await loadReport()
  pushToast('对齐检查已更新')
}

const unowned = computed<OkrAlignCheckRow[]>(() => report.value?.unownedObjectives ?? [])
const withoutKr = computed<OkrAlignCheckRow[]>(() => report.value?.objectivesWithoutKr ?? [])
const tasksWithoutObjective = computed<Array<Record<string, unknown>>>(() => report.value?.tasksWithoutObjective ?? [])
const summary = computed(() => ({
  unowned: report.value?.summary?.unownedObjectiveCount ?? unowned.value.length,
  withoutKr: report.value?.summary?.objectivesWithoutKrCount ?? withoutKr.value.length,
  orphanTasks: report.value?.summary?.tasksWithoutObjectiveCount ?? tasksWithoutObjective.value.length,
}))

const objectiveColumns = [
  { key: 'objectiveTitle', title: '目标', minWidth: 240 },
  { key: 'level', title: '层级', width: 96 },
  { key: 'periodName', title: '周期', width: 160 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'createTime', title: '创建时间', width: 150 },
]

const objectivePath = computed(() => pathOf('okr/objective/index'))
function goObjectives() {
  if (objectivePath.value) router.push(objectivePath.value)
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="对齐检查"
      desc="按周期检查目标管理上的断点：目标是否有人承接、目标是否可衡量、任务是否挂在目标上；断点清单供复盘会逐条补齐。"
    >
      <Button variant="secondary" icon="refresh" :loading="loading" @click="refresh">重新检查</Button>
      <Button v-if="objectivePath" variant="primary" icon="layers" @click="goObjectives">去目标制定</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="断点概览" />
        <div style="display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap">
          <Field label="检查周期">
            <Select
              :model-value="periodId"
              :options="periodOptions"
              placeholder="全部周期"
              filterable
              @update:model-value="pickPeriod"
            />
          </Field>
          <span class="eyebrow" style="padding-bottom: 10px">
            最近检查：{{ report?.generatedAt ? dateTimeText(report.generatedAt) : '—' }}
            <template v-if="report?.periodId"> · 周期 {{ periods.find(p => p.id === report?.periodId)?.periodName || report.periodId }}</template>
          </span>
        </div>
      </div>
      <div class="readout-strip">
        <Metric label="无承接人的目标" :value="summary.unowned" unit="个" :class="{ 'is-alert': summary.unowned > 0 }" />
        <Metric label="没有关键结果的目标" :value="summary.withoutKr" unit="个" />
        <Metric label="未关联目标的任务" :value="summary.orphanTasks" unit="个" />
      </div>
      <p class="faint" style="margin-top: 12px; font-size: 12px; line-height: 1.8">
        断点数量为 0 表示该周期目标管理是完整的；不为 0 时按下方清单逐条补齐，检查结果会随数据实时变化。
      </p>
    </section>

    <Panel class="reveal" title="没有承接人的目标" :meta="`共 ${unowned.length} 项`">
      <DataTable :columns="objectiveColumns" :data="unowned" :loading="loading" empty-text="没有发现无承接人的目标">
        <template #cell-objectiveTitle="{ row }">
          <span class="cell-title">{{ row.objectiveTitle }}</span>
        </template>
        <template #cell-level="{ row }">
          <Tag tone="neutral">{{ LEVEL_TEXT[row.level || ''] || row.level || '—' }}</Tag>
        </template>
        <template #cell-periodName="{ row }">
          <span class="muted">{{ row.periodName || '—' }}</span>
        </template>
        <template #cell-status="{ row }">
          <Tag :tone="OBJECTIVE_STATUS_TONE[row.status || ''] || 'neutral'" dot>
            {{ OBJECTIVE_STATUS_TEXT[row.status || ''] || row.status || '—' }}
          </Tag>
        </template>
        <template #cell-createTime="{ row }">
          <span class="tnum soft">{{ dateTimeText(row.createTime) }}</span>
        </template>
      </DataTable>
    </Panel>

    <Panel class="reveal" title="没有关键结果的目标" :meta="`共 ${withoutKr.length} 项`">
      <DataTable :columns="objectiveColumns" :data="withoutKr" :loading="loading" empty-text="每个目标都有关键结果">
        <template #cell-objectiveTitle="{ row }">
          <span class="cell-title">{{ row.objectiveTitle }}</span>
        </template>
        <template #cell-level="{ row }">
          <Tag tone="neutral">{{ LEVEL_TEXT[row.level || ''] || row.level || '—' }}</Tag>
        </template>
        <template #cell-periodName="{ row }">
          <span class="muted">{{ row.periodName || '—' }}</span>
        </template>
        <template #cell-status="{ row }">
          <Tag :tone="OBJECTIVE_STATUS_TONE[row.status || ''] || 'neutral'" dot>
            {{ OBJECTIVE_STATUS_TEXT[row.status || ''] || row.status || '—' }}
          </Tag>
        </template>
        <template #cell-createTime="{ row }">
          <span class="tnum soft">{{ dateTimeText(row.createTime) }}</span>
        </template>
      </DataTable>
    </Panel>

    <Panel class="reveal" title="没有关联目标的任务" :meta="`共 ${tasksWithoutObjective.length} 项`">
      <p v-if="report?.tasksWithoutObjectiveNote" class="faint" style="font-size: 12px; line-height: 1.8; margin-bottom: 10px">
        {{ report.tasksWithoutObjectiveNote }}
      </p>
      <DataTable
        :columns="[{ key: 'taskTitle', title: '任务', minWidth: 260 }, { key: 'assigneeName', title: '责任人', width: 120 }]"
        :data="tasksWithoutObjective"
        :loading="loading"
        empty-text="目前没有发现脱节的任务"
      >
        <template #cell-taskTitle="{ row }">
          <span class="cell-title">{{ row.taskTitle || '—' }}</span>
        </template>
        <template #cell-assigneeName="{ row }">
          <span>{{ row.assigneeName || '—' }}</span>
        </template>
      </DataTable>
    </Panel>
  </div>
</template>
