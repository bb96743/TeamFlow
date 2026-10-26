<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
import {
  meetingApi,
  type MeetingBookingRow,
  type MeetingRoomAvailability,
  type MeetingRoomRow,
} from '@/api/meeting'
import { ApiError } from '@/api'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  BOOKING_STATUS_LABEL,
  ROOM_STATUS_LABEL,
  ROOM_STATUS_OPTIONS,
  toDisplay,
  toIso,
} from './shared'

/* ============================================================
   会议室（M06 · 会议室档案 + 预定 + 可用性/冲突检测）
   ------------------------------------------------------------
   冲突由服务端在同一事务内判定并返回可读提示（含占用人、冲突时段与建议）；
   本页把该提示**原样展示**在页面冲突提示条与表单内，不改写成通用错误。
   预定取消/会议改期或取消后，原时段恢复可用，本页刷新可用性。
   ============================================================ */

const rooms = ref<MeetingRoomRow[]>([])
const roomsLoading = ref(false)
const roomQuery = reactive<{ keyword: string; status: string }>({ keyword: '', status: '' })

const roomColumns: Column[] = [
  { key: 'roomName', title: '会议室', minWidth: 200 },
  { key: 'location', title: '位置', width: 150 },
  { key: 'capacity', title: '容量', width: 90, align: 'right' },
  { key: 'equipment', title: '设备', minWidth: 160 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'actions', title: '操作', width: 130, className: 'col-actions' },
]

async function loadRooms() {
  roomsLoading.value = true
  try {
    rooms.value = await meetingApi.rooms.list({
      keyword: roomQuery.keyword || undefined,
      status: roomQuery.status || undefined,
    })
  } catch (error) {
    toastError(error, '会议室加载失败')
  } finally {
    roomsLoading.value = false
  }
}

/* ── 可用性查询 ── */
const availabilityQuery = reactive<{ startTime: string; endTime: string; minCapacity: number | null }>({
  startTime: '',
  endTime: '',
  minCapacity: null,
})
const availability = ref<MeetingRoomAvailability[]>([])
const availabilityLoading = ref(false)
const availabilityError = ref('')
const availabilityColumns: Column[] = [
  { key: 'roomName', title: '会议室', minWidth: 180 },
  { key: 'capacity', title: '容量', width: 90, align: 'right' },
  { key: 'location', title: '位置', width: 140 },
  { key: 'available', title: '可用性', width: 110 },
  { key: 'conflictNote', title: '冲突说明', minWidth: 320 },
]

async function queryAvailability() {
  if (!availabilityQuery.startTime || !availabilityQuery.endTime) {
    availabilityError.value = '请填写要查询的开始与结束时间'
    return
  }
  availabilityError.value = ''
  availabilityLoading.value = true
  try {
    availability.value = await meetingApi.rooms.availability({
      startTime: toIso(availabilityQuery.startTime) as string,
      endTime: toIso(availabilityQuery.endTime) as string,
      minCapacity: availabilityQuery.minCapacity ?? undefined,
    })
  } catch (error) {
    availability.value = []
    availabilityError.value = error instanceof ApiError ? error.message : '可用性查询失败'
    toastError(error, '可用性查询失败')
  } finally {
    availabilityLoading.value = false
  }
}

/* ── 会议室档案 ── */
interface RoomForm {
  id?: number
  roomName: string
  roomCode: string
  location: string
  capacity: number | null
  equipment: string
  status: string
  sort: number | null
  remark: string
}
const emptyRoom = (): RoomForm => ({
  roomName: '',
  roomCode: '',
  location: '',
  capacity: 10,
  equipment: '',
  status: 'available',
  sort: 10,
  remark: '',
})
const roomDialog = ref(false)
const roomSaving = ref(false)
const roomForm = reactive<RoomForm>(emptyRoom())
const roomErrors = ref<Partial<Record<keyof RoomForm, string>>>({})

function openCreateRoom() {
  Object.assign(roomForm, emptyRoom(), { id: undefined })
  roomErrors.value = {}
  roomDialog.value = true
}

function openEditRoom(row: MeetingRoomRow) {
  Object.assign(roomForm, {
    id: row.id,
    roomName: row.roomName,
    roomCode: row.roomCode,
    location: row.location ?? '',
    capacity: row.capacity ?? null,
    equipment: row.equipment ?? '',
    status: row.status ?? 'available',
    sort: row.sort ?? 10,
    remark: row.remark ?? '',
  })
  roomErrors.value = {}
  roomDialog.value = true
}

async function submitRoom() {
  roomErrors.value = validate(roomForm, {
    roomName: [required('请填写会议室名称')],
    roomCode: [required('请填写会议室编码')],
  })
  if (Object.keys(roomErrors.value).length) return
  roomSaving.value = true
  try {
    const payload = {
      roomName: roomForm.roomName.trim(),
      roomCode: roomForm.roomCode.trim(),
      location: roomForm.location.trim() || null,
      capacity: roomForm.capacity ?? null,
      equipment: roomForm.equipment.trim() || null,
      status: roomForm.status,
      sort: roomForm.sort ?? 0,
      remark: roomForm.remark.trim() || null,
    }
    if (roomForm.id) {
      await meetingApi.rooms.update(roomForm.id, payload)
      pushToast('会议室已更新')
    } else {
      await meetingApi.rooms.create(payload)
      pushToast('会议室已创建')
    }
    roomDialog.value = false
    await loadRooms()
  } catch (error) {
    toastError(error, '会议室保存失败')
  } finally {
    roomSaving.value = false
  }
}

async function removeRoom(row: MeetingRoomRow) {
  const ok = await confirm({
    title: '删除会议室',
    message: `确认删除会议室「${row.roomName}」吗？已有有效预定的会议室不能删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.rooms.remove(row.id)
    pushToast('会议室已删除')
    await loadRooms()
  } catch (error) {
    toastError(error, '会议室删除失败')
  }
}

/* ── 预定 ── */
const bookings = ref<MeetingBookingRow[]>([])
const bookingsLoading = ref(false)
const bookingQuery = reactive<{ roomId: number | null; status: string }>({ roomId: null, status: '' })
const bookingColumns: Column[] = [
  { key: 'bookingTitle', title: '预定', minWidth: 200 },
  { key: 'roomName', title: '会议室', width: 150 },
  { key: 'startTime', title: '占用时段', width: 210 },
  { key: 'bookerName', title: '预定人', width: 110 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'actions', title: '操作', width: 90, className: 'col-actions' },
]

async function loadBookings() {
  bookingsLoading.value = true
  try {
    bookings.value = await meetingApi.bookings.list({
      roomId: bookingQuery.roomId ?? undefined,
      status: bookingQuery.status || undefined,
    })
  } catch (error) {
    toastError(error, '预定列表加载失败')
  } finally {
    bookingsLoading.value = false
  }
}

const bookingDialog = ref(false)
const bookingSaving = ref(false)
const bookingForm = reactive<{ roomId: number | null; bookingTitle: string; startTime: string; endTime: string; remark: string }>({
  roomId: null,
  bookingTitle: '',
  startTime: '',
  endTime: '',
  remark: '',
})
const bookingError = ref('')
const lastConflict = ref('')

const roomOptions = computed(() => rooms.value.map(item => ({
  label: `${item.roomName}（${item.capacity ?? '—'} 人）`,
  value: item.id,
  hint: item.location || item.roomCode,
})))

function openBooking(room?: MeetingRoomRow) {
  bookingForm.roomId = room?.id ?? null
  bookingForm.bookingTitle = ''
  bookingForm.startTime = ''
  bookingForm.endTime = ''
  bookingForm.remark = ''
  bookingError.value = ''
  bookingDialog.value = true
}

async function submitBooking() {
  const nextErrors = validate(bookingForm, {
    roomId: [required('请选择会议室')],
    bookingTitle: [required('请填写预定用途')],
    startTime: [required('请填写开始时间')],
    endTime: [required('请填写结束时间')],
  })
  bookingError.value = Object.values(nextErrors).find(Boolean) ?? ''
  if (bookingError.value) return
  if (bookingForm.endTime <= bookingForm.startTime) {
    bookingError.value = '结束时间必须晚于开始时间'
    return
  }
  bookingSaving.value = true
  bookingError.value = ''
  try {
    await meetingApi.bookings.create({
      roomId: bookingForm.roomId as number,
      bookingTitle: bookingForm.bookingTitle.trim(),
      startTime: toIso(bookingForm.startTime) as string,
      endTime: toIso(bookingForm.endTime) as string,
      remark: bookingForm.remark.trim() || null,
    })
    pushToast('会议室已预定')
    lastConflict.value = ''
    bookingDialog.value = false
    if (availability.value.length) await queryAvailability()
    await loadBookings()
  } catch (error) {
    // R1：后端冲突提示含会议室、占用时段、占用人与调整建议，原样展示
    const message = error instanceof ApiError ? error.message : '预定失败'
    bookingError.value = message
    lastConflict.value = message
    toastError(error, '预定失败')
  } finally {
    bookingSaving.value = false
  }
}

async function cancelBooking(row: MeetingBookingRow) {
  const ok = await confirm({
    title: '取消预定',
    message: `确认取消「${row.bookingTitle}」的会议室预定吗？取消后该时段立即恢复可用。`,
    confirmText: '取消预定',
    danger: true,
  })
  if (!ok) return
  try {
    await meetingApi.bookings.cancel(row.id)
    pushToast('预定已取消，时段已释放')
    await loadBookings()
    if (availability.value.length) await queryAvailability()
  } catch (error) {
    toastError(error, '取消预定失败')
  }
}

onMounted(async () => {
  await loadRooms()
  await loadBookings()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="会议室"
      desc="会议室档案、时段可用性与预定。冲突检测在服务端完成：同一会议室同一时段重复预定时会返回占用人与冲突时段，页面原样展示该提示。"
    >
      <PermissionButton perm="meeting:room:add" variant="primary" icon="plus" @click="openCreateRoom">新增会议室</PermissionButton>
      <PermissionButton perm="meeting:booking:add" variant="secondary" icon="calendar" @click="openBooking()">新增预定</PermissionButton>
    </PageHeader>

    <div v-if="lastConflict" class="band reveal" style="margin-bottom: 14px">
      <div class="band-head">
        <span class="section-label">会议室冲突</span>
        <Tag tone="danger" dot>预定未生效</Tag>
      </div>
      <p style="margin: 0; color: var(--red-text); line-height: 1.7">{{ lastConflict }}</p>
      <p class="cell-sub" style="margin: 6px 0 0">
        以上为服务端返回的原文；本次预定没有写入，原时段占用情况未改变。请调整时间或更换会议室后重试。
      </p>
    </div>

    <section class="band reveal">
      <div class="band-head">
        <span class="section-label">会议室档案</span>
        <div class="band-tools">
          <Field label="关键词">
            <TextInput v-model="roomQuery.keyword" icon="search" placeholder="名称 / 编码 / 位置" clearable @enter="loadRooms()" />
          </Field>
          <Field label="状态">
            <Select v-model="roomQuery.status" :options="[{ label: '全部状态', value: '' }, ...ROOM_STATUS_OPTIONS]" />
          </Field>
          <Button variant="secondary" icon="refresh" :loading="roomsLoading" @click="loadRooms">查询</Button>
        </div>
      </div>
      <DataTable :columns="roomColumns" :data="rooms" row-key="id" :loading="roomsLoading" :min-width="840">
        <template #cell-roomName="{ row }">
          <div>
            <div class="cell-title">{{ row.roomName }}</div>
            <div class="cell-sub">{{ row.roomCode }}</div>
          </div>
        </template>
        <template #cell-location="{ row }"><span class="muted">{{ row.location || '—' }}</span></template>
        <template #cell-capacity="{ row }"><span class="tnum">{{ row.capacity ?? '—' }}</span></template>
        <template #cell-equipment="{ row }"><span class="muted">{{ row.equipment || '—' }}</span></template>
        <template #cell-status="{ row }">
          <Tag :tone="row.status === 'available' ? 'ok' : 'neutral'" dot>
            {{ ROOM_STATUS_LABEL[row.status ?? 'available'] ?? row.status }}
          </Tag>
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions">
            <PermissionButton
              v-if="row.status === 'available'"
              perm="meeting:booking:add"
              icon-only
              icon="calendar"
              label="预定会议室"
              @click="openBooking(row)"
            />
            <PermissionButton perm="meeting:room:edit" icon-only icon="edit" label="编辑会议室" @click="openEditRoom(row)" />
            <PermissionButton perm="meeting:room:remove" icon-only icon="trash" label="删除会议室" danger @click="removeRoom(row)" />
          </div>
        </template>
      </DataTable>
    </section>

    <Panel title="时段可用性" meta="按区间查询每个会议室的占用情况">
      <div class="band-head">
        <div class="band-tools">
          <Field label="开始时间" hint="yyyy-MM-dd HH:mm">
            <TextInput v-model="availabilityQuery.startTime" placeholder="如：2026-11-01 14:00" />
          </Field>
          <Field label="结束时间" hint="yyyy-MM-dd HH:mm">
            <TextInput v-model="availabilityQuery.endTime" placeholder="如：2026-11-01 15:00" />
          </Field>
          <Field label="最低容量">
            <TextInput v-model="availabilityQuery.minCapacity" type="number" />
          </Field>
          <Button variant="primary" icon="search" :loading="availabilityLoading" @click="queryAvailability">查询可用性</Button>
        </div>
      </div>
      <p v-if="availabilityError" style="color: var(--red-text); margin: 0 0 10px">{{ availabilityError }}</p>
      <DataTable
        :columns="availabilityColumns"
        :data="availability"
        row-key="id"
        :loading="availabilityLoading"
        :min-width="900"
      >
        <template #cell-roomName="{ row }"><span class="cell-title">{{ row.roomName }}</span></template>
        <template #cell-capacity="{ row }"><span class="tnum">{{ row.capacity ?? '—' }}</span></template>
        <template #cell-location="{ row }"><span class="muted">{{ row.location || '—' }}</span></template>
        <template #cell-available="{ row }">
          <Tag :tone="row.available ? 'ok' : 'danger'" dot>{{ row.available ? '可用' : '已被占用' }}</Tag>
        </template>
        <template #cell-conflictNote="{ row }">
          <span v-if="row.conflictNote" style="color: var(--red-text)">{{ row.conflictNote }}</span>
          <span v-else class="muted">该时段无冲突</span>
        </template>
      </DataTable>
      <p class="faint" style="margin: 14px 0 0">
        可用性以服务端预定时段为准（半开区间比较）；会议改期、取消或预定取消后，原时段会恢复可用，重新查询即可看到最新结果。
      </p>
    </Panel>

    <Panel title="预定记录" :meta="`共 ${bookings.length} 条`">
      <div class="band-head">
        <div class="band-tools">
          <Field label="会议室">
            <Select v-model="bookingQuery.roomId" :options="roomOptions" placeholder="全部会议室" filterable clearable />
          </Field>
          <Field label="状态">
            <Select
              v-model="bookingQuery.status"
              :options="[{ label: '全部状态', value: '' }, { label: '已预定', value: 'booked' }, { label: '已取消', value: 'cancelled' }]"
            />
          </Field>
          <Button variant="secondary" icon="refresh" :loading="bookingsLoading" @click="loadBookings">刷新</Button>
        </div>
      </div>
      <DataTable :columns="bookingColumns" :data="bookings" row-key="id" :loading="bookingsLoading" :min-width="860">
        <template #cell-bookingTitle="{ row }">
          <div>
            <div class="cell-title">{{ row.bookingTitle }}</div>
            <div class="cell-sub">{{ row.meetingTitle || '独立预定' }}</div>
          </div>
        </template>
        <template #cell-roomName="{ row }"><span class="muted">{{ row.roomName || '—' }}</span></template>
        <template #cell-startTime="{ row }">
          <span class="tnum soft">{{ toDisplay(row.startTime) }} 至 {{ toDisplay(row.endTime) }}</span>
        </template>
        <template #cell-bookerName="{ row }"><span class="muted">{{ row.bookerName || '—' }}</span></template>
        <template #cell-status="{ row }">
          <Tag :tone="row.status === 'booked' ? 'ok' : 'neutral'" dot>
            {{ BOOKING_STATUS_LABEL[row.status ?? 'booked'] ?? row.status }}
          </Tag>
        </template>
        <template #cell-actions="{ row }">
          <PermissionButton
            v-if="row.status === 'booked'"
            perm="meeting:booking:cancel"
            icon-only
            icon="ban"
            label="取消预定"
            danger
            @click="cancelBooking(row)"
          />
          <span v-else class="cell-sub">已取消</span>
        </template>
      </DataTable>
    </Panel>

    <!-- 会议室档案表单 -->
    <FormDialog
      :open="roomDialog"
      :title="roomForm.id ? '编辑会议室' : '新增会议室'"
      sub="会议室编码唯一；停用的会议室不能接受预定。"
      :width="640"
      :saving="roomSaving"
      @close="roomDialog = false"
      @submit="submitRoom"
    >
      <Field label="会议室名称" required :error="roomErrors.roomName">
        <TextInput v-model="roomForm.roomName" placeholder="如：3 号会议室" :invalid="!!roomErrors.roomName" :maxlength="100" />
      </Field>
      <Field label="会议室编码" required :error="roomErrors.roomCode">
        <TextInput v-model="roomForm.roomCode" placeholder="如：ROOM-3F-01" :invalid="!!roomErrors.roomCode" :maxlength="64" />
      </Field>
      <Field label="位置">
        <TextInput v-model="roomForm.location" placeholder="如：3 层东侧" :maxlength="200" />
      </Field>
      <Field label="容量（人）">
        <TextInput v-model="roomForm.capacity" type="number" />
      </Field>
      <Field label="设备" span hint="顿号或逗号分隔">
        <TextInput v-model="roomForm.equipment" placeholder="如：投影、视频会议、白板" :maxlength="500" />
      </Field>
      <Field label="状态">
        <Select v-model="roomForm.status" :options="ROOM_STATUS_OPTIONS" />
      </Field>
      <Field label="显示排序">
        <TextInput v-model="roomForm.sort" type="number" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="roomForm.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>

    <!-- 预定时段 -->
    <FormDialog
      :open="bookingDialog"
      title="预定会议室"
      sub="提交时服务端会做时段冲突检测；若已占用会返回占用人与冲突时段，页面原样展示，不会写入本次预定。"
      :width="640"
      :saving="bookingSaving"
      submit-text="提交预定"
      @close="bookingDialog = false"
      @submit="submitBooking"
    >
      <Field label="会议室" required wide>
        <Select v-model="bookingForm.roomId" :options="roomOptions" placeholder="请选择会议室" filterable />
      </Field>
      <Field label="预定用途" required wide>
        <TextInput v-model="bookingForm.bookingTitle" placeholder="如：项目评审会占用" :maxlength="200" />
      </Field>
      <Field label="开始时间" required hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="bookingForm.startTime" placeholder="如：2026-11-01 14:30" />
      </Field>
      <Field label="结束时间" required hint="yyyy-MM-dd HH:mm">
        <TextInput v-model="bookingForm.endTime" placeholder="如：2026-11-01 15:30" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="bookingForm.remark" multiline :rows="2" placeholder="其他说明" :maxlength="200" />
      </Field>
      <p v-if="bookingError" style="color: var(--red-text); margin: 0 0 8px; line-height: 1.7">{{ bookingError }}</p>
    </FormDialog>
  </div>
</template>
