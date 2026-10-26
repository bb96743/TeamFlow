<script setup lang="ts">
import { computed } from 'vue'
import { Tag } from '@/components/ui'
import { PermissionButton } from '@/components/business'
import type { ProjectGanttTaskRow, ProjectMilestoneRow } from '@/api/project'

/* ============================================================
   简化时间线（甘特视图）
   ------------------------------------------------------------
   只做时间轴定位：任务的标题、计划起止、状态、完成度全部来自后端
   （任务模块实时读取），这里**不计算完成度**，只用后端给的百分比
   填充时间条；依赖关系与关键路径未实现，不在前端推断。
   任务不可见（缺失/无权限）时保留行并标注，不让整页失败。
   ============================================================ */
const props = defineProps<{
  tasks: ProjectGanttTaskRow[]
  milestones: ProjectMilestoneRow[]
  projectStart?: string | null
  projectEnd?: string | null
  editable: boolean
}>()
const emit = defineEmits<{ (e: 'remove', row: ProjectGanttTaskRow): void }>()

const DAY = 86_400_000

function dayNumber(text?: string | null): number | null {
  if (!text) return null
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(text)
  if (!matched) return null
  return Date.UTC(Number(matched[1]), Number(matched[2]) - 1, Number(matched[3])) / DAY
}

const range = computed<{ from: number; to: number; totalDays: number } | null>(() => {
  const starts: number[] = []
  const ends: number[] = []
  props.tasks.forEach(row => {
    const start = dayNumber(row.startDate)
    const end = dayNumber(row.dueDate)
    if (start !== null) starts.push(start)
    if (end !== null) ends.push(end)
  })
  props.milestones.forEach(row => {
    const start = dayNumber(row.planStartDate)
    const end = dayNumber(row.planEndDate)
    if (start !== null) starts.push(start)
    if (end !== null) ends.push(end)
  })
  const projectStart = dayNumber(props.projectStart)
  const projectEnd = dayNumber(props.projectEnd)
  if (projectStart !== null) starts.push(projectStart)
  if (projectEnd !== null) ends.push(projectEnd)
  if (!starts.length || !ends.length) return null
  const from = Math.min(...starts)
  let to = Math.max(...ends)
  if (to <= from) to = from + 1
  return { from, to, totalDays: to - from + 1 }
})

function barStyle(startText?: string | null, endText?: string | null) {
  if (!range.value) return null
  let start = dayNumber(startText)
  let end = dayNumber(endText)
  if (start === null && end === null) return null
  if (start === null) start = end
  if (end === null) end = start
  if (start === null || end === null) return null
  if (end < start) end = start
  const { from, totalDays } = range.value
  const left = Math.max(0, Math.min(100, ((start - from) / totalDays) * 100))
  const width = Math.max(1.4, Math.min(100 - left, ((end - start + 1) / totalDays) * 100))
  return { left: `${left}%`, width: `${width}%` }
}

const monthTicks = computed(() => {
  if (!range.value) return [] as Array<{ key: string; label: string; left: string }>
  const { from, to, totalDays } = range.value
  const ticks: Array<{ key: string; label: string; left: string }> = []
  const cursor = new Date(from * DAY)
  let year = cursor.getUTCFullYear()
  let month = cursor.getUTCMonth()
  for (let index = 0; index < 36; index++) {
    const monthStart = Date.UTC(year, month, 1) / DAY
    if (monthStart > to) break
    const left = Math.max(0, ((monthStart - from) / totalDays) * 100)
    ticks.push({
      key: `${year}-${month}`,
      label: index === 0 || month === 0 ? `${year}年${month + 1}月` : `${month + 1}月`,
      left: `${left}%`,
    })
    if (month === 11) {
      year += 1
      month = 0
    } else {
      month += 1
    }
  }
  return ticks
})

const taskRows = computed(() => props.tasks)
const milestoneRows = computed(() => props.milestones)

function milestoneStatusTone(status: string) {
  return status === 'completed' ? 'ok' : status === 'cancelled' ? 'neutral' : 'info'
}
</script>

<template>
  <div v-if="!range" class="timeline-empty faint">
    关联任务与里程碑都还没有计划起止日期，补齐计划日期后这里会显示时间条。
  </div>
  <div v-else class="timeline">
    <div class="timeline-head">
      <div class="timeline-label">任务 / 里程碑</div>
      <div class="timeline-track timeline-scale">
        <span v-for="tick in monthTicks" :key="tick.key" class="timeline-tick" :style="{ left: tick.left }">
          {{ tick.label }}
        </span>
      </div>
      <div class="timeline-action" aria-hidden="true" />
    </div>

    <div
      v-for="row in taskRows"
      :key="`task-${row.linkId}`"
      class="timeline-row"
      :class="{ 'is-missing': row.missing }"
    >
      <div class="timeline-label">
        <div class="cell-title">
          {{ row.missing ? '任务不可见' : (row.taskTitle || `任务 #${row.taskId}`) }}
        </div>
        <div class="cell-sub">
          <template v-if="row.missing">
            {{ row.missingReason || '任务已删除或当前账号无权查看；关联仍保留，可取消关联。' }}
          </template>
          <template v-else>
            <Tag :tone="row.status === 'done' ? 'ok' : row.delayed ? 'danger' : 'info'" dot>
              {{ row.statusLabel || row.status || '状态未知' }}
            </Tag>
            <span class="soft"> · {{ row.linkTypeLabel || '执行任务' }}</span>
            <span v-if="row.assigneeName" class="soft"> · {{ row.assigneeName }}</span>
            <span v-if="row.delayed" class="soft"> · 已逾期</span>
          </template>
        </div>
      </div>
      <div class="timeline-track">
        <div
          v-if="!row.missing && barStyle(row.startDate, row.dueDate)"
          class="timeline-bar"
          :class="{ 'is-delayed': row.delayed }"
          :style="barStyle(row.startDate, row.dueDate) ?? undefined"
        >
          <span
            class="timeline-bar-fill"
            :style="{ width: `${row.completionPercent == null ? 0 : row.completionPercent}%` }"
          />
          <span class="timeline-bar-text">
            {{ row.completionPercent != null ? `${row.completionPercent}%` : '完成度暂缺' }}
          </span>
        </div>
        <span v-else-if="!row.missing" class="faint timeline-hint">未设置计划起止</span>
        <span v-else class="faint timeline-hint">任务不可见，无法读取计划时间</span>
      </div>
      <div class="timeline-action">
        <PermissionButton
          v-if="editable"
          perm="project:tasklink:remove"
          icon-only
          icon="ban"
          label="取消关联任务"
          danger
          @click="emit('remove', row)"
        />
      </div>
    </div>

    <div v-for="row in milestoneRows" :key="`milestone-${row.id}`" class="timeline-row is-milestone">
      <div class="timeline-label">
        <div class="cell-title">{{ row.milestoneName }}</div>
        <div class="cell-sub">
          <Tag :tone="milestoneStatusTone(row.status)" dot>{{ row.statusLabel || row.status }}</Tag>
          <span class="soft"> · 里程碑</span>
          <span v-if="row.delayed" class="soft"> · 已延期</span>
        </div>
      </div>
      <div class="timeline-track">
        <div
          v-if="barStyle(row.planStartDate, row.planEndDate)"
          class="timeline-bar is-milestone"
          :style="barStyle(row.planStartDate, row.planEndDate) ?? undefined"
        >
          <span
            class="timeline-bar-fill"
            :style="{ width: `${row.completion == null ? 0 : row.completion}%` }"
          />
          <span class="timeline-bar-text">
            {{ row.completion != null ? `${row.completion}%` : (row.completionNote || '完成度暂缺') }}
          </span>
        </div>
        <span v-else class="faint timeline-hint">未设置计划起止</span>
      </div>
      <div class="timeline-action" />
    </div>
  </div>
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; gap: 10px; overflow-x: auto; }
.timeline-empty { padding: 20px 0; }
.timeline-head,
.timeline-row {
  display: grid;
  grid-template-columns: minmax(200px, 260px) minmax(320px, 1fr) 40px;
  gap: 14px;
  align-items: center;
  min-width: 640px;
}
.timeline-head { padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.timeline-row { padding: 8px 0; border-bottom: 1px dashed var(--line); }
.timeline-row:last-child { border-bottom: none; }
.timeline-row.is-missing { opacity: .78; }
.timeline-label { min-width: 0; }
.timeline-track { position: relative; min-height: 30px; }
.timeline-scale { min-height: 18px; }
.timeline-tick {
  position: absolute;
  top: 0;
  transform: translateX(-1px);
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  border-left: 1px solid var(--line);
  padding-left: 4px;
  height: 18px;
}
.timeline-bar {
  position: absolute;
  top: 4px;
  height: 22px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--blue-soft);
  box-shadow: inset 0 0 0 1px var(--line);
}
.timeline-bar.is-delayed { box-shadow: inset 0 0 0 1px var(--red-text); }
.timeline-bar.is-milestone { background: var(--cat-5-bg, var(--blue-soft)); }
.timeline-bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--blue);
  opacity: .38;
  border-radius: 999px;
}
.timeline-bar.is-milestone .timeline-bar-fill { background: var(--lime); }
.timeline-bar-text {
  position: relative;
  display: inline-flex;
  align-items: center;
  height: 100%;
  padding: 0 8px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  white-space: nowrap;
}
.timeline-hint { font-size: 12px; }
.timeline-action { display: flex; justify-content: flex-end; }
</style>
