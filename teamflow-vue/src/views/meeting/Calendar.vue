<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  Field,
  Select,
  Switch,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton } from '@/components/business'
import { meetingApi, type MeetingEventRow } from '@/api/meeting'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  EVENT_STATUS_LABEL,
  EVENT_TYPE_LABEL,
  EVENT_TYPE_OPTIONS,
  EVENT_TYPE_TONE,
  dayRangeParams,
  toDisplay,
  toIso,
} from './shared'

/* ============================================================
   日程（M06 · 2.2.1 日程与时间块）
   ------------------------------------------------------------
   个人/团队日程的增删改查。会议创建/改期/取消会同步生成或调整
   会议日程（来源=会议同步），这类日程只能通过会议操作调整，本页只读。
   时间按 ISO-8601 提交，界面按「yyyy-MM-dd HH:mm」展示。
   ============================================================ */

const list = useListPage<MeetingEventRow, { keyword: string; eventType: string; fromTime: string; toTime: string }>({
  query: { keyword: '', eventType: '', fromTime: '', toTime: '' },
  fetch: params => {
    const { keyword, eventType, fromTime, toTime } = params
    const range = dayRangeParams(String(fromTime ?? ''), String(toTime ?? ''))
    return meetingApi.events.list({ keyword: keyword || undefined, eventType: eventType || undefined, ...range })
  },
})

const columns: Column[] = [
  { key: 'eventTitle', title: '日程', minWidth: 240 },
  { key: 'time', title: '时间', width: 210 },
  { key: 'ownerName', title: '归属', width: 160 },
  { key: 'remindMinutes', title: '提醒', width: 110 },
  { key: 'status', title: '状态', width: 100 },
  { key: 'actions', title: '操作', width: 100, className: 'col-actions' },
]

interface EventForm {
  id?: number
  eventTitle: string
  eventType: string
  deptId: number | null
  startTime: string
  endTime: string
  allDay: boolean
  location: string
  description: string
  remindMinutes: number | null
  remark: string
}
const emptyForm = (): EventForm => ({
  eventTitle: '',
  eventType: 'personal',
  deptId: null,
  startTime: '',
  endTime: '',
  allDay: false,
  location: '',
  description: '',
  remindMinutes: 15,
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<EventForm>(emptyForm())
const errors = ref<Partial<Record<keyof EventForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: MeetingEventRow) {
  Object.assign(form, {
    id: row.id,
    eventTitle: row.eventTitle,
    eventType: row.eventType || 'personal',
    deptId: row.deptId ?? null,
    startTime: toDisplay(row.startTime),
    endTime: toDisplay(row.endTime),
    allDay: Boolean(row.allDay),
    location: row.location ?? '',
    description: row.description ?? '',
    remindMinutes: row.remindMinutes ?? 15,
    remark: '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitEvent() {
  const nextErrors = validate(form, {
    eventTitle: [required('请填写日程标题')],
    startTime: [required('请填写开始时间')],
    endTime: [required('请填写结束时间')],
  })
  if (!nextErrors.startTime && !nextErrors.endTime && form.endTime <= form.startTime) {
    nextErrors.endTime = '结束时间必须晚于开始时间'
  }
  errors.value = nextErrors
  if (Object.keys(errors.value).length) return
  saving.value = true
  try {
    const payload = {
      eventTitle: form.eventTitle.trim(),
      eventType: form.eventType,
      deptId: form.eventType === 'team' ? form.deptId : null,
      startTime: toIso(form.startTime) as string,
      endTime: toIso(form.endTime) as string,
      allDay: form.allDay,
      location: form.location.trim() || null,
      description: form.description.trim() || null,
      remindMinutes: form.remindMinutes ?? 0,
      remark: form.remark.trim() || null,
    }
    if (form.id) {
      await meetingApi.events.update(form.id, payload)
      pushToast('日程已更新')
    } else {
      await meetingApi.events.create(payload)
      pushToast('日程已创建')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    // 后端对时间/范围/归属的校验提示原样展示，不改写为通用错误
    toastError(error, '日程保存失败')
  } finally {
    saving.value = false
  }
}

async function cancelEvent(row: MeetingEventRow) {
  const ok = await confirm({
    title: '取消日程',
    message: `确认取消日程「${row.eventTitle}」吗？取消后不再出现在日程列表中，会议同步生成的日程请通过会议取消操作处理。`,
    confirmText: '取消日程',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.events.cancel(row.id)
    pushToast('日程已取消')
    await list.load()
  } catch (error) {
    toastError(error, '日程取消失败')
  }
}
</script>

<template>
  <ListPage
    title="日程日历"
    desc="个人与团队日程的时间块。会议创建、改期、取消会自动同步会议日程；由会议同步生成的日程不能在此修改，请通过会议操作调整。"
    table-title="日程列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="meeting:event:add" variant="primary" icon="plus" @click="openCreate">新增日程</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="日程标题" clearable @enter="list.search()" />
      </Field>
      <Field label="日程类型">
        <Select
          v-model="list.query.eventType"
          :options="[{ label: '全部类型', value: '' }, ...EVENT_TYPE_OPTIONS]"
        />
      </Field>
      <Field label="起始日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.fromTime" placeholder="如：2026-11-01" />
      </Field>
      <Field label="截止日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.toTime" placeholder="如：2026-11-30" />
      </Field>
    </template>

    <template #cell-eventTitle="{ row }">
      <div>
        <div class="cell-title">{{ row.eventTitle }}</div>
        <div class="cell-sub">
          <Tag :tone="EVENT_TYPE_TONE[row.eventType] ?? 'neutral'">{{ EVENT_TYPE_LABEL[row.eventType] ?? row.eventType }}</Tag>
          <span v-if="row.sourceType === 'meeting'" class="muted"> · 由会议同步生成</span>
          <span v-else-if="row.location" class="muted"> · {{ row.location }}</span>
        </div>
      </div>
    </template>
    <template #cell-time="{ row }">
      <div>
        <span class="tnum soft">{{ toDisplay(row.startTime) }} 至 {{ toDisplay(row.endTime) }}</span>
        <div v-if="row.allDay" class="cell-sub">全天</div>
      </div>
    </template>
    <template #cell-ownerName="{ row }">
      <span class="muted">{{ row.ownerName || '—' }}{{ row.deptName ? ` · ${row.deptName}` : '' }}</span>
    </template>
    <template #cell-remindMinutes="{ row }">
      <span class="tnum soft">{{ row.remindMinutes != null ? `${row.remindMinutes} 分钟前` : '—' }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'cancelled' ? 'danger' : 'neutral'" dot>
        {{ EVENT_STATUS_LABEL[row.status ?? 'active'] ?? row.status }}
      </Tag>
    </template>
    <template #cell-actions="{ row }">
      <div v-if="row.sourceType === 'meeting'" class="cell-sub">会议同步 · 只读</div>
      <div v-else class="row-actions">
        <PermissionButton perm="meeting:event:edit" icon-only icon="edit" label="编辑日程" @click="openEdit(row)" />
        <PermissionButton perm="meeting:event:remove" icon-only icon="ban" label="取消日程" danger @click="cancelEvent(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑日程' : '新增日程'"
      sub="团队日程可选择归属部门；会议同步生成的日程不在此维护。"
      :width="680"
      :saving="saving"
      @close="dialog = false"
      @submit="submitEvent"
    >
      <Field label="日程标题" required :error="errors.eventTitle" wide>
        <TextInput v-model="form.eventTitle" placeholder="如：周例会准备" :invalid="!!errors.eventTitle" :maxlength="200" />
      </Field>
      <Field label="日程类型">
        <Select v-model="form.eventType" :options="EVENT_TYPE_OPTIONS" />
      </Field>
      <Field v-if="form.eventType === 'team'" label="归属部门" hint="团队日程按部门可见">
        <DeptSelect v-model="form.deptId" clearable />
      </Field>
      <Field label="全天">
        <Switch v-model="form.allDay" label="全天日程" />
      </Field>
      <Field label="开始时间" required :error="errors.startTime" hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="form.startTime" placeholder="如：2026-11-01 09:00" :invalid="!!errors.startTime" />
      </Field>
      <Field label="结束时间" required :error="errors.endTime" hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="form.endTime" placeholder="如：2026-11-01 10:00" :invalid="!!errors.endTime" />
      </Field>
      <Field label="提前提醒" hint="提前多少分钟提醒；0 表示不提醒">
        <TextInput v-model="form.remindMinutes" type="number" />
      </Field>
      <Field label="地点">
        <TextInput v-model="form.location" placeholder="如：3 号会议室 / 线上" :maxlength="200" />
      </Field>
      <Field label="日程说明" span>
        <TextInput v-model="form.description" multiline :rows="2" placeholder="日程内容与准备事项" :maxlength="1000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
