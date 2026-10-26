<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Button,
  DataTable,
  Field,
  Metric,
  PageHeader,
  Panel,
  SectionLabel,
  Segmented,
  SpecPlate,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import TrendChart from '@/components/charts/TrendChart.vue'
import { insightApi, type InsightMetricReadout, type InsightTaskMemberRow, type InsightTaskOverview } from '@/api/insight'
import { pushToast, toastError } from '@/composables/toast'
import {
  METRIC_STATUS_LABEL,
  RANGE_PRESETS,
  TASK_CORE_METRIC_CODES,
  dateText,
  isIsoDate,
  isOnline,
  isPerfAligned,
  metricElementRows,
  percentText,
  rangeOfPreset,
  type InsightRangePreset,
} from './support'

/* ============================================================
   任务总览（M13 · 7.2）
   ------------------------------------------------------------
   统计周期内顶层任务的完成与时限表现，并把口径与 M05 绩效归集对齐：
   任务完成率 / 时限达标率 / KR 达成率三项与 M05 同码同口径，可逐项核对。
   看板数据由后端实时聚合（经 M02/M03 只读端口取数），前端不缓存、
   不写回、不自行统计；无数据时显示「暂无数据」而不是 0%。
   ============================================================ */

const overview = ref<InsightTaskOverview | null>(null)
const loading = ref(false)
const preset = ref<InsightRangePreset>('30d')
const range = reactive({ startDate: '', endDate: '' })

const specRows = computed(() => {
  const period = overview.value?.period
  const scope = overview.value?.scope
  return [
    { label: '统计区间', value: period ? `${period.startDate} ~ ${period.endDate}` : '—' },
    { label: '时区', value: period?.timezone ?? '+08:00' },
    { label: '数据范围', value: scope ? (scope.allScope ? '全部数据范围' : '本人 + 本部门') : '—' },
    { label: '可见成员', value: scope ? `${scope.visibleUserCount} 人` : '—' },
    { label: '取数方式', value: overview.value ? '实时聚合 · 不缓存' : '—' },
  ]
})

const coreMetrics = computed<InsightMetricReadout[]>(() =>
  TASK_CORE_METRIC_CODES
    .map(code => overview.value?.metrics?.[code])
    .filter((metric): metric is InsightMetricReadout => Boolean(metric)),
)

const trendValues = computed(() => overview.value?.trend.map(row => row.dueCount) ?? [])
const trendDone = computed(() => overview.value?.trend.map(row => row.doneCount) ?? [])
const trendLabels = computed(() => overview.value?.trend.map(row => row.date.slice(5)) ?? [])
const trendBand = computed<[number, number] | null>(() => {
  const length = trendValues.value.length
  return length >= 2 ? [length - 2, length - 1] : null
})

async function load() {
  if (!isIsoDate(range.startDate) || !isIsoDate(range.endDate)) {
    pushToast('统计起止日期格式应为 yyyy-MM-dd', 'danger')
    return
  }
  if (range.endDate < range.startDate) {
    pushToast('统计结束日期不能早于开始日期', 'danger')
    return
  }
  loading.value = true
  try {
    overview.value = await insightApi.tasks.overview({
      startDate: range.startDate,
      endDate: range.endDate,
    })
  } catch (error) {
    toastError(error, '任务统计加载失败')
  } finally {
    loading.value = false
  }
}

function applyPreset(value: InsightRangePreset) {
  preset.value = value
  const next = rangeOfPreset(value)
  range.startDate = next.startDate
  range.endDate = next.endDate
  return load()
}

onMounted(() => applyPreset('30d'))

async function refresh() {
  await load()
  pushToast('任务统计已刷新')
}

const columns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 210 },
  { key: 'deptName', title: '部门', width: 140 },
  { key: 'taskTotal', title: '顶层任务', width: 100, align: 'right' },
  { key: 'taskDone', title: '已完成', width: 92, align: 'right' },
  { key: 'taskOnTime', title: '按时完成', width: 100, align: 'right' },
  { key: 'taskOverdue', title: '逾期', width: 84, align: 'right' },
  { key: 'taskCompletionRate', title: '任务完成率', width: 124, align: 'right' },
  { key: 'timeComplianceRate', title: '时限达标率', width: 124, align: 'right' },
  { key: 'krAchievement', title: 'KR 达成率', width: 118, align: 'right' },
  { key: 'krCount', title: 'KR 数', width: 84, align: 'right' },
]

function rateText(row: InsightTaskMemberRow, value: number | null): string {
  return row.missing ? '暂无数据' : percentText(value)
}

function metricStatusText(metric: InsightMetricReadout): string {
  return METRIC_STATUS_LABEL[metric.status] || metric.status
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="任务总览"
      desc="统计周期内顶层任务的完成与时限表现，并把口径与绩效归集对齐：任务完成率、时限达标率、KR 达成率三项与 M05 同码同口径。数据为实时聚合，只读不缓存。"
    >
      <template #spec>
        <SpecPlate title="统计与口径" :rows="specRows" />
      </template>
      <Button variant="secondary" icon="refresh" :loading="loading" @click="refresh">刷新</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="统计区间" />
        <div style="display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap">
          <Segmented :model-value="preset" :options="RANGE_PRESETS" label="快捷区间" @update:model-value="applyPreset" />
          <Field label="开始日期">
            <TextInput v-model="range.startDate" placeholder="yyyy-MM-dd" />
          </Field>
          <Field label="结束日期">
            <TextInput v-model="range.endDate" placeholder="yyyy-MM-dd" @enter="load" />
          </Field>
          <Button variant="primary" icon="search" :loading="loading" @click="load">统计</Button>
        </div>
      </div>
      <p class="cell-sub" style="margin-bottom: 14px">
        统计区间最长 366 天；任务完成率、时限达标率、KR 达成率三项口径与 M05 绩效归集完全一致，可直接逐项核对。
      </p>
      <div class="readout-strip">
        <Metric label="顶层任务" :value="overview?.totals.taskTotal" unit="个" />
        <Metric label="已完成" :value="overview?.totals.taskDone" unit="个" />
        <Metric label="按时完成" :value="overview?.totals.taskOnTime" unit="个" />
        <Metric label="逾期" :value="overview?.totals.taskOverdue" unit="个" :class="{ 'is-alert': overview?.totals.taskOverdue }" />
        <Metric label="KR 总数" :value="overview?.totals.krCount" unit="个" />
        <Metric label="参与统计成员" :value="overview?.totals.memberCount" unit="人" />
      </div>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="核心指标与口径" />
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
          <Tag tone="info">与 M05 同码同口径</Tag>
          <Tag tone="neutral">未上线口径不出数</Tag>
        </div>
      </div>
      <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px">
        <Panel v-for="metric in coreMetrics" :key="metric.code" :title="metric.name" :meta="metric.code">
          <template #tools>
            <Tag :tone="isOnline(metric.status) ? 'ok' : 'neutral'" dot>{{ metricStatusText(metric) }}</Tag>
            <Tag v-if="isPerfAligned(metric.code)" tone="info">与 M05 一致</Tag>
          </template>
          <div style="display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap">
            <Metric
              v-if="!metric.missing"
              :value="metric.value"
              :unit="metric.unit ?? ''"
              :decimals="metric.unit === '%' ? 1 : 2"
              size="l"
            />
            <Tag v-else tone="neutral">暂无数据</Tag>
          </div>
          <p v-if="metric.note" class="cell-sub" style="margin-top: 6px; line-height: 1.7">{{ metric.note }}</p>
          <dl style="margin: 14px 0 0; display: flex; flex-direction: column; gap: 10px">
            <div v-for="item in metricElementRows(metric)" :key="item.label">
              <dt class="faint" style="font-size: 11.5px">{{ item.label }}</dt>
              <dd class="soft" style="margin: 4px 0 0; font-size: 12.5px; line-height: 1.7">{{ item.value }}</dd>
            </div>
          </dl>
        </Panel>
      </div>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="完成与到期趋势" />
        <span class="cell-sub">按自然日展开：到期 = 当日截止的顶层任务，已完成 = 当日完成的顶层任务</span>
      </div>
      <TrendChart
        v-if="trendValues.length"
        :values="trendValues"
        :secondary="trendDone"
        :labels="trendLabels"
        :band="trendBand"
        unit=" 个"
        primary-label="到期"
        secondary-label="已完成"
        :height="236"
        :dots="false"
      />
      <p v-else class="band-empty faint">暂无趋势数据</p>
    </section>

    <Panel class="reveal" title="成员分布" :meta="`共 ${overview?.members.length ?? 0} 位成员`">
      <template #tools>
        <Tag tone="info">只读聚合</Tag>
      </template>
      <DataTable
        :columns="columns"
        :data="overview?.members ?? []"
        row-key="userId"
        :loading="loading"
        :min-width="1180"
        empty-text="统计区间内没有可见成员的任务与 KR 数据"
      >
        <template #cell-nickname="{ row }">
          <div>
            <div class="cell-title">
              {{ row.nickname || row.username || `用户 #${row.userId}` }}
              <Tag v-if="row.missing" tone="neutral" style="margin-left: 6px">暂无数据</Tag>
            </div>
            <div class="cell-sub">
              {{ row.username || '' }}<template v-if="row.noDataNote"> · {{ row.noDataNote }}</template>
            </div>
          </div>
        </template>
        <template #cell-deptName="{ row }"><span class="muted">{{ row.deptName || '—' }}</span></template>
        <template #cell-taskTotal="{ row }"><span class="tnum">{{ row.taskTotal }}</span></template>
        <template #cell-taskDone="{ row }"><span class="tnum">{{ row.taskDone }}</span></template>
        <template #cell-taskOnTime="{ row }"><span class="tnum">{{ row.taskOnTime }}</span></template>
        <template #cell-taskOverdue="{ row }">
          <span class="tnum" :style="row.taskOverdue ? { color: 'var(--red-text)' } : undefined">{{ row.taskOverdue }}</span>
        </template>
        <template #cell-taskCompletionRate="{ row }">
          <span :class="row.missing ? 'soft' : 'tnum'">{{ rateText(row, row.taskCompletionRate) }}</span>
        </template>
        <template #cell-timeComplianceRate="{ row }">
          <span :class="row.missing ? 'soft' : 'tnum'">{{ rateText(row, row.timeComplianceRate) }}</span>
        </template>
        <template #cell-krAchievement="{ row }">
          <span :class="row.missing ? 'soft' : 'tnum'">{{ rateText(row, row.krAchievement) }}</span>
        </template>
        <template #cell-krCount="{ row }"><span class="tnum">{{ row.krCount }}</span></template>
      </DataTable>
      <p class="faint" style="font-size: 12px; line-height: 1.8; margin-top: 10px">
        成员分布按顶层承接量倒序，子任务不计入分子分母；无任务且无 KR 的成员不出现。统计只读，不保存明细，也不提供手工填报入口。
      </p>
    </Panel>

    <p v-if="overview?.period" class="faint" style="font-size: 12px; line-height: 1.8">
      统计截止 {{ dateText(overview.period.asOf) }}，区间 {{ overview.period.rangeDays }} 天；{{ overview.cache }}。
      {{ overview.scope.dataScopeRule }}
    </p>
  </div>
</template>
