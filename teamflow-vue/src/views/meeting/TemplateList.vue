<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  Button,
  DataTable,
  Field,
  PageHeader,
  Panel,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, PermissionButton } from '@/components/business'
import { meetingApi, type MeetingTemplateRow } from '@/api/meeting'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  TEMPLATE_STATUS_LABEL,
  TEMPLATE_STATUS_OPTIONS,
  TEMPLATE_TYPE_LABEL,
  TEMPLATE_TYPE_OPTIONS,
} from './shared'

/* ============================================================
   会议模板（M06 · 会议模板）
   ------------------------------------------------------------
   预置 3 个模板（常规会议 / 目标校准会 / 复盘会）；创建会议时可选择模板
   并带出议程与默认时长。内置模板不能删除（可停用），自定义模板可维护。
   ============================================================ */

const templates = ref<MeetingTemplateRow[]>([])
const loading = ref(false)
const query = reactive<{ keyword: string; templateType: string; status: string }>({
  keyword: '',
  templateType: '',
  status: '',
})

const columns: Column[] = [
  { key: 'templateName', title: '模板', minWidth: 220 },
  { key: 'templateType', title: '类型', width: 130 },
  { key: 'defaultDurationMinutes', title: '默认时长', width: 110, align: 'right' },
  { key: 'status', title: '状态', width: 96 },
  { key: 'builtin', title: '来源', width: 100 },
  { key: 'agendaTemplate', title: '议程模板', minWidth: 280 },
  { key: 'actions', title: '操作', width: 100, className: 'col-actions' },
]

async function loadTemplates() {
  loading.value = true
  try {
    templates.value = await meetingApi.templates.list({
      keyword: query.keyword || undefined,
      templateType: query.templateType || undefined,
      status: query.status || undefined,
    })
  } catch (error) {
    toastError(error, '会议模板加载失败')
  } finally {
    loading.value = false
  }
}

interface TemplateForm {
  id?: number
  templateCode: string
  templateName: string
  templateType: string
  description: string
  agendaTemplate: string
  defaultDurationMinutes: number | null
  status: string
  sort: number | null
  remark: string
}
const emptyForm = (): TemplateForm => ({
  templateCode: '',
  templateName: '',
  templateType: 'normal',
  description: '',
  agendaTemplate: '',
  defaultDurationMinutes: 60,
  status: 'active',
  sort: 10,
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<TemplateForm>(emptyForm())
const errors = ref<Partial<Record<keyof TemplateForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: MeetingTemplateRow) {
  Object.assign(form, {
    id: row.id,
    templateCode: row.templateCode,
    templateName: row.templateName,
    templateType: row.templateType,
    description: row.description ?? '',
    agendaTemplate: row.agendaTemplate ?? '',
    defaultDurationMinutes: row.defaultDurationMinutes ?? 60,
    status: row.status ?? 'active',
    sort: row.sort ?? 10,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitTemplate() {
  errors.value = validate(form, {
    templateCode: [required('请填写模板编码')],
    templateName: [required('请填写模板名称')],
  })
  if (Object.keys(errors.value).length) return
  saving.value = true
  try {
    const payload = {
      templateCode: form.templateCode.trim(),
      templateName: form.templateName.trim(),
      templateType: form.templateType,
      description: form.description.trim() || null,
      agendaTemplate: form.agendaTemplate.trim() || null,
      defaultDurationMinutes: form.defaultDurationMinutes ?? 60,
      status: form.status,
      sort: form.sort ?? 0,
      remark: form.remark.trim() || null,
    }
    if (form.id) {
      await meetingApi.templates.update(form.id, payload)
      pushToast('会议模板已更新')
    } else {
      await meetingApi.templates.create(payload)
      pushToast('会议模板已创建')
    }
    dialog.value = false
    await loadTemplates()
  } catch (error) {
    toastError(error, '会议模板保存失败')
  } finally {
    saving.value = false
  }
}

async function removeTemplate(row: MeetingTemplateRow) {
  const ok = await confirm({
    title: '删除会议模板',
    message: `确认删除模板「${row.templateName}」吗？内置模板不能删除，可改为停用。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.templates.remove(row.id)
    pushToast('会议模板已删除')
    await loadTemplates()
  } catch (error) {
    toastError(error, '会议模板删除失败')
  }
}

onMounted(loadTemplates)
</script>

<template>
  <div class="page">
    <PageHeader
      title="会议模板"
      desc="沉淀常用会议议程与默认时长。创建会议时可选择模板带出议程；内置模板（常规会议 / 目标校准会 / 复盘会）不能删除，可停用。"
    >
      <PermissionButton perm="meeting:template:add" variant="primary" icon="plus" @click="openCreate">新增模板</PermissionButton>
    </PageHeader>

    <Panel title="模板列表" :meta="`共 ${templates.length} 个`">
      <div class="band-head">
        <div class="band-tools">
          <Field label="关键词">
            <TextInput v-model="query.keyword" icon="search" placeholder="模板名称 / 编码" clearable @enter="loadTemplates()" />
          </Field>
          <Field label="模板类型">
            <Select v-model="query.templateType" :options="[{ label: '全部类型', value: '' }, ...TEMPLATE_TYPE_OPTIONS]" />
          </Field>
          <Field label="状态">
            <Select v-model="query.status" :options="[{ label: '全部状态', value: '' }, ...TEMPLATE_STATUS_OPTIONS]" />
          </Field>
          <Button variant="secondary" icon="refresh" :loading="loading" @click="loadTemplates">查询</Button>
        </div>
      </div>

      <DataTable :columns="columns" :data="templates" row-key="id" :loading="loading" :min-width="980">
        <template #cell-templateName="{ row }">
          <div>
            <div class="cell-title">{{ row.templateName }}</div>
            <div class="cell-sub">{{ row.templateCode }}{{ row.description ? ` · ${row.description}` : '' }}</div>
          </div>
        </template>
        <template #cell-templateType="{ row }">
          <Tag tone="info">{{ TEMPLATE_TYPE_LABEL[row.templateType] ?? row.templateType }}</Tag>
        </template>
        <template #cell-defaultDurationMinutes="{ row }">
          <span class="tnum">{{ row.defaultDurationMinutes ?? '—' }} 分钟</span>
        </template>
        <template #cell-status="{ row }">
          <Tag :tone="row.status === 'active' ? 'ok' : 'neutral'" dot>
            {{ TEMPLATE_STATUS_LABEL[row.status ?? 'active'] ?? row.status }}
          </Tag>
        </template>
        <template #cell-builtin="{ row }">
          <Tag :tone="row.builtin ? 'neutral' : 'info'">{{ row.builtin ? '内置' : '自定义' }}</Tag>
        </template>
        <template #cell-agendaTemplate="{ row }">
          <span v-if="row.agendaTemplate" class="cell-sub" style="white-space: pre-wrap">{{ row.agendaTemplate }}</span>
          <span v-else class="muted">未填写议程模板</span>
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions">
            <PermissionButton perm="meeting:template:edit" icon-only icon="edit" label="编辑模板" @click="openEdit(row)" />
            <PermissionButton
              v-if="!row.builtin"
              perm="meeting:template:remove"
              icon-only
              icon="trash"
              label="删除模板"
              danger
              @click="removeTemplate(row)"
            />
          </div>
        </template>
      </DataTable>
      <p class="faint" style="margin: 14px 0 0">
        内置模板用于统一会议节奏：常规会议用于周会/项目会，目标校准会用于对齐目标与关键结果，复盘会用于阶段复盘；停用后不再出现在创建会议的可选模板中。
      </p>
    </Panel>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑会议模板' : '新增会议模板'"
      sub="模板编码唯一；议程模板会作为创建会议时的默认议程，用户仍可修改。"
      :width="680"
      :saving="saving"
      @close="dialog = false"
      @submit="submitTemplate"
    >
      <Field label="模板名称" required :error="errors.templateName">
        <TextInput v-model="form.templateName" placeholder="如：周项目例会" :invalid="!!errors.templateName" :maxlength="100" />
      </Field>
      <Field label="模板编码" required :error="errors.templateCode">
        <TextInput v-model="form.templateCode" placeholder="如：weekly-project" :invalid="!!errors.templateCode" :maxlength="64" />
      </Field>
      <Field label="模板类型">
        <Select v-model="form.templateType" :options="TEMPLATE_TYPE_OPTIONS" />
      </Field>
      <Field label="默认时长（分钟）">
        <TextInput v-model="form.defaultDurationMinutes" type="number" />
      </Field>
      <Field label="状态">
        <Select v-model="form.status" :options="TEMPLATE_STATUS_OPTIONS" />
      </Field>
      <Field label="显示排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="模板说明" span>
        <TextInput v-model="form.description" placeholder="这个模板适用什么场景" :maxlength="500" />
      </Field>
      <Field label="议程模板" span hint="换行分隔议题；创建会议时会带出到议程">
        <TextInput
          v-model="form.agendaTemplate"
          multiline
          :rows="5"
          placeholder="1. 上周进展回顾&#10;2. 本周目标与风险&#10;3. 需协调事项"
          :maxlength="2000"
        />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>
  </div>
</template>
