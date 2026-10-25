<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Field, Select, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { okrApi, type OkrPeriodRow } from '@/api/okr'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { pattern, required, validate } from '@/utils/validate'
import { PERIOD_STATUS_TEXT, PERIOD_STATUS_TONE, PERIOD_TYPE_OPTIONS, PERIOD_TYPE_TEXT, dateText } from './support'

/* ============================================================
   目标周期：一个周期（季度/月度）承载一组目标与关键结果。
   状态流转（未开启 → 进行中 → 已归档）由后端判定，归档后周期内
   目标与关键结果全部只读，因此这里对已归档周期不再显示写操作。
   ============================================================ */

const list = useListPage<OkrPeriodRow, { keyword: string; status: string }>({
  query: { keyword: '', status: '' },
  fetch: params => okrApi.periods.list(params),
})

const columns: Column[] = [
  { key: 'periodName', title: '周期名称', minWidth: 220 },
  { key: 'periodType', title: '类型', width: 90 },
  { key: 'range', title: '起止日期', minWidth: 200 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'objectiveCount', title: '目标数', width: 90, align: 'right' },
  { key: 'sort', title: '排序', width: 76, align: 'right' },
  { key: 'actions', title: '操作', width: 200, className: 'col-actions' },
]

interface PeriodForm {
  id?: number
  periodName: string
  periodType: string
  startDate: string
  endDate: string
  sort: number | null
  remark: string
}

const DATE_RULE = /^\d{4}-\d{2}-\d{2}$/
const emptyForm = (): PeriodForm => ({ periodName: '', periodType: 'quarter', startDate: '', endDate: '', sort: 10, remark: '' })

const dialog = ref(false)
const saving = ref(false)
const form = reactive<PeriodForm>(emptyForm())
const errors = ref<Partial<Record<keyof PeriodForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: OkrPeriodRow) {
  Object.assign(form, {
    id: row.id,
    periodName: row.periodName,
    periodType: row.periodType || 'quarter',
    startDate: dateText(row.startDate),
    endDate: dateText(row.endDate),
    sort: row.sort ?? 0,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    periodName: [required('请输入周期名称')],
    startDate: [required('请选择开始日期'), pattern(DATE_RULE, '日期格式为 2026-01-01')],
    endDate: [required('请选择结束日期'), pattern(DATE_RULE, '日期格式为 2026-01-01')],
  })
  if (!errors.value.startDate && !errors.value.endDate && form.endDate < form.startDate) {
    errors.value.endDate = '结束日期不能早于开始日期'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    periodName: form.periodName.trim(),
    periodType: form.periodType,
    startDate: form.startDate,
    endDate: form.endDate,
    sort: form.sort ?? 0,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await okrApi.periods.update(form.id, payload)
      pushToast('目标周期已更新')
    } else {
      await okrApi.periods.create(payload)
      pushToast('目标周期已新增，默认未开启')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '目标周期保存失败')
  } finally {
    saving.value = false
  }
}

/** 状态流转按钮：与后端 OkrPeriodService 的允许迁移一致（未开启→进行中/已归档，进行中→已归档） */
const STATUS_ACTIONS: Record<string, Array<{ label: string; status: string }>> = {
  draft: [{ label: '开启', status: 'active' }, { label: '归档', status: 'archived' }],
  active: [{ label: '归档', status: 'archived' }],
  archived: [],
}

async function changeStatus(row: OkrPeriodRow, action: { label: string; status: string }) {
  const tip = action.status === 'archived'
    ? `归档后「${row.periodName}」下的目标与关键结果将全部只读，确认归档吗？`
    : `确认开启「${row.periodName}」吗？开启后即可在该周期下制定目标。`
  const ok = await confirm({ title: `${action.label}周期`, message: tip, confirmText: action.label })
  if (!ok) return
  try {
    await okrApi.periods.updateStatus(row.id, action.status)
    pushToast(`周期已${action.label}`)
    await list.load()
  } catch (error) {
    toastError(error, `${action.label}失败`)
  }
}

async function remove(row: OkrPeriodRow) {
  const ok = await confirm({
    title: '删除周期',
    message: `确认删除「${row.periodName}」吗？周期下仍有目标时不能删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await okrApi.periods.remove(row.id)
    pushToast('周期已删除')
    await list.load()
  } catch (error) {
    toastError(error, '周期删除失败')
  }
}
</script>

<template>
  <ListPage
    title="目标周期"
    desc="周期是目标与关键结果的容器：先建周期并开启，再在周期下制定目标、拆解关键结果；归档后该周期内容全部只读。"
    table-title="周期列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="okr:period:add" variant="primary" icon="plus" @click="openCreate">新增周期</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="周期名称" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select
          v-model="list.query.status"
          :options="[{ label: '全部状态', value: '' }, { label: '未开启', value: 'draft' }, { label: '进行中', value: 'active' }, { label: '已归档', value: 'archived' }]"
        />
      </Field>
    </template>

    <template #cell-periodName="{ row }">
      <span class="cell-title">{{ row.periodName }}</span>
    </template>
    <template #cell-periodType="{ row }">
      <Tag tone="neutral">{{ PERIOD_TYPE_TEXT[row.periodType] || row.periodType }}</Tag>
    </template>
    <template #cell-range="{ row }">
      <span class="tnum soft">{{ dateText(row.startDate) }} ~ {{ dateText(row.endDate) }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="PERIOD_STATUS_TONE[row.status] || 'neutral'" dot>{{ PERIOD_STATUS_TEXT[row.status] || row.status }}</Tag>
    </template>
    <template #cell-objectiveCount="{ row }">
      <span class="tnum">{{ row.objectiveCount ?? 0 }}</span>
    </template>
    <template #cell-sort="{ row }">
      <span class="tnum soft">{{ row.sort ?? 0 }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton
          v-for="action in STATUS_ACTIONS[row.status] || []"
          :key="action.status"
          perm="okr:period:edit"
          size="sm"
          @click="changeStatus(row, action)"
        >{{ action.label }}</PermissionButton>
        <PermissionButton
          v-if="row.status !== 'archived'"
          perm="okr:period:edit"
          icon-only
          icon="edit"
          label="编辑"
          @click="openEdit(row)"
        />
        <PermissionButton
          v-if="row.status !== 'archived'"
          perm="okr:period:remove"
          icon-only
          icon="trash"
          label="删除"
          danger
          @click="remove(row)"
        />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑周期' : '新增周期'"
      sub="新建周期默认「未开启」，开启后即可在周期下制定目标"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="周期名称" required :error="errors.periodName" wide>
        <TextInput v-model="form.periodName" placeholder="如：2026 年 Q1" :invalid="!!errors.periodName" :maxlength="60" />
      </Field>
      <Field label="周期类型">
        <Select v-model="form.periodType" :options="PERIOD_TYPE_OPTIONS" />
      </Field>
      <Field label="排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="开始日期" required :error="errors.startDate" hint="格式：2026-01-01">
        <TextInput v-model="form.startDate" placeholder="2026-01-01" :invalid="!!errors.startDate" />
      </Field>
      <Field label="结束日期" required :error="errors.endDate" hint="格式：2026-03-31">
        <TextInput v-model="form.endDate" placeholder="2026-03-31" :invalid="!!errors.endDate" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="本周期的重点说明（可选）" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
