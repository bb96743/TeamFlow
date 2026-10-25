<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Button,
  DataTable,
  Metric,
  PageHeader,
  Panel,
  SectionLabel,
  SpecPlate,
  Tag,
  type Column,
} from '@/components/ui'
import { personalApi, type PersonalProfile } from '@/api/personal'
import { toastError } from '@/composables/toast'

/* ============================================================
   效能画像（M10 · 2.3.5）
   ------------------------------------------------------------
   只读实时聚合：任务吞吐、响应时长、承接量、工作投入与复盘。
   数据全部来自个人效率模块自有表与任务只读端口，页面不落任何明细画像。
   指标口径由统计模块统一登记，本页不重新定义。
   ============================================================ */

const profile = ref<PersonalProfile | null>(null)
const loading = ref(true)

const BIZ_LABEL: Record<string, string> = {
  task_assign: '任务指派',
  task_approval: '审批待办',
  meeting_invite: '会议邀请',
}

async function load() {
  loading.value = true
  try {
    profile.value = await personalApi.profile.get()
  } catch (error) {
    toastError(error, '效能画像加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

const throughput = computed(() => profile.value?.todoThroughput)
const workTime = computed(() => profile.value?.workTime)
const focus = computed(() => profile.value?.focus)

const bizColumns: Column[] = [
  { key: 'bizLabel', title: '事项类型', minWidth: 160 },
  { key: 'received', title: '收到', width: 100, align: 'right' },
  { key: 'handled', title: '已处理', width: 100, align: 'right' },
  { key: 'avgHandleMinutes', title: '平均响应（分钟）', width: 168, align: 'right' },
]
const bizRows = computed(() => (profile.value?.byBizType ?? []).map(row => ({
  ...row,
  bizLabel: BIZ_LABEL[row.bizType] ?? row.bizType,
  handleRate: Number(row.received) > 0 ? Math.round((Number(row.handled) / Number(row.received)) * 1000) / 10 : 0,
})))

const taskColumns: Column[] = [
  { key: 'taskId', title: '任务', minWidth: 200 },
  { key: 'totalMinutes', title: '累计工时（分钟）', width: 168, align: 'right' },
  { key: 'totalHours', title: '折合小时', width: 140, align: 'right' },
]
const topTasks = computed(() => profile.value?.workTime.topTasks ?? [])

const specRows = computed(() => [
  { label: '统计方式', value: '实时聚合' },
  { label: '数据来源', value: (profile.value?.sourceTables ?? []).join(' / ') || '—' },
  { label: '生成时间', value: profile.value?.generatedAt ?? '—' },
])
</script>

<template>
  <div class="page">
    <PageHeader
      title="效能画像"
      desc="只读聚合视图：从任务与个人记录中计算任务吞吐、响应时长、承接量与工作投入。这里是观察口径，不是手工填报入口；指标口径由统计模块统一登记。"
    >
      <template #spec>
        <SpecPlate title="口径与来源" :rows="specRows" />
      </template>
      <Button variant="secondary" icon="refresh" :loading="loading" @click="load">刷新</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="待办吞吐与响应" />
        <Tag tone="info">只读</Tag>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">收到事项</div>
          <Metric :value="throughput?.received ?? 0" unit="项" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">已处理</div>
          <Metric :value="throughput?.handled ?? 0" unit="项" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">仍待处理</div>
          <Metric :value="throughput?.open ?? 0" unit="项" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">平均响应时长</div>
          <Metric :value="throughput?.avgHandleMinutes ?? 0" unit="分钟" :decimals="1" size="l" />
        </div>
      </div>
      <p class="cell-sub">
        吞吐与响应时长按收件箱事项计算：从事项投递到你处理完成的时间计入响应时长；已忽略的事项不计入已完成。
      </p>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="按事项类型分布" />
        <span class="cell-sub">承接量按业务类型统计，便于看出哪类事项占用你最多处理精力。</span>
      </div>
      <Panel :title="'类型分布'" :meta="`共 ${bizRows.length} 类`">
        <DataTable :columns="bizColumns" :data="bizRows" row-key="bizType" :loading="loading">
          <template #cell-bizLabel="{ row }"><span class="cell-title">{{ row.bizLabel }}</span></template>
          <template #cell-received="{ row }"><span class="tnum">{{ row.received }}</span></template>
          <template #cell-handled="{ row }">
            <span class="tnum">{{ row.handled }}</span>
            <span class="cell-sub">完成率 {{ row.handleRate }}%</span>
          </template>
          <template #cell-avgHandleMinutes="{ row }">
            <span class="tnum soft">{{ row.avgHandleMinutes ?? '—' }}</span>
          </template>
        </DataTable>
      </Panel>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="工作投入" />
        <span class="cell-sub">工时来自任务级填报，仅统计已关联任务的记录。</span>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">累计工时</div>
          <Metric :value="workTime?.totalMinutes ?? 0" unit="分钟" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">涉及任务</div>
          <Metric :value="workTime?.taskCount ?? 0" unit="个" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">工时记录</div>
          <Metric :value="workTime?.logCount ?? 0" unit="条" size="l" />
        </div>
      </div>
      <Panel title="工时投入最多的任务" :meta="`展示前 ${topTasks.length} 个`">
        <DataTable :columns="taskColumns" :data="topTasks" row-key="taskId" :loading="loading">
          <template #cell-taskId="{ row }"><span class="cell-title">任务 #{{ row.taskId }}</span></template>
          <template #cell-totalMinutes="{ row }"><span class="tnum">{{ row.totalMinutes }}</span></template>
          <template #cell-totalHours="{ row }">
            <span class="tnum soft">{{ (Number(row.totalMinutes) / 60).toFixed(2) }}</span>
          </template>
        </DataTable>
      </Panel>
    </section>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="专注与复盘" />
        <span class="cell-sub">专注会话与复盘记录只统计本人数据，不含团队汇总。</span>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">专注会话</div>
          <Metric :value="focus?.sessionCount ?? 0" unit="次" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">专注时长</div>
          <Metric :value="focus?.totalMinutes ?? 0" unit="分钟" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">复盘记录</div>
          <Metric :value="profile?.reviewCount ?? 0" unit="篇" size="l" />
        </div>
      </div>
    </section>

    <p class="faint reveal">
      本页为只读聚合，不保存明细画像，也不提供修改入口。指标口径由统计模块登记，若与团队看板口径不一致，以统计模块的登记为准。
    </p>
  </div>
</template>
