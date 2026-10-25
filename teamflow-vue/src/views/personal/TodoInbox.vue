<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Field, IconButton, Select, Tag, TextInput, type Column } from '@/components/ui'
import { ListPage, PermissionButton } from '@/components/business'
import { ApiError } from '@/api'
import { personalApi, type TodoBizTypeInfo, type TodoRow } from '@/api/personal'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'

/* ============================================================
   待办收件箱（M10 · 2.3.1）
   ------------------------------------------------------------
   汇总其他模块投递的「需要我行动」事项。点「完成」时后端会先回写原模块，
   只有原模块接受才关闭待办；原模块拒绝时待办保持未处理，这里把后端的
   原因原文展示出来，不能改成通用提示。
   ============================================================ */

const router = useRouter()

const BIZ_LABEL: Record<string, string> = {
  task_assign: '任务指派',
  task_approval: '审批待办',
  meeting_invite: '会议邀请',
}
const BIZ_SOURCE: Record<string, string> = {
  task_assign: '任务与工作流',
  task_approval: '任务与工作流',
  meeting_invite: '日程与会议',
}
const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '未处理', value: 'open' },
  { label: '已完成', value: 'handled' },
  { label: '已忽略', value: 'ignored' },
]
const PRIORITY_OPTIONS = [
  { label: '全部优先级', value: '' },
  { label: '紧急', value: 'urgent' },
  { label: '高', value: 'high' },
  { label: '普通', value: 'normal' },
  { label: '低', value: 'low' },
]
const PRIORITY_TONE: Record<string, string> = { urgent: 'danger', high: 'info', normal: 'neutral', low: 'neutral' }

const list = useListPage<TodoRow, { bizType: string; status: string; priority: string; dueBefore: string }>({
  query: { bizType: '', status: '', priority: '', dueBefore: '' },
  fetch: params => personalApi.todos.list(params),
})

const bizInfo = ref<TodoBizTypeInfo | null>(null)
const lastError = ref('')
const errorTodoId = ref<number | null>(null)
const handlingId = ref<number | null>(null)

onMounted(async () => {
  try {
    bizInfo.value = await personalApi.todos.bizTypes()
  } catch {
    bizInfo.value = null
  }
})

const bizOptions = computed(() => [
  { label: '全部业务类型', value: '' },
  ...(bizInfo.value?.knownBizTypes ?? []).map(item => ({
    label: BIZ_LABEL[item.bizType] ?? item.bizType,
    value: item.bizType,
  })),
])

const columns: Column[] = [
  { key: 'title', title: '待办事项', minWidth: 260 },
  { key: 'bizType', title: '业务类型', width: 168 },
  { key: 'priority', title: '优先级', width: 92 },
  { key: 'dueTime', title: '截止时间', width: 168 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'createTime', title: '投递时间', width: 148 },
  { key: 'actions', title: '操作', width: 132, className: 'col-actions' },
]

function bizLabel(bizType: string) {
  return BIZ_LABEL[bizType] ?? bizType
}

function bizSource(bizType: string) {
  return BIZ_SOURCE[bizType] ?? '其他模块'
}

function statusTone(row: TodoRow) {
  if (row.status === 'handled') return 'ok'
  if (row.status === 'ignored') return 'neutral'
  return 'info'
}

function openSource(row: TodoRow) {
  if (!row.actionUrl) {
    pushToast('该事项没有提供回源入口，请在原模块中处理', 'info')
    return
  }
  router.push(row.actionUrl)
}

async function handle(row: TodoRow, action: 'complete' | 'ignore') {
  lastError.value = ''
  errorTodoId.value = null
  handlingId.value = row.id
  try {
    await personalApi.todos.handle(row.id, action)
    pushToast(action === 'complete' ? '已完成，并在原模块同步了业务状态' : '已忽略该待办')
    await list.load()
  } catch (error) {
    // R3 强约束：原模块拒绝时，待办保持未处理；后端返回的原因原样展示（不吞、不改写）
    lastError.value = error instanceof ApiError ? error.message : '待办处理失败，请稍后重试'
    errorTodoId.value = row.id
    toastError(error, '待办处理失败')
  } finally {
    handlingId.value = null
  }
}
</script>

<template>
  <ListPage
    title="待办收件箱"
    desc="汇总任务指派、审批等需要你处理的事项。在收件箱点“完成”时，系统会先回写原模块，原模块接受后才关闭待办；若原模块拒绝，待办保持未处理并给出原因。"
    table-title="待办列表"
    :list="list"
    :columns="columns"
  >
    <template v-if="lastError" #before>
      <div class="band reveal" role="alert">
        <div class="band-head">
          <span class="section-label">处理未完成</span>
          <Tag tone="danger" dot>待办仍为未处理</Tag>
        </div>
        <p class="cell-title">{{ lastError }}</p>
        <p class="cell-sub">
          以上为原模块返回的原文。该待办没有被关闭，原模块的业务状态也未被改动；请按提示到原模块处理后，再回到收件箱完成。
        </p>
        <p v-if="errorTodoId" class="faint">可点击该行的「回到来源」跳到原模块继续处理。</p>
      </div>
    </template>

    <template #search>
      <Field label="业务类型">
        <Select v-model="list.query.bizType" :options="bizOptions" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="STATUS_OPTIONS" />
      </Field>
      <Field label="优先级">
        <Select v-model="list.query.priority" :options="PRIORITY_OPTIONS" />
      </Field>
      <Field label="截止时间早于" hint="格式 yyyy-MM-dd，留空不筛选">
        <TextInput v-model="list.query.dueBefore" placeholder="如：2026-11-30" clearable @enter="list.search()" />
      </Field>
    </template>

    <template #cell-title="{ row }">
      <div>
        <div class="cell-title">{{ row.title }}</div>
        <div class="cell-sub">
          来自{{ bizSource(row.bizType) }} ·
          {{ row.handlerRegistered ? '可在收件箱直接处理' : '需回到原模块处理' }}
        </div>
      </div>
    </template>
    <template #cell-bizType="{ row }">
      <div>
        <Tag :tone="row.handlerRegistered ? 'info' : 'neutral'">{{ bizLabel(row.bizType) }}</Tag>
        <div v-if="!row.handlerRegistered" class="cell-sub">该类型暂不支持在收件箱直接处理</div>
      </div>
    </template>
    <template #cell-priority="{ row }">
      <Tag :tone="PRIORITY_TONE[row.priority] ?? 'neutral'">{{ row.priorityLabel || row.priority }}</Tag>
    </template>
    <template #cell-dueTime="{ row }">
      <span class="tnum soft">{{ row.dueTime || '—' }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="statusTone(row)" dot>{{ row.statusLabel || row.status }}</Tag>
    </template>
    <template #cell-createTime="{ row }">
      <span class="tnum soft">{{ row.createTime || '—' }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <template v-if="row.status === 'open'">
          <PermissionButton
            perm="personal:todo:handle"
            icon-only
            icon="check"
            label="完成待办"
            :loading="handlingId === row.id"
            @click="handle(row, 'complete')"
          />
          <PermissionButton
            perm="personal:todo:handle"
            icon-only
            icon="ban"
            label="忽略待办"
            danger
            @click="handle(row, 'ignore')"
          />
        </template>
        <IconButton name="arrowRight" label="回到来源处理" small @click="openSource(row)" />
      </div>
    </template>
  </ListPage>
</template>
