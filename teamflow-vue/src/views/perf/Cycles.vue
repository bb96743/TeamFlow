<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Field, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { perfApi, type PerfCycle, type PerfTemplate } from '@/api/perf'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { CYCLE_STATUS_OPTIONS, cycleStatusLabel, cycleStatusTone, cycleTypeLabel } from './labels'

/* 绩效周期：定义考核区间、绑定指标模板，并按状态机推进（草稿 → 进行中 → 评分中 → 已归档 → 已锁定）。
   界面只展示状态机提示与可用动作，流转合法性以后端状态机判定为准。 */

const list = useListPage<PerfCycle, { keyword: string; status: string }>({
  query: { keyword: '', status: '' },
  fetch: params => perfApi.cycles.list(params),
})

const columns: Column[] = [
  { key: 'cycleName', title: '绩效周期', minWidth: 220 },
  { key: 'cycleType', title: '类型', width: 90 },
  { key: 'range', title: '考核区间', width: 210 },
  { key: 'templateName', title: '指标模板', minWidth: 200 },
  { key: 'status', title: '状态', width: 100 },
  { key: 'collectTime', title: '最近归集', width: 150 },
  { key: 'actions', title: '操作', width: 250, className: 'col-actions' },
]

/* ── 概览读数与模板选项 ── */
const allCycles = ref<PerfCycle[]>([])
const templates = ref<PerfTemplate[]>([])

async function refreshOverview() {
  try {
    const [cyclePage, templatePage] = await Promise.all([
      perfApi.cycles.list({ pageNum: 1, pageSize: 100 }),
      perfApi.templates.list({ pageNum: 1, pageSize: 100 }),
    ])
    allCycles.value = cyclePage.rows
    templates.value = templatePage.rows
  } catch (error) {
    toastError(error, '周期概览加载失败')
  }
}

onMounted(refreshOverview)

const specRows = computed(() => {
  const count = (status: string) => allCycles.value.filter(cycle => cycle.status === status).length
  return [
    { label: '周期总数', value: `${allCycles.value.length} 个` },
    { label: '进行中', value: `${count('active')} 个` },
    { label: '评分中', value: `${count('scoring')} 个` },
    { label: '已归档', value: `${count('archived')} 个` },
    { label: '已锁定', value: `${count('locked')} 个`, tone: count('locked') ? 'red' as const : undefined },
  ]
})

const templateOptions = computed(() => templates.value.map(template => ({
  label: template.templateName,
  value: template.id,
  hint: `${template.templateCode} · ${template.itemCount} 项 · 权重合计 ${template.totalWeight}%`,
})))

/* ── 新增 / 编辑 ── */
interface CycleForm {
  id?: number
  cycleCode: string
  cycleName: string
  cycleType: string
  startDate: string
  endDate: string
  templateId: number | null
  remark: string
}

const emptyForm = (): CycleForm => ({
  cycleCode: '', cycleName: '', cycleType: 'quarter', startDate: '', endDate: '', templateId: null, remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const form = reactive<CycleForm>(emptyForm())
const errors = ref<Partial<Record<keyof CycleForm, string>>>({})
const isEdit = computed(() => Boolean(form.id))

const dateRule = (label: string) => (value: unknown) => {
  const text = String(value ?? '').trim()
  if (!text) return `请填写${label}`
  return /^\d{4}-\d{2}-\d{2}$/.test(text) ? undefined : `${label}格式应为 yyyy-MM-dd`
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  form.startDate = `${new Date().getFullYear()}-01-01`
  form.endDate = `${new Date().getFullYear()}-12-31`
  errors.value = {}
  dialog.value = true
}

function openEdit(row: PerfCycle) {
  Object.assign(form, {
    id: row.id,
    cycleCode: row.cycleCode,
    cycleName: row.cycleName,
    cycleType: row.cycleType,
    startDate: row.startDate,
    endDate: row.endDate,
    templateId: row.templateId ?? null,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = {}
  if (!form.cycleCode.trim() && !isEdit.value) errors.value.cycleCode = '请填写周期编码'
  if (!form.cycleName.trim()) errors.value.cycleName = '请填写周期名称'
  const startError = dateRule('开始日期')(form.startDate)
  if (startError) errors.value.startDate = startError
  const endError = dateRule('结束日期')(form.endDate)
  if (endError) errors.value.endDate = endError
  if (form.startDate && form.endDate && form.endDate < form.startDate) errors.value.endDate = '结束日期不能早于开始日期'
  if (Object.keys(errors.value).length) return

  saving.value = true
  const payload = {
    cycleCode: form.cycleCode.trim(),
    cycleName: form.cycleName.trim(),
    cycleType: form.cycleType,
    startDate: form.startDate,
    endDate: form.endDate,
    templateId: form.templateId,
    remark: form.remark.trim() || undefined,
  }
  try {
    if (form.id) {
      await perfApi.cycles.update(form.id, payload)
      pushToast('绩效周期已更新')
    } else {
      await perfApi.cycles.create(payload)
      pushToast('绩效周期已创建，可在绑定模板后开启')
    }
    dialog.value = false
    await Promise.all([list.load(), refreshOverview()])
  } catch (error) {
    toastError(error, '绩效周期保存失败')
  } finally {
    saving.value = false
  }
}

/* ── 状态流转（草稿→进行中→评分中；归档与锁定在结果归档页执行） ── */
function transitionLabel(target: string) {
  return target === 'active' ? '开启周期' : target === 'scoring' ? '进入评分' : target
}

async function advance(row: PerfCycle, target: 'active' | 'scoring') {
  const message = target === 'active'
    ? `开启「${row.cycleName}」后进入进行中，可开始归集与自评。开启前会校验模板已启用且指标权重合计 100%。`
    : `「${row.cycleName}」进入评分中后即可完成自评与上级评；归档与锁定请到「结果归档」页操作。`
  const ok = await confirm({ title: transitionLabel(target), message, confirmText: transitionLabel(target) })
  if (!ok) return
  try {
    await perfApi.cycles.updateStatus(row.id, target)
    pushToast(`「${row.cycleName}」已${target === 'active' ? '开启' : '进入评分'}`)
    await Promise.all([list.load(), refreshOverview()])
  } catch (error) {
    toastError(error, '状态流转失败')
  }
}

async function remove(row: PerfCycle) {
  const ok = await confirm({
    title: '删除绩效周期',
    message: `确认删除草稿周期「${row.cycleName}」吗？只有草稿状态的周期可以删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await perfApi.cycles.remove(row.id)
    pushToast('绩效周期已删除')
    await Promise.all([list.load(), refreshOverview()])
  } catch (error) {
    toastError(error, '绩效周期删除失败')
  }
}

function nextTargets(row: PerfCycle) {
  return (row.allowedTransitions ?? []).filter(target => target === 'active' || target === 'scoring')
}
</script>

<template>
  <ListPage
    title="绩效周期"
    desc="定义考核区间并绑定指标模板；周期状态依后端状态机推进，锁定后不可逆，任何修改只能走申诉流程。"
    table-title="周期列表"
    :list="list"
    :columns="columns"
    :min-width="1160"
  >
    <template #spec>
      <SpecPlate :rows="specRows" title="周期概况" />
    </template>

    <template #actions>
      <PermissionButton perm="perf:cycle:add" variant="primary" icon="plus" @click="openCreate">新增周期</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="周期编码 / 名称" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...CYCLE_STATUS_OPTIONS]" />
      </Field>
    </template>

    <template #cell-cycleName="{ row }">
      <div>
        <div class="cell-title">{{ row.cycleName }}</div>
        <div class="cell-sub tnum">{{ row.cycleCode }}</div>
      </div>
    </template>
    <template #cell-cycleType="{ row }"><span class="muted">{{ cycleTypeLabel(row.cycleType) }}</span></template>
    <template #cell-range="{ row }"><span class="tnum soft">{{ row.startDate }} → {{ row.endDate }}</span></template>
    <template #cell-templateName="{ row }">
      <div v-if="row.templateName">
        <div class="cell-title" style="font-weight: 560">{{ row.templateName }}</div>
        <div class="cell-sub">
          权重合计
          <Tag :tone="Number(row.templateTotalWeight) === 100 ? 'ok' : 'danger'" style="margin-left: 4px">
            {{ row.templateTotalWeight ?? '—' }}%
          </Tag>
        </div>
      </div>
      <span v-else class="faint">未绑定模板</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="cycleStatusTone(row.status)" dot>{{ cycleStatusLabel(row.status) }}</Tag>
    </template>
    <template #cell-collectTime="{ row }"><span class="tnum soft">{{ row.collectTime || '尚未归集' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <template v-if="row.status === 'draft' || row.status === 'active'">
          <PermissionButton
            v-if="nextTargets(row).length"
            perm="perf:cycle:status"
            icon-only
            icon="play"
            :label="transitionLabel(nextTargets(row)[0])"
            @click="advance(row, nextTargets(row)[0] as 'active' | 'scoring')"
          />
        </template>
        <span
          v-else-if="row.status === 'scoring'"
          class="faint"
          style="font-size: 12px"
          title="归档与锁定请到「结果归档」页执行"
        >去结果归档页归档</span>
        <span v-else class="faint" style="font-size: 12px">已锁定，不可变更</span>

        <span :title="row.editable ? '编辑周期' : '当前状态不可编辑'">
          <PermissionButton
            perm="perf:cycle:edit"
            icon-only
            icon="edit"
            label="编辑"
            :disabled="!row.editable"
            @click="openEdit(row)"
          />
        </span>
        <PermissionButton
          v-if="row.status === 'draft'"
          perm="perf:cycle:remove"
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
      :title="isEdit ? '编辑绩效周期' : '新增绩效周期'"
      :sub="isEdit ? '仅草稿、进行中、评分中的周期可编辑；已归档与已锁定周期只读' : '先建草稿，绑定模板后再开启周期'"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="周期编码" required :error="errors.cycleCode" hint="唯一编码，如 2026-Q4">
        <TextInput v-model="form.cycleCode" placeholder="2026-Q4" :disabled="isEdit" :invalid="!!errors.cycleCode" />
      </Field>
      <Field label="周期名称" required :error="errors.cycleName">
        <TextInput v-model="form.cycleName" placeholder="如 2026 年第四季度" :invalid="!!errors.cycleName" />
      </Field>
      <Field label="周期类型">
        <Select
          v-model="form.cycleType"
          :options="[{ label: '季度', value: 'quarter' }, { label: '月度', value: 'month' }]"
        />
      </Field>
      <Field label="指标模板" hint="开启周期前必须绑定已启用且权重合计 100% 的模板">
        <Select v-model="form.templateId" :options="templateOptions" placeholder="选择指标模板" clearable filterable />
      </Field>
      <Field label="考核开始日期" required :error="errors.startDate">
        <TextInput v-model="form.startDate" placeholder="yyyy-MM-dd" :invalid="!!errors.startDate" />
      </Field>
      <Field label="考核结束日期" required :error="errors.endDate">
        <TextInput v-model="form.endDate" placeholder="yyyy-MM-dd" :invalid="!!errors.endDate" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" placeholder="选填" multiline :rows="2" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
