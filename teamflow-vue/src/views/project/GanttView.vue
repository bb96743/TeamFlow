<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  EmptyState,
  Field,
  IconButton,
  Metric,
  PageHeader,
  Panel,
  Select,
  Tag,
  TextInput,
} from '@/components/ui'
import { FormDialog, PermissionButton } from '@/components/business'
import GanttTimeline from './GanttTimeline.vue'
import { useProjectPicker } from './useProjectPicker'
import { projectApi, type ProjectGanttData, type ProjectGanttTaskRow } from '@/api/project'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   甘特视图（M14 · 2.5.2）
   ------------------------------------------------------------
   任务标题、计划起止、状态、完成度全部由后端从任务模块实时读取，
   本页只做时间轴定位与展示，**不自行计算完成度**；任务不可见时
   后端返回 missing=true，页面标注「任务不可见」而不是整页失败。
   依赖图与关键路径后端明示未实现，界面如实说明、不假装有。
   ============================================================ */

const picker = useProjectPicker()
const gantt = ref<ProjectGanttData | null>(null)
const loading = ref(false)

const editable = computed(() => {
  const status = gantt.value?.status
  return status === 'draft' || status === 'active'
})
const summary = computed(() => gantt.value?.summary ?? null)
const hasRows = computed(() => Boolean(gantt.value && (gantt.value.tasks.length || gantt.value.milestones.length)))

async function loadGantt() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) {
    gantt.value = null
    return
  }
  loading.value = true
  try {
    gantt.value = await projectApi.gantt.get(projectId)
  } catch (error) {
    toastError(error, '甘特数据加载失败')
    gantt.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await picker.loadProjects()
})
watch(picker.selectedProjectId, () => loadGantt())

/* ── 关联任务（任务在任务模块创建，这里只建立关联） ── */
interface LinkForm {
  taskId: number | null
  linkType: string
  remark: string
}
const linkDialog = ref(false)
const linkSaving = ref(false)
const linkForm = reactive<LinkForm>({ taskId: null, linkType: 'execution', remark: '' })
const linkErrors = ref<Partial<Record<keyof LinkForm, string>>>({})

const LINK_TYPE_OPTIONS = [
  { label: '执行任务', value: 'execution' },
  { label: '计划承接', value: 'plan' },
]

function openLink() {
  linkForm.taskId = null
  linkForm.linkType = 'execution'
  linkForm.remark = ''
  linkErrors.value = {}
  linkDialog.value = true
}

async function submitLink() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  linkErrors.value = validate(linkForm, { taskId: [required('请填写任务 ID')] })
  if (Object.keys(linkErrors.value).length) return
  linkSaving.value = true
  try {
    await projectApi.taskLinks.add(projectId, {
      taskId: Number(linkForm.taskId),
      linkType: linkForm.linkType,
      remark: linkForm.remark.trim() || null,
    })
    pushToast('任务已关联，时间线将展示该任务的计划起止与完成度')
    linkDialog.value = false
    await loadGantt()
  } catch (error) {
    toastError(error, '关联任务失败')
  } finally {
    linkSaving.value = false
  }
}

async function removeLink(row: ProjectGanttTaskRow) {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  const name = row.taskTitle || `任务 #${row.taskId}`
  const ok = await confirm({
    title: '取消任务关联',
    message: `确认取消「${name}」与本项目的关联吗？取消后该任务从时间线移除，任务本身不受影响。`,
    confirmText: '取消关联',
    danger: true,
  })
  if (!ok) return
  try {
    await projectApi.taskLinks.remove(projectId, row.linkId)
    pushToast('已取消任务关联')
    await loadGantt()
  } catch (error) {
    toastError(error, '取消关联失败')
  }
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="甘特视图"
      desc="按项目查看关联任务与里程碑的时间线。任务的标题、计划起止、状态与完成度均由任务模块实时提供，本页只做时间定位与展示，不重复计算完成度；任务不可见时标注说明，不影响整页。"
    >
      <Select
        :model-value="picker.selectedProjectId.value"
        :options="picker.options.value"
        :placeholder="picker.loading.value ? '项目加载中…' : '请选择项目'"
        filterable
        @update:model-value="picker.selectProject($event)"
      />
      <IconButton name="refresh" label="刷新时间线" bordered :disabled="loading" @click="loadGantt" />
      <PermissionButton
        v-if="editable"
        perm="project:tasklink:add"
        variant="primary"
        icon="plus"
        @click="openLink"
      >关联任务</PermissionButton>
    </PageHeader>

    <template v-if="!picker.selectedProjectId.value && !picker.loading.value">
      <EmptyState
        title="请先选择项目"
        desc="甘特视图按项目展示关联任务与里程碑；如果还没有项目，可以到「项目列表」先完成立项。"
        icon="grid"
      />
    </template>

    <template v-else-if="gantt">
      <section class="band reveal">
        <div class="band-head">
          <span class="section-label">
            {{ gantt.projectName }} · {{ gantt.statusLabel || gantt.status }}
          </span>
          <div class="band-tools">
            <Tag v-if="!editable" tone="neutral">项目已结项/已归档，任务关联只读</Tag>
            <Tag v-if="gantt.criticalPathSupported !== true" tone="info">依赖图/关键路径待后续版本</Tag>
          </div>
        </div>
        <p class="cell-sub">
          负责人：{{ gantt.ownerName || '未指定' }} · 计划周期：{{ gantt.startDate || '—' }} 至 {{ gantt.endDate || '—' }}
          <span class="soft"> · {{ gantt.taskSource || '任务数据由任务模块实时读取' }}</span>
        </p>
      </section>

      <section class="band reveal">
        <div class="metric-row">
          <Metric :value="summary?.linkedTaskCount ?? 0" label="关联任务" size="l" />
          <Metric :value="summary?.completedTaskCount ?? 0" label="已完成" size="l" />
          <Metric :value="summary?.inProgressTaskCount ?? 0" label="进行中" size="l" />
          <Metric :value="summary?.delayedTaskCount ?? 0" label="逾期" size="l" />
          <Metric :value="summary?.taskCompletionPercent ?? null" unit="%" :decimals="2" label="任务完成度（任务模块实时汇总）" size="l" />
          <Metric :value="summary?.projectProgress ?? null" unit="%" :decimals="2" label="项目完成度（里程碑汇总）" size="l" />
        </div>
        <p class="faint" style="margin-top: 10px">
          依赖关系图与关键路径将在后续版本提供；当前按关联任务与里程碑的计划起止展示时间线，不做关键路径推断。
          {{ gantt.criticalPathNote || '' }}
        </p>
      </section>

      <Panel
        title="时间线"
        :meta="`关联任务 ${summary?.linkedTaskCount ?? 0} 个 · 里程碑 ${summary?.milestoneCount ?? 0} 个`"
      >
        <template #tools>
          <Tag v-if="(summary?.visibleTaskCount ?? 0) < (summary?.linkedTaskCount ?? 0)" tone="danger" dot>
            有 {{ (summary?.linkedTaskCount ?? 0) - (summary?.visibleTaskCount ?? 0) }} 个任务不可见
          </Tag>
        </template>
        <GanttTimeline
          v-if="hasRows"
          :tasks="gantt.tasks"
          :milestones="gantt.milestones"
          :project-start="gantt.startDate"
          :project-end="gantt.endDate"
          :editable="editable"
          @remove="removeLink"
        />
        <EmptyState
          v-else
          title="还没有关联任务或里程碑"
          desc="关联任务后，这里会按任务计划起止展示时间条；里程碑也会一并展示。任务完成度始终由任务模块实时汇总。"
          icon="clock"
        />
        <p class="faint" style="margin: 14px 0 0">
          时间条内的百分比是任务模块返回的完成度原值，本页未做二次计算；任务不可见（已删除或当前账号无任务查看权限）时仍保留关联行，可在此取消关联。
        </p>
      </Panel>
    </template>

    <!-- 关联任务 -->
    <FormDialog
      :open="linkDialog"
      title="关联任务"
      sub="任务需先在任务模块创建；这里只建立项目与任务的关联，不复制任务字段。任务不存在或当前账号无权查看时会被拒绝。"
      :saving="linkSaving"
      @close="linkDialog = false"
      @submit="submitLink"
    >
      <Field label="任务 ID" required :error="linkErrors.taskId" hint="任务模块中的任务编号">
        <TextInput v-model="linkForm.taskId" type="number" :invalid="!!linkErrors.taskId" placeholder="如：1024" />
      </Field>
      <Field label="关联类型">
        <Select v-model="linkForm.linkType" :options="LINK_TYPE_OPTIONS" />
      </Field>
      <Field label="关联说明" span>
        <TextInput v-model="linkForm.remark" placeholder="如：该任务承接本项目一期交付" :maxlength="200" />
      </Field>
    </FormDialog>
  </div>
</template>

<style scoped>
.metric-row {
  display: flex;
  align-items: flex-start;
  gap: 32px;
  flex-wrap: wrap;
}
.metric-row > * { min-width: 120px; }
</style>