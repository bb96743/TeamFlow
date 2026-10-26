<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  DataTable,
  Drawer,
  Field,
  Segmented,
  Select,
  Switch,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  meetingApi,
  type MeetingParticipantRow,
  type MeetingRoomRow,
  type MeetingRow,
  type MeetingTemplateRow,
} from '@/api/meeting'
import { ApiError } from '@/api'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  MEETING_STATUS_LABEL,
  MEETING_STATUS_OPTIONS,
  MEETING_STATUS_TONE,
  PARTICIPANT_ROLE_LABEL,
  RESPONSE_STATUS_LABEL,
  dayRangeParams,
  toDisplay,
  toIso,
} from './shared'

/* ============================================================
   会议管理（M06 · 2.2.2 会议全流程 + 纪要归档）
   ------------------------------------------------------------
   创建 / 议程 / 改期 / 取消 / 结束 / 参会人 / 纪要归档。
   两条关键体验：
     ① 会议室冲突与参会人时段冲突由后端判定，返回的可读提示原样展示；
     ② 会议结束 → 生成「撰写会议纪要」待办 → 填写纪要 → 后端归档进知识库，
        页面显示"已归档到知识库"与知识页面 ID。
   ============================================================ */

const list = useListPage<MeetingRow, { keyword: string; status: string; fromTime: string; toTime: string }>({
  query: { keyword: '', status: '', fromTime: '', toTime: '' },
  fetch: params => {
    const { keyword, status, fromTime, toTime } = params
    const range = dayRangeParams(String(fromTime ?? ''), String(toTime ?? ''))
    return meetingApi.meetings.list({ keyword: keyword || undefined, status: status || undefined, ...range })
  },
})

const templates = ref<MeetingTemplateRow[]>([])
const rooms = ref<MeetingRoomRow[]>([])

onMounted(async () => {
  try {
    templates.value = await meetingApi.templates.list({ status: 'active' })
  } catch {
    templates.value = []
  }
  try {
    rooms.value = await meetingApi.rooms.list({ status: 'available' })
  } catch {
    rooms.value = []
  }
})

const templateOptions = () => templates.value.map(item => ({
  label: item.templateName,
  value: item.id,
  hint: item.templateType === 'okr_calibration' ? '目标校准会' : item.templateType === 'retro' ? '复盘会' : '常规会议',
}))
const roomOptions = () => rooms.value.map(item => ({
  label: `${item.roomName}（${item.capacity ?? '—'} 人）`,
  value: item.id,
  hint: item.location || item.roomCode,
}))

const columns: Column[] = [
  { key: 'meetingTitle', title: '会议', minWidth: 240 },
  { key: 'time', title: '时间', width: 200 },
  { key: 'place', title: '会议室 / 地点', width: 180 },
  { key: 'organizerName', title: '主持人', width: 110 },
  { key: 'participantCount', title: '参会', width: 80, align: 'right' },
  { key: 'status', title: '状态', width: 100 },
  { key: 'minutes', title: '纪要', width: 168 },
  { key: 'actions', title: '操作', width: 190, className: 'col-actions' },
]

/* 会议保存（新建/编辑基本信息） */
interface MeetingForm {
  id?: number
  meetingTitle: string
  templateId: number | null
  roomId: number | null
  startTime: string
  endTime: string
  location: string
  meetingLink: string
  agenda: string
  remark: string
}
const emptyForm = (): MeetingForm => ({
  meetingTitle: '',
  templateId: null,
  roomId: null,
  startTime: '',
  endTime: '',
  location: '',
  meetingLink: '',
  agenda: '',
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<MeetingForm>(emptyForm())
const errors = ref<Partial<Record<keyof MeetingForm, string>>>({})
const formError = ref('')

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  formError.value = ''
  dialog.value = true
}

function openEdit(row: MeetingRow) {
  Object.assign(form, {
    id: row.id,
    meetingTitle: row.meetingTitle,
    templateId: row.templateId ?? null,
    roomId: row.roomId ?? null,
    startTime: toDisplay(row.startTime),
    endTime: toDisplay(row.endTime),
    location: row.location ?? '',
    meetingLink: row.meetingLink ?? '',
    agenda: row.agenda ?? '',
    remark: '',
  })
  errors.value = {}
  formError.value = ''
  dialog.value = true
}

/** 选择模板时带出议程（用户仍可编辑），并按默认时长补结束时间。 */
function applyTemplate(templateId: number | null) {
  form.templateId = templateId
  const template = templates.value.find(item => item.id === templateId)
  if (!template) return
  if (template.agendaTemplate) form.agenda = template.agendaTemplate
  if (template.defaultDurationMinutes && form.startTime) {
    const startIso = toIso(form.startTime)
    if (startIso) {
      const start = new Date(startIso)
      if (!Number.isNaN(start.getTime())) {
        const end = new Date(start.getTime() + template.defaultDurationMinutes * 60_000)
        form.endTime = toDisplay(end.toISOString())
      }
    }
  }
}

async function submitMeeting() {
  const nextErrors = validate(form, { meetingTitle: [required('请填写会议标题')] })
  if (!form.id) {
    if (!form.startTime) nextErrors.startTime = '请填写会议开始时间'
    if (!form.endTime) nextErrors.endTime = '请填写会议结束时间'
  }
  if (!nextErrors.startTime && !nextErrors.endTime && form.startTime && form.endTime
      && form.endTime <= form.startTime) {
    nextErrors.endTime = '结束时间必须晚于开始时间'
  }
  errors.value = nextErrors
  if (Object.keys(errors.value).length) return
  saving.value = true
  formError.value = ''
  try {
    if (form.id) {
      await meetingApi.meetings.update(form.id, {
        meetingTitle: form.meetingTitle.trim(),
        templateId: form.templateId,
        location: form.location.trim() || null,
        meetingLink: form.meetingLink.trim() || null,
        agenda: form.agenda.trim() || null,
        remark: form.remark.trim() || null,
      })
      pushToast('会议信息已更新')
    } else {
      await meetingApi.meetings.create({
        meetingTitle: form.meetingTitle.trim(),
        templateId: form.templateId,
        roomId: form.roomId,
        startTime: toIso(form.startTime) as string,
        endTime: toIso(form.endTime) as string,
        location: form.location.trim() || null,
        meetingLink: form.meetingLink.trim() || null,
        agenda: form.agenda.trim() || null,
        remark: form.remark.trim() || null,
      })
      pushToast('会议已创建，可在详情中添加参会人')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    // 会议室冲突 / 参会人冲突等后端提示原样展示（含占用人、冲突时段与建议）
    formError.value = error instanceof ApiError ? error.message : '会议保存失败'
    toastError(error, '会议保存失败')
  } finally {
    saving.value = false
  }
}

/* 改期（原会议室在改期时由后端释放，成功后刷新列表） */
const scheduleDialog = ref(false)
const scheduleSaving = ref(false)
const scheduleRow = ref<MeetingRow | null>(null)
const schedule = reactive<{ startTime: string; endTime: string; roomId: number | null; onlineOnly: boolean }>({
  startTime: '',
  endTime: '',
  roomId: null,
  onlineOnly: false,
})
const scheduleError = ref('')

function openSchedule(row: MeetingRow) {
  scheduleRow.value = row
  schedule.startTime = toDisplay(row.startTime)
  schedule.endTime = toDisplay(row.endTime)
  schedule.roomId = row.roomId ?? null
  schedule.onlineOnly = !row.roomId
  scheduleError.value = ''
  scheduleDialog.value = true
}

async function submitSchedule() {
  if (!scheduleRow.value) return
  if (!schedule.startTime || !schedule.endTime) {
    scheduleError.value = '请填写改期后的开始与结束时间'
    return
  }
  if (schedule.endTime <= schedule.startTime) {
    scheduleError.value = '结束时间必须晚于开始时间'
    return
  }
  scheduleSaving.value = true
  scheduleError.value = ''
  try {
    await meetingApi.meetings.reschedule(scheduleRow.value.id, {
      startTime: toIso(schedule.startTime) as string,
      endTime: toIso(schedule.endTime) as string,
      roomId: schedule.onlineOnly ? null : schedule.roomId,
      onlineOnly: schedule.onlineOnly,
    })
    pushToast('会议已改期，原会议室已释放')
    scheduleDialog.value = false
    await list.load()
  } catch (error) {
    scheduleError.value = error instanceof ApiError ? error.message : '会议改期失败'
    toastError(error, '会议改期失败')
  } finally {
    scheduleSaving.value = false
  }
}

/* 取消 / 删除 */
const cancelDialog = ref(false)
const cancelSaving = ref(false)
const cancelRow = ref<MeetingRow | null>(null)
const cancelReason = ref('')
const cancelError = ref('')

function openCancel(row: MeetingRow) {
  cancelRow.value = row
  cancelReason.value = ''
  cancelError.value = ''
  cancelDialog.value = true
}

async function submitCancel() {
  if (!cancelRow.value) return
  cancelSaving.value = true
  cancelError.value = ''
  try {
    await meetingApi.meetings.cancel(cancelRow.value.id, cancelReason.value.trim() || undefined)
    pushToast('会议已取消，会议室已释放')
    cancelDialog.value = false
    await list.load()
  } catch (error) {
    cancelError.value = error instanceof ApiError ? error.message : '会议取消失败'
    toastError(error, '会议取消失败')
  } finally {
    cancelSaving.value = false
  }
}

async function removeMeeting(row: MeetingRow) {
  const ok = await confirm({
    title: '删除会议',
    message: `确认删除会议「${row.meetingTitle}」吗？仅草稿或已取消的会议可删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.meetings.remove(row.id)
    pushToast('会议已删除')
    await list.load()
  } catch (error) {
    toastError(error, '会议删除失败')
  }
}

/* 结束会议 → 生成纪要待办 */
const minutesNotice = ref('')
const archiveNotice = ref('')

async function endMeeting(row: MeetingRow) {
  const ok = await confirm({
    title: '结束会议',
    message: `确认结束「${row.meetingTitle}」吗？结束后原会议室的占用会立即释放，并为主持人生成「撰写会议纪要」待办。`,
    confirmText: '结束会议',
  })
  if (!ok) return
  try {
    const result = await meetingApi.meetings.end(row.id)
    minutesNotice.value = `「${row.meetingTitle}」已结束，已生成纪要待办（ID ${result.minutesTodoId ?? '—'}）。请填写纪要并归档到知识库。`
    pushToast('会议已结束，已生成纪要待办')
    await list.load()
  } catch (error) {
    toastError(error, '结束会议失败')
  }
}

/* 纪要填写与归档 */
const minutesDialog = ref(false)
const minutesSaving = ref(false)
const minutesRow = ref<MeetingRow | null>(null)
const minutesForm = reactive<{ content: string; summary: string; tags: string }>({
  content: '',
  summary: '',
  tags: '',
})
const minutesError = ref('')

function openMinutes(row: MeetingRow) {
  minutesRow.value = row
  minutesForm.content = ''
  minutesForm.summary = ''
  minutesForm.tags = ''
  minutesError.value = ''
  minutesDialog.value = true
}

async function submitMinutes() {
  if (!minutesRow.value) return
  if (!minutesForm.content.trim()) {
    minutesError.value = '纪要正文不能为空'
    return
  }
  minutesSaving.value = true
  minutesError.value = ''
  try {
    const result = await meetingApi.meetings.archiveMinutes(minutesRow.value.id, {
      content: minutesForm.content.trim(),
      summary: minutesForm.summary.trim() || null,
      tags: minutesForm.tags.trim()
        ? minutesForm.tags.split(/[,，\s]+/).map(item => item.trim()).filter(Boolean)
        : undefined,
    })
    archiveNotice.value = `「${minutesRow.value.meetingTitle}」的会议纪要已归档到知识库（页面 ID ${result.minutesPageId}）。`
    pushToast('纪要已归档到知识库')
    minutesDialog.value = false
    await list.load()
  } catch (error) {
    minutesError.value = error instanceof ApiError ? error.message : '纪要归档失败'
    toastError(error, '纪要归档失败')
  } finally {
    minutesSaving.value = false
  }
}

/* 详情与参会人 */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<MeetingRow | null>(null)
const participantColumns: Column[] = [
  { key: 'nickname', title: '参会人', minWidth: 170 },
  { key: 'participantRole', title: '参会角色', width: 100 },
  { key: 'responseStatus', title: '回复', width: 100 },
  { key: 'actions', title: '操作', width: 80, className: 'col-actions' },
]
const addUserOpen = ref(false)
const addUserId = ref<number | null>(null)
const addRole = ref<'required' | 'optional'>('required')
const addError = ref('')

async function openDetail(row: MeetingRow) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await meetingApi.meetings.detail(row.id)
  } catch (error) {
    toastError(error, '会议详情加载失败')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

function openAddUser() {
  addUserId.value = null
  addRole.value = 'required'
  addError.value = ''
  addUserOpen.value = true
}

async function submitAddUser() {
  if (!detail.value) return
  if (!addUserId.value) {
    addError.value = '请选择要添加的参会人'
    return
  }
  addError.value = ''
  try {
    await meetingApi.meetings.addParticipants(detail.value.id, {
      userIds: [addUserId.value],
      participantRole: addRole.value,
    })
    pushToast('参会人已添加')
    addUserOpen.value = false
    detail.value = await meetingApi.meetings.detail(detail.value.id)
    await list.load()
  } catch (error) {
    // 参会人时段冲突由后端判定，提示原样展示
    addError.value = error instanceof ApiError ? error.message : '添加参会人失败'
    toastError(error, '添加参会人失败')
  }
}

async function removeParticipant(row: MeetingParticipantRow) {
  if (!detail.value) return
  const ok = await confirm({
    title: '移出参会人',
    message: `确认将「${row.nickname || row.username || `用户 #${row.userId}`}」移出本次会议吗？`,
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.meetings.removeParticipant(detail.value.id, row.userId)
    pushToast('参会人已移出')
    detail.value = await meetingApi.meetings.detail(detail.value.id)
    await list.load()
  } catch (error) {
    toastError(error, '移出参会人失败')
  }
}

function canEdit(row: MeetingRow) {
  return ['draft', 'scheduled', 'ongoing'].includes(row.status)
}
function canSchedule(row: MeetingRow) {
  return ['draft', 'scheduled'].includes(row.status)
}
function canEnd(row: MeetingRow) {
  return ['scheduled', 'ongoing'].includes(row.status)
}
function canCancel(row: MeetingRow) {
  return !['ended', 'cancelled'].includes(row.status)
}
function canDelete(row: MeetingRow) {
  return ['draft', 'cancelled'].includes(row.status)
}
function minutesText(row: MeetingRow) {
  if (row.minutesPageId) return `已归档 · 页面 #${row.minutesPageId}`
  if (row.status === 'ended') return '待填写纪要'
  return '—'
}
</script>

<template>
  <ListPage
    title="会议管理"
    desc="会议全流程：创建（可套用模板议程）、改期、取消、结束与纪要归档。冲突检测由服务端完成，返回的冲突说明会原样提示；会议结束后自动生成纪要待办，填写并归档后正文进入知识库。"
    table-title="会议列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="meeting:meeting:add" variant="primary" icon="plus" @click="openCreate">新增会议</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="会议标题" clearable @enter="list.search()" />
      </Field>
      <Field label="会议状态">
        <Select
          v-model="list.query.status"
          :options="[{ label: '全部状态', value: '' }, ...MEETING_STATUS_OPTIONS]"
        />
      </Field>
      <Field label="起始日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.fromTime" placeholder="如：2026-11-01" />
      </Field>
      <Field label="截止日期" hint="yyyy-MM-dd">
        <TextInput v-model="list.query.toTime" placeholder="如：2026-11-30" />
      </Field>
    </template>

    <template #before>
      <div v-if="minutesNotice || archiveNotice" class="band reveal" style="margin-bottom: 14px">
        <div class="band-head">
          <span class="section-label">会议纪要状态</span>
          <div class="band-tools">
            <Tag v-if="archiveNotice" tone="ok" dot>已归档到知识库</Tag>
            <Tag v-else tone="info" dot>待填写纪要</Tag>
          </div>
        </div>
        <p class="cell-sub" style="margin: 0">{{ archiveNotice || minutesNotice }}</p>
      </div>
    </template>

    <template #cell-meetingTitle="{ row }">
      <div>
        <div class="cell-title">{{ row.meetingTitle }}</div>
        <div class="cell-sub">
          <span v-if="row.templateName">{{ row.templateName }}</span>
          <span v-else class="muted">未套用模板</span>
          <span v-if="row.rescheduleCount" class="muted"> · 已改期 {{ row.rescheduleCount }} 次</span>
        </div>
      </div>
    </template>
    <template #cell-time="{ row }">
      <span class="tnum soft">{{ toDisplay(row.startTime) }} 至 {{ toDisplay(row.endTime) }}</span>
    </template>
    <template #cell-place="{ row }">
      <div>
        <div class="cell-title">{{ row.roomName || row.location || '—' }}</div>
        <div v-if="row.meetingLink" class="cell-sub">线上会议</div>
      </div>
    </template>
    <template #cell-organizerName="{ row }"><span class="muted">{{ row.organizerName || '—' }}</span></template>
    <template #cell-participantCount="{ row }"><span class="tnum">{{ row.participantCount ?? 0 }}</span></template>
    <template #cell-status="{ row }">
      <Tag :tone="MEETING_STATUS_TONE[row.status] ?? 'neutral'" dot>{{ MEETING_STATUS_LABEL[row.status] ?? row.status }}</Tag>
    </template>
    <template #cell-minutes="{ row }">
      <Tag v-if="row.minutesPageId" tone="ok">已归档 · #{{ row.minutesPageId }}</Tag>
      <Tag v-else-if="row.status === 'ended'" tone="info">待填写纪要</Tag>
      <span v-else class="muted">{{ minutesText(row) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="meeting:meeting:list" icon-only icon="eye" label="会议详情" @click="openDetail(row)" />
        <PermissionButton v-if="canEdit(row)" perm="meeting:meeting:edit" icon-only icon="edit" label="编辑会议" @click="openEdit(row)" />
        <PermissionButton v-if="canSchedule(row)" perm="meeting:meeting:edit" icon-only icon="arrowRight" label="会议改期" @click="openSchedule(row)" />
        <PermissionButton v-if="canEnd(row)" perm="meeting:meeting:edit" icon-only icon="check" label="结束会议" @click="endMeeting(row)" />
        <PermissionButton
          v-if="row.status === 'ended'"
          perm="meeting:meeting:edit"
          icon-only
          icon="file"
          label="填写并归档纪要"
          @click="openMinutes(row)"
        />
        <PermissionButton v-if="canCancel(row)" perm="meeting:meeting:edit" icon-only icon="ban" label="取消会议" danger @click="openCancel(row)" />
        <PermissionButton v-if="canDelete(row)" perm="meeting:meeting:remove" icon-only icon="trash" label="删除会议" danger @click="removeMeeting(row)" />
      </div>
    </template>

    <!-- 会议详情：议程 + 参会人 + 纪要归档状态 -->
    <Drawer
      :open="detailOpen"
      origin-from="tr"
      :title="detail ? `会议详情 · ${detail.meetingTitle}` : '会议详情'"
      :sub="detail ? `${toDisplay(detail.startTime)} 至 ${toDisplay(detail.endTime)} · ${MEETING_STATUS_LABEL[detail.status] ?? detail.status}` : ''"
      :width="880"
      @close="detailOpen = false"
    >
      <div v-if="detail">
        <div class="kv-grid">
          <div class="kv">
            <div class="kv-label">会议室 / 地点</div>
            <div class="kv-value">{{ detail.roomName || detail.location || '—' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">主持人</div>
            <div class="kv-value">{{ detail.organizerName || '—' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">模板</div>
            <div class="kv-value">{{ detail.templateName || '未套用模板' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">会议链接</div>
            <div class="kv-value">{{ detail.meetingLink || '—' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">纪要状态</div>
            <div class="kv-value">
              <Tag v-if="detail.minutesPageId" tone="ok" dot>已归档到知识库</Tag>
              <Tag v-else-if="detail.status === 'ended'" tone="info" dot>待填写纪要</Tag>
              <Tag v-else tone="neutral" dot>会议未结束</Tag>
              <div v-if="detail.minutesPageId" class="cell-sub">知识页面 ID：{{ detail.minutesPageId }}</div>
            </div>
          </div>
          <div class="kv">
            <div class="kv-label">改期次数</div>
            <div class="kv-value tnum">{{ detail.rescheduleCount ?? 0 }}</div>
          </div>
        </div>

        <div class="band-head" style="margin-top: 16px"><span class="section-label">会议议程</span></div>
        <p class="cell-sub" style="white-space: pre-wrap; margin: 0 0 14px">{{ detail.agenda || '未填写议程' }}</p>

        <div class="band-head">
          <span class="section-label">参会人</span>
          <div class="band-tools">
            <PermissionButton
              v-if="!['ended', 'cancelled'].includes(detail.status)"
              perm="meeting:meeting:edit"
              variant="primary"
              icon="plus"
              @click="openAddUser"
            >添加参会人</PermissionButton>
          </div>
        </div>
        <DataTable :columns="participantColumns" :data="detail.participants ?? []" row-key="id" :loading="detailLoading">
          <template #cell-nickname="{ row }">
            <div>
              <div class="cell-title">{{ row.nickname || row.username || `用户 #${row.userId}` }}</div>
              <div class="cell-sub">{{ row.deptName || '' }}</div>
            </div>
          </template>
          <template #cell-participantRole="{ row }">
            <Tag tone="neutral">{{ PARTICIPANT_ROLE_LABEL[row.participantRole ?? 'required'] ?? row.participantRole }}</Tag>
          </template>
          <template #cell-responseStatus="{ row }">
            <Tag :tone="row.responseStatus === 'accepted' ? 'ok' : row.responseStatus === 'declined' ? 'danger' : 'neutral'">
              {{ RESPONSE_STATUS_LABEL[row.responseStatus ?? 'pending'] ?? row.responseStatus }}
            </Tag>
          </template>
          <template #cell-actions="{ row }">
            <PermissionButton
              v-if="!['ended', 'cancelled'].includes(detail.status)"
              perm="meeting:meeting:edit"
              icon-only
              icon="ban"
              label="移出参会人"
              danger
              @click="removeParticipant(row)"
            />
          </template>
        </DataTable>
        <p class="faint" style="margin: 14px 0 0">
          参会人时段冲突由服务端校验（与其它会议时间重叠时会返回具体冲突信息）；纪要正文归档在知识库，会议记录只保存知识页面引用。
        </p>
      </div>
      <p v-else-if="detailLoading" class="faint">正在读取会议详情…</p>
    </Drawer>

    <!-- 新建 / 编辑会议 -->
    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑会议' : '新增会议'"
      sub="选择会议模板可带出议程与默认时长；会议室是否可用由服务端在保存时校验，冲突提示会原样显示。"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submitMeeting"
    >
      <Field label="会议标题" required :error="errors.meetingTitle" wide>
        <TextInput v-model="form.meetingTitle" placeholder="如：Q4 目标校准会" :invalid="!!errors.meetingTitle" :maxlength="200" />
      </Field>
      <Field label="会议模板" hint="选择后自动带出议程">
        <Select
          :model-value="form.templateId"
          :options="[{ label: '不套用模板', value: '' }, ...templateOptions()]"
          filterable
          @update:model-value="applyTemplate($event === '' || $event === null || $event === undefined ? null : Number($event))"
        />
      </Field>
      <Field label="会议室" hint="可空；选择后服务端做冲突检测">
        <Select v-model="form.roomId" :options="roomOptions()" placeholder="不占用会议室" filterable clearable />
      </Field>
      <Field label="开始时间" required :error="errors.startTime" hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="form.startTime" placeholder="如：2026-11-01 14:00" :invalid="!!errors.startTime" />
      </Field>
      <Field label="结束时间" required :error="errors.endTime" hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="form.endTime" placeholder="如：2026-11-01 15:00" :invalid="!!errors.endTime" />
      </Field>
      <Field label="地点">
        <TextInput v-model="form.location" placeholder="如：3 号会议室 / 线上" :maxlength="200" />
      </Field>
      <Field label="会议链接">
        <TextInput v-model="form.meetingLink" placeholder="线上会议链接（可空）" :maxlength="500" />
      </Field>
      <Field label="会议议程" span>
        <TextInput v-model="form.agenda" multiline :rows="4" placeholder="会议议题与时间安排" :maxlength="2000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
      <p v-if="formError" style="color: var(--red-text); margin: 0 0 8px; line-height: 1.6">{{ formError }}</p>
    </FormDialog>

    <!-- 改期 -->
    <FormDialog
      :open="scheduleDialog"
      title="会议改期"
      sub="改期会释放原会议室占用并按新时段重新检测冲突；参会人时段冲突会原样提示。"
      :width="620"
      :saving="scheduleSaving"
      submit-text="确认改期"
      @close="scheduleDialog = false"
      @submit="submitSchedule"
    >
      <Field label="开始时间" required hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="schedule.startTime" placeholder="如：2026-11-02 10:00" />
      </Field>
      <Field label="结束时间" required hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="schedule.endTime" placeholder="如：2026-11-02 11:00" />
      </Field>
      <Field label="仅线上">
        <Switch v-model="schedule.onlineOnly" label="不占用会议室" />
      </Field>
      <Field v-if="!schedule.onlineOnly" label="会议室">
        <Select v-model="schedule.roomId" :options="roomOptions()" placeholder="选择会议室" filterable clearable />
      </Field>
      <p v-if="scheduleError" style="color: var(--red-text); margin: 0 0 8px; line-height: 1.6">{{ scheduleError }}</p>
    </FormDialog>

    <!-- 取消 -->
    <FormDialog
      :open="cancelDialog"
      title="取消会议"
      sub="取消后会议室占用立即释放，并通知参会人；已结束的会议不能取消。"
      :saving="cancelSaving"
      submit-text="确认取消"
      @close="cancelDialog = false"
      @submit="submitCancel"
    >
      <Field label="取消原因" span hint="会随取消通知发送给参会人">
        <TextInput v-model="cancelReason" placeholder="如：时间冲突，另行安排" :maxlength="200" />
      </Field>
      <p v-if="cancelError" style="color: var(--red-text); margin: 0 0 8px; line-height: 1.6">{{ cancelError }}</p>
    </FormDialog>

    <!-- 纪要填写并归档 -->
    <FormDialog
      :open="minutesDialog"
      title="填写会议纪要"
      sub="提交后正文由后端归档到知识库（本模块只保存知识页面引用）；会议未结束时不能归档。"
      :width="720"
      :saving="minutesSaving"
      submit-text="保存并归档到知识库"
      @close="minutesDialog = false"
      @submit="submitMinutes"
    >
      <Field label="纪要正文" required wide hint="支持 Markdown；归档后可在知识库检索与查阅">
        <TextInput v-model="minutesForm.content" multiline :rows="8" placeholder="结论、决议、待办与责任人" />
      </Field>
      <Field label="结项摘要">
        <TextInput v-model="minutesForm.summary" placeholder="一句话总结本次会议结论" :maxlength="200" />
      </Field>
      <Field label="标签" hint="用逗号分隔，便于知识库检索">
        <TextInput v-model="minutesForm.tags" placeholder="如：目标校准, Q4" :maxlength="200" />
      </Field>
      <p v-if="minutesError" style="color: var(--red-text); margin: 0 0 8px; line-height: 1.6">{{ minutesError }}</p>
    </FormDialog>

    <!-- 添加参会人（冲突提示原样展示） -->
    <FormDialog
      :open="addUserOpen"
      title="添加参会人"
      sub="参会人时段与其它会议重叠时，服务端会拒绝并返回具体冲突信息。"
      :width="560"
      @close="addUserOpen = false"
      @submit="submitAddUser"
    >
      <Field label="选择用户" required :error="addError" wide>
        <UserSelect v-model="addUserId" placeholder="请选择参会人" />
      </Field>
      <Field label="参会角色" group>
        <Segmented
          :model-value="addRole"
          :options="[{ label: '必选', value: 'required' }, { label: '可选', value: 'optional' }]"
          @update:model-value="addRole = $event as 'required' | 'optional'"
        />
      </Field>
    </FormDialog>
  </ListPage>
</template>
