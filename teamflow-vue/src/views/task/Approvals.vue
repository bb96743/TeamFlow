<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Drawer, Field, SectionLabel, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { ListPage, PermissionButton } from '@/components/business'
import {
  taskApi,
  type FlowDefinitionRow,
  type FlowInstanceDetail,
  type FlowInstanceRow,
  type TaskStatusMachine,
} from '@/api/task'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import {
  FLOW_TASK_STATUS_TEXT,
  FLOW_TASK_STATUS_TONE,
  INSTANCE_STATUS_TEXT,
  INSTANCE_STATUS_TONE,
  INSTANCE_TYPE_TEXT,
  dateTimeText,
  parseNodes,
  statusLabelOf,
} from './support'

/* ============================================================
   审批中心：查看流程实例与审批待办，并就地处理「通过 / 驳回」。
   本期只做线性单节点审批：通过后才执行目标状态流转，驳回只结束实例、
   任务状态不变（审批与流转都在后端同一事务里完成）。
   ============================================================ */

const list = useListPage<FlowInstanceRow, { instanceType: string; status: string }>({
  query: { instanceType: '', status: '' },
  fetch: params => taskApi.flow.instances(params),
})

const columns: Column[] = [
  { key: 'bizTitle', title: '业务对象', minWidth: 200 },
  { key: 'instanceType', title: '类型', width: 96 },
  { key: 'currentNode', title: '当前节点', width: 110 },
  { key: 'status', title: '实例状态', width: 100 },
  { key: 'startByName', title: '发起人', width: 100 },
  { key: 'startTime', title: '发起时间', width: 150 },
  { key: 'targetStatus', title: '审批目标', width: 100 },
  { key: 'actions', title: '操作', width: 110, className: 'col-actions' },
]

/* ── 状态机与流程定义：只用于把代码翻译成中文，不参与业务判断 ── */
const machine = ref<TaskStatusMachine | null>(null)
const definitions = ref<FlowDefinitionRow[]>([])

onMounted(async () => {
  try {
    machine.value = await taskApi.flow.statusMachine()
  } catch {
    machine.value = null
  }
  try {
    const page = await taskApi.flow.definitions({ pageNum: 1, pageSize: 200 })
    definitions.value = page.rows
  } catch {
    definitions.value = []
  }
})

function nodeLabelOf(instance: FlowInstanceRow | null): string {
  if (!instance?.currentNode) return '—'
  const code = instance.currentNode
  const state = machine.value?.states.find(item => item.code === code)
  if (state) return state.label
  const definition = definitions.value.find(item => item.defCode === instance.defCode)
  const node = parseNodes(definition?.nodesJson).find(item => item.key === code)
  return node?.name || '—'
}

function targetLabelOf(instance: FlowInstanceRow): string {
  if (!instance.targetStatus) return '—'
  return instance.targetStatusLabel || statusLabelOf(machine.value, instance.targetStatus)
}

const INSTANCE_TYPE_OPTIONS = [
  { label: '全部类型', value: '' },
  { label: '状态流转', value: 'status' },
  { label: '审批', value: 'approval' },
]
const INSTANCE_STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '进行中', value: 'running' },
  { label: '已完成', value: 'completed' },
  { label: '已终止', value: 'terminated' },
]

/* ── 详情与审批处理 ── */
const detailOpen = ref(false)
const detail = ref<FlowInstanceDetail | null>(null)
const detailLoading = ref(false)
const approvalForm = reactive<{ opinion: string }>({ opinion: '' })
const approvalSaving = ref(false)

const pendingTask = computed(() => (detail.value?.flowTasks ?? []).find(task => task.status === 'pending') ?? null)
const canDecide = computed(() => detail.value?.status === 'running' && detail.value?.instanceType === 'approval' && Boolean(pendingTask.value))

async function openDetail(row: FlowInstanceRow) {
  detailOpen.value = true
  detail.value = null
  approvalForm.opinion = ''
  await loadDetail(row.id)
}

async function loadDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await taskApi.flow.instanceDetail(id)
  } catch (error) {
    toastError(error, '流程实例详情加载失败')
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function decide(approved: boolean) {
  if (!detail.value) return
  approvalSaving.value = true
  try {
    await taskApi.flow.approve(detail.value.id, { approved, opinion: approvalForm.opinion })
    pushToast(approved ? '审批已通过，任务状态已按目标流转' : '审批已驳回，任务状态保持不变')
    await loadDetail(detail.value.id)
    await list.load()
  } catch (error) {
    toastError(error, '审批处理失败')
  } finally {
    approvalSaving.value = false
  }
}
</script>

<template>
  <ListPage
    title="审批中心"
    desc="这里汇总任务流转与审批实例：审批通过后任务状态才按目标流转，驳回则只结束实例、任务保持原状态。"
    table-title="流程实例"
    :list="list"
    :columns="columns"
  >
    <template #search>
      <Field label="实例类型">
        <Select v-model="list.query.instanceType" :options="INSTANCE_TYPE_OPTIONS" />
      </Field>
      <Field label="实例状态">
        <Select v-model="list.query.status" :options="INSTANCE_STATUS_OPTIONS" />
      </Field>
    </template>

    <template #cell-bizTitle="{ row }">
      <span class="cell-title">{{ row.bizTitle || '—' }}</span>
      <span v-if="row.remark" class="cell-sub">{{ row.remark }}</span>
    </template>
    <template #cell-instanceType="{ row }">
      <Tag :tone="row.instanceType === 'approval' ? 'info' : 'neutral'">{{ INSTANCE_TYPE_TEXT[row.instanceType || ''] || row.instanceType || '—' }}</Tag>
    </template>
    <template #cell-currentNode="{ row }">
      <span class="soft">{{ nodeLabelOf(row) }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="INSTANCE_STATUS_TONE[row.status || ''] || 'neutral'" dot>{{ INSTANCE_STATUS_TEXT[row.status || ''] || row.status || '—' }}</Tag>
    </template>
    <template #cell-startByName="{ row }">
      <span>{{ row.startByName || '—' }}</span>
    </template>
    <template #cell-startTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.startTime) }}</span>
    </template>
    <template #cell-targetStatus="{ row }">
      <span class="soft">{{ targetLabelOf(row) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <Button variant="ghost" size="sm" @click="openDetail(row)">查看</Button>
      </div>
    </template>

    <Drawer
      :open="detailOpen"
      title="流程实例"
      :sub="detail?.bizTitle || ''"
      :width="820"
      @close="detailOpen = false"
    >
      <div v-if="detail" class="stack">
        <SpecPlate
          title="实例信息"
          :rows="[
            { label: '业务对象', value: detail.bizTitle },
            { label: '实例类型', value: INSTANCE_TYPE_TEXT[detail.instanceType || ''] || detail.instanceType },
            { label: '流程定义', value: detail.defCode },
            { label: '当前节点', value: nodeLabelOf(detail) },
            { label: '实例状态', value: INSTANCE_STATUS_TEXT[detail.status || ''] || detail.status },
            { label: '审批目标', value: targetLabelOf(detail) },
            { label: '发起人', value: detail.startByName },
            { label: '发起时间', value: dateTimeText(detail.startTime) },
            { label: '结束时间', value: dateTimeText(detail.endTime) },
          ]"
        />

        <div>
          <SectionLabel title="节点轨迹" />
          <DataTable
            :columns="[
              { key: 'nodeName', title: '节点', width: 110 },
              { key: 'nodeStatus', title: '结果', width: 90 },
              { key: 'change', title: '状态变化', minWidth: 170 },
              { key: 'operatorName', title: '操作人', width: 100 },
              { key: 'operatedTime', title: '时间', width: 150 },
              { key: 'opinion', title: '意见', minWidth: 160 }
            ]"
            :data="detail.nodes ?? []"
            :loading="detailLoading"
            empty-text="还没有节点记录"
          >
            <template #cell-nodeName="{ row }">
              <span class="cell-title">{{ row.nodeName || row.nodeKey || '—' }}</span>
            </template>
            <template #cell-nodeStatus="{ row }">
              <Tag :tone="row.nodeStatus === 'rejected' ? 'danger' : row.nodeStatus === 'completed' ? 'ok' : 'neutral'" dot>
                {{ row.nodeStatus === 'rejected' ? '驳回' : row.nodeStatus === 'completed' ? '完成' : (row.nodeStatus || '—') }}
              </Tag>
            </template>
            <template #cell-change="{ row }">
              <span class="soft">
                {{ row.fromStatus ? statusLabelOf(machine, row.fromStatus) : '—' }}
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
        </div>

        <div>
          <SectionLabel title="审批待办" />
          <DataTable
            :columns="[
              { key: 'taskTitle', title: '待办', minWidth: 220 },
              { key: 'assigneeName', title: '审批人', width: 110 },
              { key: 'status', title: '处理状态', width: 100 },
              { key: 'operateTime', title: '处理时间', width: 150 },
              { key: 'opinion', title: '意见', minWidth: 160 }
            ]"
            :data="detail.flowTasks ?? []"
            :loading="detailLoading"
            empty-text="该实例没有审批待办（状态流转实例不需要审批）"
          >
            <template #cell-taskTitle="{ row }">
              <span class="cell-title">{{ row.taskTitle || '—' }}</span>
            </template>
            <template #cell-assigneeName="{ row }">
              <span>{{ row.assigneeName || '—' }}</span>
            </template>
            <template #cell-status="{ row }">
              <Tag :tone="FLOW_TASK_STATUS_TONE[row.status || ''] || 'neutral'" dot>{{ FLOW_TASK_STATUS_TEXT[row.status || ''] || row.status || '—' }}</Tag>
            </template>
            <template #cell-operateTime="{ row }">
              <span class="tnum soft">{{ dateTimeText(row.operateTime) }}</span>
            </template>
            <template #cell-opinion="{ row }">
              <span class="muted">{{ row.opinion || '—' }}</span>
            </template>
          </DataTable>
        </div>

        <div v-if="canDecide">
          <SectionLabel title="审批处理" />
          <p class="faint" style="font-size: 12px; line-height: 1.8; margin-bottom: 10px">
            仅审批人本人可处理；通过后任务将流转为「{{ targetLabelOf(detail) }}」，驳回则任务保持当前状态。
          </p>
          <Field label="审批意见" span>
            <TextInput v-model="approvalForm.opinion" multiline :rows="2" placeholder="填写通过或驳回的原因（可选）" :maxlength="200" />
          </Field>
          <PermissionButton perm="flow:instance:approve" variant="primary" :loading="approvalSaving" @click="decide(true)">通过</PermissionButton>
          <PermissionButton perm="flow:instance:approve" variant="ghost" danger :loading="approvalSaving" @click="decide(false)">驳回</PermissionButton>
        </div>
        <p v-else-if="detail.instanceType === 'approval'" class="faint" style="font-size: 12px">
          该审批实例已处理完毕，不再提供处理入口。
        </p>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>
  </ListPage>
</template>
