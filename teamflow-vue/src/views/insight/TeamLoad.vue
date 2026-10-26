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
  Select,
  SpecPlate,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import BarChart from '@/components/charts/BarChart.vue'
import {
  insightApi,
  type InsightMetricReadout,
  type InsightTeamEfficiency,
  type InsightTeamMemberRow,
  type InsightTeamOption,
} from '@/api/insight'
import { pushToast, toastError } from '@/composables/toast'
import {
  METRIC_STATUS_LABEL,
  RANGE_PRESETS,
  TEAM_METRIC_CODES,
  hoursText,
  isIsoDate,
  isOnline,
  isPerfAligned,
  loadLevelTone,
  metricElementRows,
  numberText,
  rangeOfPreset,
  type InsightRangePreset,
} from './support'

/* ============================================================
   团队负载（M13 · 7.3）
   ------------------------------------------------------------
   按团队查看承接量、在办量、区间内完成量与响应时长，并用变异系数（CV）
   评估承接量是否均衡；最忙成员在明细与柱状图中高亮。
   数据来自 M04 团队详情与 M03 任务只读端口，前端不缓存、不自行统计。
   ============================================================ */

const teams = ref<InsightTeamOption[]>([])
const teamId = ref<number | null>(null)
const efficiency = ref<InsightTeamEfficiency | null>(null)
const optionsLoading = ref(true)
const loading = ref(false)
const preset = ref<InsightRangePreset>('30d')
const range = reactive({ startDate: '', endDate: '' })

const teamOptions = computed(() => teams.value.map(team => ({
  label: team.teamName,
  value: team.id,
  hint: team.teamCode ?? undefined,
})))
const currentTeam = computed(() => teams.value.find(team => team.id === teamId.value))
const members = computed<InsightTeamMemberRow[]>(() => efficiency.value?.members ?? [])
const loadBalance = computed(() => efficiency.value?.loadBalance ?? null)
const notes = computed(() => efficiency.value?.notes ?? [])
const metricCards = computed<InsightMetricReadout[]>(() =>
  TEAM_METRIC_CODES
    .map(code => efficiency.value?.metrics?.[code])
    .filter((metric): metric is InsightMetricReadout => Boolean(metric)),
)
const busiestIndex = computed(() => {
  const userId = loadBalance.value?.busiestUserId
  if (!userId) return -1
  return members.value.findIndex(member => member.userId === userId)
})
const memberCvMissing = computed(() => !loadBalance.value || loadBalance.value.missing || loadBalance.value.cv === null)

const specRows = computed(() => {
  const period = efficiency.value?.period
  return [
    { label: '统计区间', value: period ? `${period.startDate} ~ ${period.endDate}` : '—' },
    { label: '时区', value: period?.timezone ?? '+08:00' },
    { label: '数据来源', value: 'M04 团队详情 + M03 任务只读端口' },
    { label: '负载口径', value: '承接量变异系数 CV' },
    { label: '取数方式', value: efficiency.value ? '实时聚合 · 不缓存' : '—' },
  ]
})

function pickTeam(value: string | number | null | undefined) {
  teamId.value = value === null || value === undefined || value === '' ? null : Number(value)
  return loadEfficiency()
}

function applyPreset(value: InsightRangePreset) {
  preset.value = value
  const next = rangeOfPreset(value)
  range.startDate = next.startDate
  range.endDate = next.endDate
  return loadEfficiency()
}

async function loadOptions() {
  optionsLoading.value = true
  try {
    teams.value = await insightApi.teams.options()
    if (!teamId.value && teams.value.length) {
      teamId.value = teams.value[0].id
    }
    const next = rangeOfPreset(preset.value)
    range.startDate = next.startDate
    range.endDate = next.endDate
    if (teamId.value) await loadEfficiency()
  } catch (error) {
    toastError(error, '团队列表加载失败')
  } finally {
    optionsLoading.value = false
  }
}

async function loadEfficiency() {
  if (!teamId.value) {
    efficiency.value = null
    return
  }
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
    efficiency.value = await insightApi.teams.efficiency({
      teamId: teamId.value,
      startDate: range.startDate,
      endDate: range.endDate,
    })
  } catch (error) {
    toastError(error, '团队负载加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadOptions)

async function refresh() {
  await loadEfficiency()
  pushToast('团队负载已刷新')
}

const ROLE_LABEL: Record<string, string> = { OWNER: '负责人', MEMBER: '成员', VIEWER: '观察者' }

const columns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 210 },
  { key: 'teamRole', title: '团队角色', width: 110 },
  { key: 'deptName', title: '部门', width: 140 },
  { key: 'taskTotal', title: '承接任务', width: 100, align: 'right' },
  { key: 'taskInProgress', title: '在办', width: 84, align: 'right' },
  { key: 'completedTaskCount', title: '区间内完成', width: 110, align: 'right' },
  { key: 'responseHours', title: '响应时长', width: 130, align: 'right' },
  { key: 'sampled', title: '采样', width: 84, align: 'center' },
]

function responseText(row: InsightTeamMemberRow): string {
  return row.responseHours === null || row.responseHours === undefined ? '暂无数据' : hoursText(row.responseHours)
}

function metricStatusText(metric: InsightMetricReadout): string {
  return METRIC_STATUS_LABEL[metric.status] || metric.status
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="团队负载"
      desc="按团队查看承接量、在办量与区间内响应时长，并用变异系数（CV）判断承接量是否均衡；数据来自团队与任务模块的只读端口，看板不缓存、不自行统计。"
    >
      <template #spec>
        <SpecPlate title="统计与口径" :rows="specRows" />
      </template>
      <Select
        :model-value="teamId"
        :options="teamOptions"
        :placeholder="optionsLoading ? '团队加载中…' : '请选择团队'"
        filterable
        @update:model-value="pickTeam"
      />
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
            <TextInput v-model="range.endDate" placeholder="yyyy-MM-dd" @enter="loadEfficiency" />
          </Field>
          <Button variant="primary" icon="search" :loading="loading" @click="loadEfficiency">统计</Button>
        </div>
      </div>
      <p class="cell-sub" style="margin-bottom: 12px">
        承接量 / 在办为当前快照，不受区间限制；区间只约束响应时长（按任务完成日期）。{{ efficiency?.period.rangeNote || '' }}
      </p>
      <div v-if="notes.length" style="display: flex; flex-direction: column; gap: 4px">
        <p v-for="note in notes" :key="note" class="cell-sub" style="margin: 0; line-height: 1.7">{{ note }}</p>
      </div>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel :title="currentTeam ? `${currentTeam.teamName} · 团队概览` : '团队概览'" />
        <Tag tone="info">数据来自 M04 / M03 只读端口</Tag>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">在册成员</div>
          <Metric :value="efficiency?.team.memberCount" unit="人" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">参与统计成员</div>
          <Metric :value="efficiency?.team.statMemberCount" unit="人" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">承接任务合计</div>
          <Metric :value="efficiency?.totals.taskTotal" unit="个" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">在办任务</div>
          <Metric :value="efficiency?.totals.taskInProgress" unit="个" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">区间内完成任务</div>
          <Metric :value="efficiency?.totals.completedTaskCount" unit="个" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">平均响应时长</div>
          <Metric
            v-if="efficiency && efficiency.totals.avgResponseHours !== null"
            :value="efficiency.totals.avgResponseHours"
            :decimals="2"
            unit="小时"
            size="l"
          />
          <Tag v-else tone="neutral">暂无数据</Tag>
          <div v-if="efficiency?.team.filteredMemberCount" class="cell-sub">
            按数据范围过滤 {{ efficiency.team.filteredMemberCount }} 名成员
          </div>
        </div>
      </div>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="负载均衡" />
        <Tag v-if="!memberCvMissing" :tone="loadLevelTone(loadBalance?.level)" dot>
          {{ loadBalance?.levelLabel || '—' }}
        </Tag>
        <Tag v-else tone="neutral">数据不足</Tag>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">变异系数 CV</div>
          <Metric v-if="!memberCvMissing" :value="loadBalance?.cv" :decimals="2" size="l" />
          <Tag v-else tone="neutral">暂无数据</Tag>
        </div>
        <div class="kv">
          <div class="kv-label">平均承接量</div>
          <div class="kv-value tnum">{{ numberText(loadBalance?.avgTasks) }}</div>
        </div>
        <div class="kv">
          <div class="kv-label">最多 / 最少</div>
          <div class="kv-value tnum">{{ numberText(loadBalance?.maxTasks) }} / {{ numberText(loadBalance?.minTasks) }}</div>
        </div>
        <div class="kv">
          <div class="kv-label">最忙成员</div>
          <div class="kv-value">
            {{ loadBalance?.busiestName || '—' }}
            <Tag v-if="loadBalance?.busiestName" tone="info" style="margin-left: 6px">承接最多</Tag>
          </div>
        </div>
      </div>
      <p class="cell-sub" style="margin-top: 12px; line-height: 1.8">{{ loadBalance?.note || '—' }}</p>
      <p class="faint" style="font-size: 12px; line-height: 1.8">
        判定阈值（与指标字典一致）：CV &lt; 0.30 均衡；0.30 ~ 0.60 一般；&gt; 0.60 失衡。在册成员不足 2 人或平均承接量为 0 时不评估，显示「数据不足」。
      </p>
      <BarChart
        v-if="members.length"
        :values="members.map(member => member.taskTotal)"
        :labels="members.map(member => member.nickname || member.username || `#${member.userId}`)"
        :highlight="busiestIndex >= 0 ? busiestIndex : null"
        :height="190"
        unit=" 个"
      />
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="团队负载指标与口径" />
        <Tag tone="neutral">未上线口径不出数</Tag>
      </div>
      <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px">
        <Panel v-for="metric in metricCards" :key="metric.code" :title="metric.name" :meta="metric.code">
          <template #tools>
            <Tag :tone="isOnline(metric.status) ? 'ok' : 'neutral'" dot>{{ metricStatusText(metric) }}</Tag>
            <Tag v-if="isPerfAligned(metric.code)" tone="info">与 M05 一致</Tag>
          </template>
          <div style="display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap">
            <Metric
              v-if="!metric.missing"
              :value="metric.value"
              :unit="metric.unit ?? ''"
              :decimals="metric.unit === '个' ? 0 : 2"
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

    <Panel class="reveal" title="成员负载明细" :meta="`共 ${members.length} 位参与统计成员`">
      <template #tools>
        <Tag tone="info">只读聚合</Tag>
      </template>
      <DataTable
        :columns="columns"
        :data="members"
        row-key="userId"
        :loading="loading"
        :min-width="980"
        empty-text="该团队在当前数据范围与统计区间内没有可统计的成员"
      >
        <template #cell-nickname="{ row }">
          <div>
            <div class="cell-title">
              {{ row.nickname || row.username || `用户 #${row.userId}` }}
              <Tag v-if="row.userId === loadBalance?.busiestUserId" tone="info" style="margin-left: 6px">承接最多</Tag>
            </div>
            <div class="cell-sub">{{ row.username || '' }}</div>
          </div>
        </template>
        <template #cell-teamRole="{ row }">
          <Tag :tone="row.teamRole === 'OWNER' ? 'info' : 'neutral'">
            {{ row.teamRoleLabel || ROLE_LABEL[row.teamRole || ''] || row.teamRole || '—' }}
          </Tag>
        </template>
        <template #cell-deptName="{ row }"><span class="muted">{{ row.deptName || '—' }}</span></template>
        <template #cell-taskTotal="{ row }">
          <span class="tnum" :style="row.userId === loadBalance?.busiestUserId ? { fontWeight: 620 } : undefined">{{ row.taskTotal }}</span>
        </template>
        <template #cell-taskInProgress="{ row }"><span class="tnum">{{ row.taskInProgress }}</span></template>
        <template #cell-completedTaskCount="{ row }"><span class="tnum">{{ row.completedTaskCount }}</span></template>
        <template #cell-responseHours="{ row }">
          <span :class="row.responseHours === null ? 'soft' : 'tnum'">{{ responseText(row) }}</span>
        </template>
        <template #cell-sampled="{ row }">
          <Tag v-if="row.sampled" tone="neutral">采样</Tag>
          <span v-else class="faint">—</span>
        </template>
      </DataTable>
      <p class="faint" style="font-size: 12px; line-height: 1.8; margin-top: 10px">
        成员明细按承接量倒序；响应时长仅统计区间内完成的顶层任务，超过采样上限时以样本均值近似并标注「采样」。团队角色只表示团队内分工，不代表系统权限。
      </p>
    </Panel>

    <p v-if="efficiency" class="faint" style="font-size: 12px; line-height: 1.8">
      统计截止 {{ efficiency.period.asOf }}，区间 {{ efficiency.period.rangeDays }} 天；{{ efficiency.cache }}。
    </p>
  </div>
</template>
