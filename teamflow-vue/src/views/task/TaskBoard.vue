<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Button, PageHeader, SectionLabel, Segmented, Tag } from '@/components/ui'
import BoardView, { type BoardColumn } from '@/components/business/BoardView.vue'
import { taskApi, type TaskRow, type TaskStatusMachine } from '@/api/task'
import { hasPermission } from '@/directives/permission'
import { pushToast, toastError } from '@/composables/toast'
import { PRIORITY_TEXT, PRIORITY_TONE, allowedTargetsOf, dateText, statusLabelOf } from './support'

/* ============================================================
   任务看板：按状态分列查看任务分布，可直接拖卡片改状态。
   · 列的来源 = 后端状态机（/task/flow/status-machine），不是前端写死的列表；
   · 拖到非法列会被拒绝并提示（合法性仍由后端判定，前端只是提前拦一道）；
   · 没有「改状态」权限时看板只读，不给拖拽与「移动到」菜单。
   ============================================================ */

const machine = ref<TaskStatusMachine | null>(null)
const tasks = ref<TaskRow[]>([])
const loading = ref(false)
const scope = ref<'all' | 'my'>('all')
const SCOPE_OPTIONS = [{ label: '全部任务', value: 'all' as const }, { label: '我的任务', value: 'my' as const }]

const canMove = computed(() => hasPermission('task:task:status'))

async function loadMachine() {
  try {
    machine.value = await taskApi.flow.statusMachine()
  } catch (error) {
    toastError(error, '任务状态机加载失败')
  }
}

async function loadTasks() {
  loading.value = true
  try {
    const page = scope.value === 'my'
      ? await taskApi.tasks.my({ pageNum: 1, pageSize: 200 })
      : await taskApi.tasks.list({ pageNum: 1, pageSize: 200 })
    tasks.value = page.rows
  } catch (error) {
    toastError(error, '任务加载失败')
    tasks.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadMachine()
  await loadTasks()
})
watch(scope, loadTasks)

const columns = computed<BoardColumn<TaskRow>[]>(() => {
  if (!machine.value) return []
  return machine.value.states.map(state => ({
    key: state.code,
    title: state.label,
    items: tasks.value.filter(task => task.status === state.code),
  }))
})

/** 拖拽落位：只接受状态机允许的目标状态，其余直接拒绝并提示 */
async function onMove(payload: { item: TaskRow; from: BoardColumn<TaskRow>; to: BoardColumn<TaskRow> }): Promise<boolean> {
  const target = String(payload.to.key)
  const allowed = allowedTargetsOf(machine.value, payload.item.status)
  if (!allowed.includes(target)) {
    pushToast(`「${statusLabelOf(machine.value, payload.item.status)}」不能直接变更为「${statusLabelOf(machine.value, target)}」`, 'danger')
    return false
  }
  try {
    await taskApi.tasks.transition(payload.item.id, { toStatus: target, opinion: '看板拖拽流转' })
    pushToast(`任务已流转为「${statusLabelOf(machine.value, target)}」`)
    await loadTasks()
    return true
  } catch (error) {
    toastError(error, '状态流转失败')
    return false
  }
}

const total = computed(() => tasks.value.length)
const openCount = computed(() => tasks.value.filter(task => task.status !== 'closed' && task.status !== 'done').length)
</script>

<template>
  <div class="page">
    <PageHeader
      title="任务看板"
      desc="按状态分列查看任务分布；把卡片拖到其它列即可改状态，列的划分与可流转范围都来自后端状态机。"
    >
      <Segmented v-model="scope" label="任务范围" :options="SCOPE_OPTIONS" />
      <Button variant="secondary" icon="refresh" :loading="loading" @click="loadTasks">刷新</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="看板说明" />
        <span class="eyebrow">共 {{ total }} 个任务 · 未结束 {{ openCount }} 个</span>
      </div>
      <p class="faint" style="font-size: 12px; line-height: 1.8">
        状态列与允许的流转由后端统一维护，前端不另存一份规则；{{ canMove ? '拖动卡片或使用卡片右上角的「移动到…」即可改状态。' : '当前账号没有状态流转权限，看板为只读。' }}
      </p>
    </section>

    <div v-if="machine" class="reveal">
      <BoardView
        :columns="columns"
        item-key="id"
        :draggable="canMove"
        :on-move="canMove ? onMove : undefined"
        empty-text="该状态暂无任务"
      >
        <template #card="{ item }">
          <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 8px">
            <span class="cell-title">{{ item.taskTitle }}</span>
            <Tag :tone="PRIORITY_TONE[item.priority || 'normal'] || 'neutral'">{{ PRIORITY_TEXT[item.priority || 'normal'] || item.priority }}</Tag>
          </div>
          <div class="cell-sub" style="margin-top: 6px">
            {{ item.assigneeName || '待认领' }} · 截止 {{ dateText(item.dueDate) }}
          </div>
          <div style="margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap">
            <Tag v-if="item.krId" tone="info">KR #{{ item.krId }}</Tag>
            <Tag v-if="(item.subTaskCount ?? 0) > 0" tone="neutral">子任务 {{ item.doneSubTaskCount ?? 0 }}/{{ item.subTaskCount }}</Tag>
          </div>
        </template>
      </BoardView>
    </div>

    <div v-else class="band reveal">
      <p class="faint" style="font-size: 13px">状态机尚未加载完成，请刷新页面重试。</p>
    </div>
  </div>
</template>
