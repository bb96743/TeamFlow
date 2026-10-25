<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { DataTable, Drawer, Field, SectionLabel, Select, Segmented, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  taskApi,
  type TaskRow,
  type TaskStatusMachine,
  type TaskTraceRow,
} from '@/api/task'
import { okrApi, type OkrKeyResultRow, type OkrObjectiveRow } from '@/api/okr'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { pattern, required, validate } from '@/utils/validate'
import {
  PRIORITY_OPTIONS,
  PRIORITY_TEXT,
  PRIORITY_TONE,
  allowedTargetsOf,
  dateText,
  dateTimeText,
  statusLabelOf,
} from './support'

/* ============================================================
   任务列表：M1 主链路的落地点。
   ── 状态流转的合法目标全部来自后端状态机接口（/task/flow/status-machine），
      前端不硬编码任何迁移表，后端调整状态机后页面自动跟随。
   ── 任务可关联 M02 的关键结果（kr_id）：这是「任务完成 → KR 进度自动回写」的连接点。
   ============================================================ */

const scope = ref<'all' | 'my' | 'pool'>('all')
const SCOPE_OPTIONS = [
  { label: '全部任务', value: 'all' as const },
  { label: '我的任务', value: 'my' as const },
  { label: '待认领池', value: 'pool' as const },
]

const list = useListPage<TaskRow, { keyword: string; status: string; priority: string; assigneeId: number | null }>({
  query: { keyword: '', status: '', priority: '', assigneeId: null },
  toParams: query => ({
    keyword: query.keyword,
    status: query.status,
    priority: query.priority,
    assigneeId: query.assigneeId ?? undefined,
  }),
  fetch: params => {
    if (scope.value === 'my') return taskApi.tasks.my({ status: params.status })
    if (scope.value === 'pool') return taskApi.tasks.pool({ keyword: params.keyword })
    return taskApi.tasks.list(params)
  },
})
watch(scope, () => list.search())

/* ── 状态机（后端唯一来源，只读） ── */
const machine = ref<TaskStatusMachine | null>(null)
async function loadMachine() {
  try {
    machine.value = await taskApi.flow.statusMachine()
  } catch (error) {
    toastError(error, '任务状态机加载失败，状态流转暂不可用')
  }
}
onMounted(loadMachine)

const statusOptions = computed(() => (machine.value?.states ?? []).map(state => ({ label: state.label, value: state.code })))

const columns: Column[] = [
  { key: 'taskTitle', title: '任务', minWidth: 240 },
  { key: 'status', title: '状态', width: 92 },
  { key: 'priority', title: '优先级', width: 84 },
  { key: 'assigneeName', title: '负责人', width: 100 },
  { key: 'kr', title: '关联关键结果', width: 130 },
  { key: 'subTasks', title: '子任务', width: 90, align: 'right' },
  { key: 'dueDate', title: '截止日期', width: 110 },
  { key: 'actions', title: '操作', width: 210, className: 'col-actions' },
]

function statusText(row: TaskRow) {
  return row.statusLabel || statusLabelOf(machine.value, row.status)
}

function pickKeywordOwner(value: string | number | null) {
  list.query.assigneeId = value === null || value === '' ? null : Number(value)
}

/* ── 表单（新增 / 编辑） ── */
interface TaskForm {
  id?: number
  taskTitle: string
  taskDesc: string
  priority: string
  startDate: string
  dueDate: string
  sort: number | null
  remark: string
}
const emptyForm = (): TaskForm => ({ taskTitle: '', taskDesc: '', priority: 'normal', startDate: '', dueDate: '', sort: 10, remark: '' })
const DATE_RULE = /^\d{4}-\d{2}-\d{2}$/

const dialog = ref(false)
const saving = ref(false)
const form = reactive<TaskForm>(emptyForm())
const errors = ref<Partial<Record<keyof TaskForm, string>>>({})
const formOwner = ref<number | null>(null)
const formKrId = ref<number | null>(null)

/* 关联关键结果：先选目标、再选关键结果（都是 M02 的公开接口） */
const okrObjectives = ref<OkrObjectiveRow[]>([])
const okrKrs = ref<OkrKeyResultRow[]>([])
const formObjectiveId = ref<number | null>(null)
const okrReady = ref(true)

async function loadOkrObjectives() {
  try {
    const page = await okrApi.objectives.list({ pageNum: 1, pageSize: 200 })
    okrObjectives.value = page.rows
    okrReady.value = true
  } catch {
    okrObjectives.value = []
    okrReady.value = false
  }
}

async function loadOkrKrs(objectiveId: number | null) {
  okrKrs.value = []
  if (!objectiveId) return
  try {
    okrKrs.value = await okrApi.krs.list(objectiveId)
  } catch {
    okrKrs.value = []
    okrReady.value = false
  }
}

const objectiveOptions = computed(() => okrObjectives.value.map(o => ({ label: `${o.objectiveTitle}（${o.periodName || '—'}）`, value: o.id })))
const krOptions = computed(() => okrKrs.value.map(kr => ({ label: `${kr.krTitle}（进度 ${kr.progress ?? 0}%）`, value: kr.id })))

function pickFormObjective(value: string | number | null | undefined) {
  formObjectiveId.value = value === null || value === undefined || value === '' ? null : Number(value)
  formKrId.value = null
  loadOkrKrs(formObjectiveId.value)
}
function pickFormKr(value: string | number | null | undefined) {
  formKrId.value = value === null || value === undefined || value === '' ? null : Number(value)
}
function pickFormOwner(value: string | number | null) {
  formOwner.value = value === null || value === '' ? null : Number(value)
}
function pickPriority(value: string | number | null | undefined) {
  form.priority = String(value ?? 'normal')
}

async function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  formOwner.value = null
  formKrId.value = null
  formObjectiveId.value = null
  errors.value = {}
  dialog.value = true
  if (!okrObjectives.value.length) await loadOkrObjectives()
}

function openEdit(row: TaskRow) {
  Object.assign(form, {
    id: row.id,
    taskTitle: row.taskTitle,
    taskDesc: row.taskDesc ?? '',
    priority: row.priority || 'normal',
    startDate: row.startDate ? dateText(row.startDate) : '',
    dueDate: row.dueDate ? dateText(row.dueDate) : '',
    sort: row.sort ?? 0,
    remark: row.remark ?? '',
  })
  formOwner.value = row.assigneeId ?? null
  formKrId.value = row.krId ?? null
  formObjectiveId.value = null
  errors.value = {}
  dialog.value = true
  if (!okrObjectives.value.length) loadOkrObjectives()
}

async function submit() {
  errors.value = validate(form, {
    taskTitle: [required('请输入任务标题')],
    startDate: [pattern(DATE_RULE, '日期格式为 2026-01-01')],
    dueDate: [pattern(DATE_RULE, '日期格式为 2026-01-01')],
  })
  if (!errors.value.startDate && !errors.value.dueDate && form.startDate && form.dueDate && form.dueDate < form.startDate) {
    errors.value.dueDate = '截止日期不能早于开始日期'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const base = {
    taskTitle: form.taskTitle.trim(),
    taskDesc: form.taskDesc,
    priority: form.priority,
    startDate: form.startDate || null,
    dueDate: form.dueDate || null,
    sort: form.sort,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await taskApi.tasks.update(form.id, { ...base, krId: formKrId.value })
      pushToast('任务已更新')
    } else {
      await taskApi.tasks.create({ ...base, krId: formKrId.value, assigneeId: formOwner.value })
      pushToast('任务已创建')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '任务保存失败')
  } finally {
    saving.value = false
  }
}

/* ── 指派 / 转派 / 认领 ── */
const assignOpen = ref(false)
const assignSaving = ref(false)
const assignTarget = ref<TaskRow | null>(null)
const assignForm = reactive<{ assigneeId: number | null; remark: string }>({ assigneeId: null, remark: '' })

function openAssign(row: TaskRow) {
  assignTarget.value = row
  assignForm.assigneeId = row.assigneeId ?? null
  assignForm.remark = ''
  assignOpen.value = true
}
function pickAssignee(value: string | number | null) {
  assignForm.assigneeId = value === null || value === '' ? null : Number(value)
}

async function submitAssign(clear = false) {
  if (!assignTarget.value) return
  assignSaving.value = true
  try {
    await taskApi.tasks.assign(assignTarget.value.id, {
      assigneeId: clear ? 0 : assignForm.assigneeId,
      remark: assignForm.remark,
    })
    pushToast(clear ? '已收回指派，任务回到待认领池' : '任务已指派')
    assignOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '指派失败')
  } finally {
    assignSaving.value = false
  }
}

async function claim(row: TaskRow) {
  const ok = await confirm({ title: '认领任务', message: `确认认领「${row.taskTitle}」吗？认领后任务进入「待办」。`, confirmText: '认领' })
  if (!ok) return
  try {
    await taskApi.tasks.claim(row.id)
    pushToast('任务已认领')
    await list.load()
  } catch (error) {
    toastError(error, '认领失败')
  }
}

/* ── 状态流转（目标状态来自后端状态机） ── */
const transitionOpen = ref(false)
const transitionSaving = ref(false)
const transitionTarget = ref<TaskRow | null>(null)
const transitionForm = reactive<{ toStatus: string; opinion: string }>({ toStatus: '', opinion: '' })
const transitionOptions = computed(() =>
  allowedTargetsOf(machine.value, transitionTarget.value?.status).map(code => ({ label: statusLabelOf(machine.value, code), value: code })),
)

function openTransition(row: TaskRow) {
  transitionTarget.value = row
  transitionForm.toStatus = allowedTargetsOf(machine.value, row.status)[0] ?? ''
  transitionForm.opinion = ''
  transitionOpen.value = true
}

async function submitTransition() {
  if (!transitionTarget.value) return
  if (!transitionForm.toStatus) {
    pushToast('请选择要流转到的状态', 'danger')
    return
  }
  transitionSaving.value = true
  try {
    await taskApi.tasks.transition(transitionTarget.value.id, {
      toStatus: transitionForm.toStatus,
      opinion: transitionForm.opinion,
    })
    pushToast(`任务已流转为「${statusLabelOf(machine.value, transitionForm.toStatus)}」`)
    transitionOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '状态流转失败')
  } finally {
    transitionSaving.value = false
  }
}

/* ── 发起审批（线性单节点） ── */
const approvalOpen = ref(false)
const approvalSaving = ref(false)
const approvalTarget = ref<TaskRow | null>(null)
const approvalForm = reactive<{ targetStatus: string; approverId: number | null; opinion: string }>({ targetStatus: '', approverId: null, opinion: '' })
const approvalOptions = computed(() =>
  allowedTargetsOf(machine.value, approvalTarget.value?.status).map(code => ({ label: statusLabelOf(machine.value, code), value: code })),
)

function openApproval(row: TaskRow) {
  approvalTarget.value = row
  approvalForm.targetStatus = allowedTargetsOf(machine.value, row.status)[0] ?? ''
  approvalForm.approverId = null
  approvalForm.opinion = ''
  approvalOpen.value = true
}
function pickApprover(value: string | number | null) {
  approvalForm.approverId = value === null || value === '' ? null : Number(value)
}
function pickApprovalStatus(value: string | number | null | undefined) {
  approvalForm.targetStatus = String(value ?? '')
}

async function submitApproval() {
  if (!approvalTarget.value) return
  if (!approvalForm.targetStatus) {
    pushToast('请选择审批通过后的目标状态', 'danger')
    return
  }
  approvalSaving.value = true
  try {
    await taskApi.tasks.startApproval(approvalTarget.value.id, {
      targetStatus: approvalForm.targetStatus,
      approverId: approvalForm.approverId,
      opinion: approvalForm.opinion,
    })
    pushToast('审批已发起，通过后任务才会流转')
    approvalOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '发起审批失败')
  } finally {
    approvalSaving.value = false
  }
}

/* ── 流程轨迹 ── */
const traceOpen = ref(false)
const traceTarget = ref<TaskRow | null>(null)
const traces = ref<TaskTraceRow[]>([])
const traceLoading = ref(false)

async function openTraces(row: TaskRow) {
  traceTarget.value = row
  traceOpen.value = true
  traceLoading.value = true
  try {
    traces.value = await taskApi.tasks.traces(row.id)
  } catch (error) {
    toastError(error, '流程轨迹加载失败')
    traces.value = []
  } finally {
    traceLoading.value = false
  }
}

async function remove(row: TaskRow) {
  const ok = await confirm({
    title: '删除任务',
    message: `确认删除「${row.taskTitle}」吗？已关联关键结果时进度会自动回落。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await taskApi.tasks.remove(row.id)
    pushToast('任务已删除')
    await list.load()
  } catch (error) {
    toastError(error, '任务删除失败')
  }
}
</script>

<template>
  <ListPage
    title="任务列表"
    desc="任务的派发、认领与状态流转都在这里；状态可选范围由后端状态机给出，任务可关联关键结果，完成后进度自动回写目标。"
    table-title="任务列表"
    :list="list"
    :columns="columns"
  >
    <template #before>
      <section class="band reveal">
        <div class="band-head">
          <SectionLabel title="查看范围" />
          <Segmented v-model="scope" label="任务范围" :options="SCOPE_OPTIONS" />
        </div>
        <p class="faint" style="font-size: 12px; line-height: 1.8">
          全部任务按权限与数据范围过滤；「我的任务」只看当前账号负责的任务；「待认领池」是没有责任人的任务，认领后进入待办。
        </p>
      </section>
    </template>

    <template #actions>
      <PermissionButton perm="task:task:add" variant="primary" icon="plus" @click="openCreate">新增任务</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="任务标题" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...statusOptions]" />
      </Field>
      <Field label="优先级">
        <Select v-model="list.query.priority" :options="[{ label: '全部优先级', value: '' }, ...PRIORITY_OPTIONS]" />
      </Field>
      <Field v-if="scope === 'all'" label="负责人">
        <UserSelect :model-value="list.query.assigneeId" placeholder="全部负责人" @update:model-value="pickKeywordOwner" />
      </Field>
    </template>

    <template #cell-taskTitle="{ row }">
      <span class="cell-title">{{ row.taskTitle }}</span>
      <span v-if="row.taskDesc" class="cell-sub">{{ row.taskDesc }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'closed' ? 'neutral' : row.status === 'done' ? 'ok' : 'info'" dot>{{ statusText(row) }}</Tag>
    </template>
    <template #cell-priority="{ row }">
      <Tag :tone="PRIORITY_TONE[row.priority || 'normal'] || 'neutral'">{{ PRIORITY_TEXT[row.priority || 'normal'] || row.priority }}</Tag>
    </template>
    <template #cell-assigneeName="{ row }">
      <span>{{ row.assigneeName || '待认领' }}</span>
    </template>
    <template #cell-kr="{ row }">
      <Tag v-if="row.krId" tone="info">KR #{{ row.krId }}</Tag>
      <span v-else class="faint">未关联</span>
    </template>
    <template #cell-subTasks="{ row }">
      <span class="tnum soft">{{ row.doneSubTaskCount ?? 0 }}/{{ row.subTaskCount ?? 0 }}</span>
    </template>
    <template #cell-dueDate="{ row }">
      <span class="tnum soft">{{ dateText(row.dueDate) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton v-if="row.status === 'pool'" perm="task:task:assign" size="sm" @click="claim(row)">认领</PermissionButton>
        <PermissionButton perm="task:task:list" icon-only icon="clock" label="流程轨迹" @click="openTraces(row)" />
        <PermissionButton perm="task:task:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
        <PermissionButton perm="task:task:assign" icon-only icon="users" label="指派" @click="openAssign(row)" />
        <PermissionButton
          perm="task:task:status"
          icon-only
          icon="arrowRight"
          label="状态流转"
          :disabled="!allowedTargetsOf(machine, row.status).length"
          @click="openTransition(row)"
        />
        <PermissionButton
          perm="task:task:status"
          icon-only
          icon="check"
          label="发起审批"
          :disabled="!allowedTargetsOf(machine, row.status).length"
          @click="openApproval(row)"
        />
        <PermissionButton perm="task:task:remove" icon-only icon="trash" label="删除" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑任务' : '新增任务'"
      sub="有负责人时任务进入「待办」，不指定负责人则进入「待认领池」"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="任务标题" required :error="errors.taskTitle" span>
        <TextInput v-model="form.taskTitle" placeholder="如：完成审批流联调" :invalid="!!errors.taskTitle" :maxlength="120" />
      </Field>
      <Field label="优先级">
        <Select :model-value="form.priority" :options="PRIORITY_OPTIONS" @update:model-value="pickPriority" />
      </Field>
      <Field label="排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="开始日期" :error="errors.startDate" hint="格式：2026-01-01">
        <TextInput v-model="form.startDate" placeholder="2026-01-01" :invalid="!!errors.startDate" />
      </Field>
      <Field label="截止日期" :error="errors.dueDate" hint="格式：2026-01-31">
        <TextInput v-model="form.dueDate" placeholder="2026-01-31" :invalid="!!errors.dueDate" />
      </Field>
      <Field v-if="!form.id" label="负责人" hint="不指定时进入待认领池">
        <UserSelect :model-value="formOwner" placeholder="选择负责人" @update:model-value="pickFormOwner" />
      </Field>
      <Field label="关联目标" hint="先选目标，再选该目标下的关键结果">
        <Select
          :model-value="formObjectiveId"
          :options="objectiveOptions"
          placeholder="不关联目标"
          filterable
          clearable
          @update:model-value="pickFormObjective"
        />
      </Field>
      <Field label="关联关键结果" hint="任务完成后会自动回写该关键结果的进度">
        <Select
          :model-value="formKrId"
          :options="krOptions"
          placeholder="不关联关键结果"
          filterable
          clearable
          @update:model-value="pickFormKr"
        />
      </Field>
      <p v-if="!okrReady" class="span-2 faint" style="width: 100%; font-size: 12px">
        当前账号没有目标查看权限，无法在此选择关键结果；任务仍可创建，稍后可在有权限的账号下补关联。
      </p>
      <Field label="任务描述" span>
        <TextInput v-model="form.taskDesc" multiline :rows="3" placeholder="交付内容与验收标准（可选）" :maxlength="500" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="内部备注（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="assignOpen"
      title="指派任务"
      :sub="assignTarget ? assignTarget.taskTitle : ''"
      :saving="assignSaving"
      submit-text="保存指派"
      @close="assignOpen = false"
      @submit="submitAssign(false)"
    >
      <Field label="负责人" span>
        <UserSelect :model-value="assignForm.assigneeId" placeholder="选择负责人" @update:model-value="pickAssignee" />
      </Field>
      <Field label="说明" span>
        <TextInput v-model="assignForm.remark" placeholder="指派说明（可选）" :maxlength="200" />
      </Field>
      <div class="span-2" style="width: 100%">
        <PermissionButton perm="task:task:assign" variant="ghost" danger :loading="assignSaving" @click="submitAssign(true)">收回指派（回到待认领池）</PermissionButton>
      </div>
    </FormDialog>

    <FormDialog
      :open="transitionOpen"
      title="状态流转"
      :sub="transitionTarget ? `${transitionTarget.taskTitle} · 当前：${statusText(transitionTarget)}` : ''"
      :saving="transitionSaving"
      submit-text="确认流转"
      @close="transitionOpen = false"
      @submit="submitTransition"
    >
      <Field label="流转到" required span hint="可选目标由后端状态机决定，非法目标不会出现在这里">
        <Select v-model="transitionForm.toStatus" :options="transitionOptions" placeholder="选择目标状态" />
      </Field>
      <Field label="说明" span>
        <TextInput v-model="transitionForm.opinion" placeholder="流转说明（可选）" :maxlength="200" />
      </Field>
      <p v-if="!transitionOptions.length" class="span-2 faint" style="width: 100%; font-size: 13px">
        当前状态没有可流转的目标状态（可能已关闭，或状态机未加载成功）。
      </p>
    </FormDialog>

    <FormDialog
      :open="approvalOpen"
      title="发起审批"
      :sub="approvalTarget ? `${approvalTarget.taskTitle} · 当前：${statusText(approvalTarget)}` : ''"
      :saving="approvalSaving"
      submit-text="发起审批"
      @close="approvalOpen = false"
      @submit="submitApproval"
    >
      <Field label="审批通过后流转到" required span hint="审批未通过前任务状态不变">
        <Select :model-value="approvalForm.targetStatus" :options="approvalOptions" placeholder="选择目标状态" @update:model-value="pickApprovalStatus" />
      </Field>
      <Field label="审批人" hint="留空则默认由任务创建人审批">
        <UserSelect :model-value="approvalForm.approverId" placeholder="选择审批人" @update:model-value="pickApprover" />
      </Field>
      <Field label="审批说明" span>
        <TextInput v-model="approvalForm.opinion" placeholder="说明本次审批事由（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <Drawer :open="traceOpen" title="流程轨迹" :sub="traceTarget ? traceTarget.taskTitle : ''" :width="760" @close="traceOpen = false">
      <DataTable
        :columns="[
          { key: 'nodeName', title: '节点', width: 120 },
          { key: 'change', title: '状态变化', minWidth: 180 },
          { key: 'operatorName', title: '操作人', width: 100 },
          { key: 'operatedTime', title: '时间', width: 150 },
          { key: 'opinion', title: '说明', minWidth: 180 }
        ]"
        :data="traces"
        :loading="traceLoading"
        empty-text="还没有流转记录"
      >
        <template #cell-nodeName="{ row }">
          <span class="cell-title">{{ row.nodeName || row.nodeKey || '—' }}</span>
        </template>
        <template #cell-change="{ row }">
          <span class="soft">
            {{ row.fromStatus ? statusLabelOf(machine, row.fromStatus) : '创建' }}
            <template v-if="row.toStatus"> → {{ statusLabelOf(machine, row.toStatus) }}</template>
          </span>
        </template>
        <template #cell-operatorName="{ row }">
          <span>{{ row.operatorName || '—' }}</span>
        </template>
        <template #cell-operatedTime="{ row }">
          <span class="tnum soft">{{ dateTimeText(row.operatedTime) }}</span>
        </template>
        <template #cell-opinion="{ row }">
          <span class="muted">{{ row.opinion || '—' }}</span>
        </template>
      </DataTable>
    </Drawer>
  </ListPage>
</template>
