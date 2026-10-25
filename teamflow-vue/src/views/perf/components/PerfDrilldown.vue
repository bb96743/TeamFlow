<script setup lang="ts">
import { computed } from 'vue'
import { DataTable, EmptyState, Metric, Panel, SpecPlate, Tag, type Column } from '@/components/ui'
import { PermissionButton } from '@/components/business'
import { categoryLabel, percentText, progressSourceLabel, scoreText, taskStatusLabel } from '../labels'
import type { PerfCollectEmployee, PerfCollectItem, PerfKrSource, PerfTaskSource } from '@/api/perf'

/* 归集下钻面板：把可归集指标的分值追溯到来源 KR / 任务清单，并展示人工校准量与原因。
   同时用于「绩效评分·归集预览」和「结果归档·结果下钻」；readonly 时不提供不适用标记入口。 */
const props = withDefaults(defineProps<{
  detail: PerfCollectEmployee | null
  loading?: boolean
  readonly?: boolean
}>(), {
  loading: false,
  readonly: false,
})

const emit = defineEmits<{ (e: 'mark-na', item: PerfCollectItem): void }>()

const plateRows = computed(() => {
  const detail = props.detail
  if (!detail) return []
  return [
    { label: '计入权重', value: `${scoreText(detail.scoredWeight)}%` },
    { label: '不适用权重', value: `${scoreText(detail.naWeight)}%`, tone: detail.naWeight > 0 ? 'red' as const : undefined },
    { label: '关联 KR', value: `${detail.krCount} 个` },
    { label: '顶层任务', value: `${detail.taskCount} 个` },
  ]
})

const krColumns: Column[] = [
  { key: 'krTitle', title: '关键结果', minWidth: 220 },
  { key: 'progress', title: 'KR 进度', width: 100 },
  { key: 'progressSource', title: '进度来源', width: 110 },
  { key: 'calibration', title: '人工校准（D-06.3）', minWidth: 260 },
]

const taskColumns: Column[] = [
  { key: 'taskTitle', title: '任务', minWidth: 220 },
  { key: 'status', title: '状态', width: 100 },
  { key: 'dueDate', title: '截止日期', width: 112 },
  { key: 'doneTime', title: '完成时间', width: 136 },
  { key: 'onTime', title: '时限', width: 92 },
]

function krSources(item: PerfCollectItem) {
  return (item.sources ?? []) as PerfKrSource[]
}

function taskSources(item: PerfCollectItem) {
  return (item.sources ?? []) as PerfTaskSource[]
}

function calibrationText(row: Record<string, any>) {
  const source = row as PerfKrSource
  if (source.calibrationTo === null || source.calibrationTo === undefined) return '未校准'
  return `${percentText(source.calibrationFrom)} → ${percentText(source.calibrationTo)}`
}
</script>

<template>
  <EmptyState
    v-if="!detail"
    :title="loading ? '正在归集' : '请选择一位员工'"
    :desc="loading ? '正在通过目标与任务数据计算归集分值' : '选人后可查看每项可归集指标的来源明细'"
    icon="activity"
  />
  <div v-else>
    <div class="band-head" style="margin-bottom: 12px">
      <div>
        <div class="cell-title" style="font-size: 15px">{{ detail.employeeName }}</div>
        <div class="cell-sub">@{{ detail.employeeUsername }} · 归集预览分仅含可归集指标，主观项以评分人的评分为准</div>
      </div>
      <Metric label="归集预览分" :value="detail.previewTotal" :decimals="2" unit="分" size="m" />
    </div>

    <SpecPlate :rows="plateRows" title="归集口径读数" />

    <div v-if="detail.warnings.length" style="display: flex; flex-direction: column; gap: 8px; margin-top: 14px">
      <div v-for="(warning, index) in detail.warnings" :key="index" class="alert-calm" style="background: var(--blue-soft); color: var(--blue)">
        <span>{{ warning }}</span>
      </div>
    </div>

    <div style="display: flex; flex-direction: column; gap: 14px; margin-top: 16px">
      <Panel v-for="item in detail.items" :key="item.itemId" :title="item.itemName" :meta="`权重 ${item.weight}%`">
        <template #tools>
          <Tag :tone="item.itemCategory === 'subjective' ? 'info' : 'neutral'">{{ categoryLabel(item.itemCategory) }}</Tag>
          <Tag v-if="item.itemCategory === 'collectable' && item.missing && !item.notApplicable" tone="danger" dot>无数据已按 0 计</Tag>
          <Tag v-if="item.notApplicable" tone="danger" dot>不适用</Tag>
          <PermissionButton
            v-if="!readonly && item.itemCategory === 'collectable'"
            perm="perf:score:submit"
            icon-only
            icon="ban"
            label="标记不适用"
            @click="emit('mark-na', item)"
          />
        </template>

        <div v-if="item.itemCategory === 'collectable'" class="band-head" style="margin-bottom: 8px">
          <div class="readout-strip">
            <Metric label="系统归集分值（只读）" :value="item.autoScore" :decimals="2" unit="分" size="s" />
            <Metric v-if="item.notApplicable" label="不适用权重" :value="item.weight" :decimals="2" unit="%" size="s" />
          </div>
          <span class="soft" style="font-size: 12px">
            {{ item.notApplicable ? '已标记不适用，按剩余权重归一化' : '系统归集值不可手工覆盖（R1）' }}
          </span>
        </div>
        <p v-if="item.notApplicable && item.naReason" class="soft" style="font-size: 12.5px; margin-bottom: 10px">
          不适用原因：{{ item.naReason }}
        </p>
        <p v-if="item.itemCategory === 'subjective'" class="soft" style="font-size: 12.5px; margin-bottom: 10px">
          主观指标由评分人录入，无系统取数来源；提交时评语必填。
        </p>

        <template v-if="item.drillDownType === 'kr'">
          <DataTable
            v-if="krSources(item).length"
            :columns="krColumns"
            :data="krSources(item)"
            row-key="krId"
            :loading="loading"
            :min-width="720"
          >
            <template #cell-krTitle="{ row }">
              <div>
                <div class="cell-title">{{ row.krTitle }}</div>
                <div class="cell-sub">{{ row.objectiveTitle }} · {{ row.periodName }}</div>
              </div>
            </template>
            <template #cell-progress="{ row }"><span class="tnum">{{ percentText(row.progress) }}</span></template>
            <template #cell-progressSource="{ row }">
              <Tag :tone="row.progressSource === 'manual' ? 'danger' : 'neutral'">{{ progressSourceLabel(row.progressSource) }}</Tag>
            </template>
            <template #cell-calibration="{ row }">
              <div v-if="row.calibrationTo !== null && row.calibrationTo !== undefined">
                <div class="tnum" style="font-size: 12.5px">{{ calibrationText(row) }}</div>
                <div class="cell-sub">{{ row.calibrationReason || '未填写原因' }}<template v-if="row.calibrationOperator"> · {{ row.calibrationOperator }}</template></div>
              </div>
              <span v-else class="faint">未校准</span>
            </template>
          </DataTable>
          <p v-else class="soft" style="font-size: 12.5px">该员工在当前周期内没有承接中的关键结果。</p>
        </template>

        <template v-else-if="item.drillDownType === 'task'">
          <DataTable
            v-if="taskSources(item).length"
            :columns="taskColumns"
            :data="taskSources(item)"
            row-key="taskId"
            :loading="loading"
            :min-width="760"
          >
            <template #cell-taskTitle="{ row }"><span class="cell-title">{{ row.taskTitle }}</span></template>
            <template #cell-status="{ row }"><span class="muted">{{ taskStatusLabel(row.status) }}</span></template>
            <template #cell-dueDate="{ row }"><span class="tnum soft">{{ row.dueDate || '—' }}</span></template>
            <template #cell-doneTime="{ row }"><span class="tnum soft">{{ row.doneTime || '—' }}</span></template>
            <template #cell-onTime="{ row }">
              <Tag v-if="row.completed" :tone="row.onTime ? 'ok' : 'danger'" dot>{{ row.onTime ? '按时' : '逾期' }}</Tag>
              <Tag v-else tone="neutral">未完成</Tag>
            </template>
          </DataTable>
          <p v-else class="soft" style="font-size: 12.5px">该员工在当前周期内没有顶层任务（子任务不计入完成率与时限达标率）。</p>
        </template>
      </Panel>
    </div>
  </div>
</template>
