<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Field, Select, StatusTag, Switch, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { taskApi, type FlowDefinitionRow } from '@/api/task'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import { pattern, required, validate } from '@/utils/validate'
import { BUSINESS_TYPE_TEXT, dateTimeText, parseNodes } from './support'

/* ============================================================
   流程定义：本期只做「线性节点清单」，一条定义描述业务会经过哪些节点，
   供流程实例引用（例如任务状态流转、单节点审批）。
   节点清单以 JSON 保存，页面用一句人话解释格式，避免把内部实现细节直接抛给使用者。
   ============================================================ */

const list = useListPage<FlowDefinitionRow, { keyword: string; businessType: string }>({
  query: { keyword: '', businessType: '' },
  fetch: params => taskApi.flow.definitions(params),
})

const columns: Column[] = [
  { key: 'defName', title: '流程名称', minWidth: 200 },
  { key: 'defCode', title: '定义编码', width: 180 },
  { key: 'businessType', title: '业务类型', width: 110 },
  { key: 'nodes', title: '节点', minWidth: 220 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'updateTime', title: '更新时间', width: 150 },
  { key: 'actions', title: '操作', width: 90, className: 'col-actions' },
]

/** 业务类型筛选选项：取自后端已定义的类型，不在这里写死取值范围 */
const businessTypeOptions = computed(() => {
  const types = Array.from(new Set(list.rows.value.map(row => row.businessType).filter((type): type is string => Boolean(type))))
  return [{ label: '全部业务类型', value: '' }, ...types.map(type => ({ label: BUSINESS_TYPE_TEXT[type] || type, value: type }))]
})

function nodesText(row: FlowDefinitionRow): string {
  const nodes = parseNodes(row.nodesJson)
  if (!nodes.length) return '未配置节点'
  const names = nodes.map(node => node.name || node.key || '未命名').slice(0, 4)
  return nodes.length > 4 ? `${names.join(' → ')} …（共 ${nodes.length} 个）` : names.join(' → ')
}

interface FlowForm {
  id?: number
  defCode: string
  defName: string
  businessType: string
  nodesJson: string
  status: number
  remark: string
}
const NODE_EXAMPLE = '[{"key":"create","name":"创建"},{"key":"approve","name":"审批"}]'
const emptyForm = (): FlowForm => ({ defCode: '', defName: '', businessType: 'task_status', nodesJson: NODE_EXAMPLE, status: 1, remark: '' })

const dialog = ref(false)
const saving = ref(false)
const form = reactive<FlowForm>(emptyForm())
const errors = ref<Partial<Record<keyof FlowForm, string>>>({})

function pickBusinessType(value: string | number | null | undefined) {
  form.businessType = String(value ?? 'task_status')
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: FlowDefinitionRow) {
  Object.assign(form, {
    id: row.id,
    defCode: row.defCode,
    defName: row.defName,
    businessType: row.businessType || 'task_status',
    nodesJson: row.nodesJson || NODE_EXAMPLE,
    status: row.status ?? 1,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

function validateNodes(value: unknown): string | undefined {
  const text = String(value ?? '').trim()
  if (!text) return '请填写节点清单'
  try {
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed) || parsed.length === 0) return '节点清单需要是非空数组'
    if (parsed.some(node => !node || typeof node !== 'object' || !(node as Record<string, unknown>).key)) {
      return '每个节点都需要 key 字段'
    }
    return undefined
  } catch {
    return '节点清单不是合法的 JSON'
  }
}

async function submit() {
  errors.value = validate(form, {
    defCode: [required('请输入定义编码'), pattern(/^[A-Za-z][\w-]*$/, '字母开头，仅含字母、数字、下划线、短横线')],
    defName: [required('请输入流程名称')],
  })
  const nodeError = validateNodes(form.nodesJson)
  if (nodeError) errors.value.nodesJson = nodeError
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    defCode: form.defCode.trim(),
    defName: form.defName.trim(),
    businessType: form.businessType,
    nodesJson: form.nodesJson.trim(),
    status: form.status,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await taskApi.flow.updateDefinition(form.id, payload)
      pushToast('流程定义已更新')
    } else {
      await taskApi.flow.createDefinition(payload)
      pushToast('流程定义已新增')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '流程定义保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ListPage
    title="流程定义"
    desc="流程定义描述业务经过的节点清单，被流程实例引用；本期为线性节点，用于任务状态流转与单节点审批。"
    table-title="流程定义列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="flow:def:add" variant="primary" icon="plus" @click="openCreate">新增流程定义</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="流程名称 / 定义编码" clearable @enter="list.search()" />
      </Field>
      <Field label="业务类型">
        <Select v-model="list.query.businessType" :options="businessTypeOptions" />
      </Field>
    </template>

    <template #cell-defName="{ row }">
      <span class="cell-title">{{ row.defName }}</span>
      <span v-if="row.remark" class="cell-sub">{{ row.remark }}</span>
    </template>
    <template #cell-defCode="{ row }">
      <span class="code-chip">{{ row.defCode }}</span>
    </template>
    <template #cell-businessType="{ row }">
      <Tag tone="neutral">{{ BUSINESS_TYPE_TEXT[row.businessType || ''] || row.businessType || '—' }}</Tag>
    </template>
    <template #cell-nodes="{ row }">
      <span class="soft">{{ nodesText(row) }}</span>
    </template>
    <template #cell-status="{ row }">
      <StatusTag :status="row.status ?? 0" on="启用" off="停用" />
    </template>
    <template #cell-updateTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.updateTime) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="flow:def:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑流程定义' : '新增流程定义'"
      sub="节点清单按顺序描述业务经过的环节，供流程实例引用"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="流程名称" required :error="errors.defName">
        <TextInput v-model="form.defName" placeholder="如：任务状态线性流转" :invalid="!!errors.defName" :maxlength="60" />
      </Field>
      <Field label="定义编码" required :error="errors.defCode">
        <TextInput v-model="form.defCode" placeholder="如：task_status_linear" :invalid="!!errors.defCode" :maxlength="60" />
      </Field>
      <Field label="业务类型" hint="决定该流程服务于哪类业务">
        <Select
          :model-value="form.businessType"
          :options="[{ label: '任务状态', value: 'task_status' }, { label: '任务审批', value: 'task_approve' }]"
          @update:model-value="pickBusinessType"
        />
      </Field>
      <Field label="启用状态" group>
        <Switch :model-value="form.status === 1" :label="form.status === 1 ? '启用' : '停用'" @update:model-value="form.status = $event ? 1 : 0" />
      </Field>
      <Field
        label="节点清单"
        required
        span
        :error="errors.nodesJson"
        hint="按顺序排列的节点数组，每个节点需要 key（唯一标识）与 name（显示名称）"
      >
        <TextInput v-model="form.nodesJson" multiline :rows="5" :invalid="!!errors.nodesJson" placeholder='[{"key":"create","name":"创建"}]' />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="该流程的适用范围（可选）" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
