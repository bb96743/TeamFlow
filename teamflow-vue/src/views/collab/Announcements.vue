<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Drawer, Field, SectionLabel, Segmented, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton } from '@/components/business'
import { collabApi, type CollabAnnouncementRow } from '@/api/collab'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  ANNOUNCEMENT_STATUS_OPTIONS,
  ANNOUNCEMENT_STATUS_TEXT,
  ANNOUNCEMENT_STATUS_TONE,
  dateTimeText,
} from './support'

/* ============================================================
   公告：全员 / 部门两级范围的内部通告。
   · 可见范围由后端按平台数据权限判定——部门公告只有数据范围覆盖该部门的人能看到，
     前端不做任何过滤，也不展示越权内容；
   · 已读状态由后端记录：打开公告详情即标记已读，列表展示「已读 / 未读」。
   ============================================================ */

const list = useListPage<CollabAnnouncementRow, { status: string }>({
  query: { status: '' },
  fetch: params => collabApi.announcements.list(params),
})

const columns: Column[] = [
  { key: 'title', title: '公告标题', minWidth: 240 },
  { key: 'scope', title: '可见范围', width: 150 },
  { key: 'publisherName', title: '发布人', width: 110 },
  { key: 'publishTime', title: '发布时间', width: 150 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'read', title: '阅读状态', width: 120 },
  { key: 'actions', title: '操作', width: 130, className: 'col-actions' },
]

/* ── 发布 / 编辑 ── */
interface AnnouncementForm {
  id?: number
  title: string
  content: string
  scopeType: 'all' | 'dept'
  deptId: number | null
  status: string
}
const emptyForm = (): AnnouncementForm => ({ title: '', content: '', scopeType: 'all', deptId: null, status: 'draft' })

const SCOPE_OPTIONS = [
  { label: '全员公告', value: 'all' as const },
  { label: '部门公告', value: 'dept' as const },
]

const dialog = ref(false)
const saving = ref(false)
const form = reactive<AnnouncementForm>(emptyForm())
const errors = ref<Partial<Record<keyof AnnouncementForm, string>>>({})

function pickScope(value: 'all' | 'dept') {
  form.scopeType = value
  if (value === 'all') form.deptId = null
}

function pickStatus(value: string | number | null | undefined) {
  form.status = String(value ?? 'draft')
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: CollabAnnouncementRow) {
  Object.assign(form, {
    id: row.id,
    title: row.title,
    content: row.content,
    scopeType: row.scopeType === 'dept' ? 'dept' : 'all',
    deptId: row.deptId ?? null,
    status: row.status || 'draft',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    title: [required('请输入公告标题')],
    content: [required('请输入公告正文')],
    deptId: [required('部门公告必须选择部门')],
  })
  if (form.scopeType !== 'dept') delete errors.value.deptId
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    title: form.title.trim(),
    content: form.content,
    scopeType: form.scopeType,
    deptId: form.scopeType === 'dept' ? form.deptId : null,
    status: form.status,
  }
  try {
    if (form.id) {
      await collabApi.announcements.update(form.id, payload)
      pushToast('公告已更新')
    } else {
      await collabApi.announcements.create(payload)
      pushToast(form.status === 'published' ? '公告已发布' : '公告已保存为草稿')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '公告保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: CollabAnnouncementRow) {
  const ok = await confirm({
    title: '删除公告',
    message: `确认删除公告「${row.title}」吗？已读记录会一并失效。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await collabApi.announcements.remove(row.id)
    pushToast('公告已删除')
    await list.load()
  } catch (error) {
    toastError(error, '公告删除失败')
  }
}

/* ── 详情：打开即标记已读（状态由后端记录） ── */
const detailOpen = ref(false)
const detail = ref<CollabAnnouncementRow | null>(null)
const detailLoading = ref(false)

async function openDetail(row: CollabAnnouncementRow) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await collabApi.announcements.detail(row.id)
    if (!detail.value.read) {
      await collabApi.announcements.read(row.id)
      await list.load()
    }
  } catch (error) {
    toastError(error, '公告详情加载失败')
  } finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <ListPage
    title="公告"
    desc="面向全员或指定部门的内部通告。全员公告所有同事可见，部门公告仅数据范围覆盖该部门的同事可见，可见性由平台权限规则判定。"
    table-title="公告列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="collab:notice:add" variant="primary" icon="plus" @click="openCreate">发布公告</PermissionButton>
    </template>

    <template #search>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...ANNOUNCEMENT_STATUS_OPTIONS]" />
      </Field>
    </template>

    <template #cell-title="{ row }">
      <span class="cell-title">{{ row.title }}</span>
      <span class="cell-sub ellipsis">{{ row.content }}</span>
    </template>
    <template #cell-scope="{ row }">
      <Tag :tone="row.scopeType === 'all' ? 'info' : 'neutral'">{{ row.scopeLabel || (row.scopeType === 'all' ? '全员' : '部门') }}</Tag>
      <span v-if="row.scopeType === 'dept'" class="faint" style="font-size: 11.5px; margin-left: 6px">{{ row.deptName || '—' }}</span>
    </template>
    <template #cell-publisherName="{ row }">
      <span>{{ row.publisherName || '—' }}</span>
    </template>
    <template #cell-publishTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.publishTime) }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="ANNOUNCEMENT_STATUS_TONE[row.status || ''] || 'neutral'" dot>
        {{ row.statusLabel || ANNOUNCEMENT_STATUS_TEXT[row.status || ''] || row.status }}
      </Tag>
    </template>
    <template #cell-read="{ row }">
      <Tag :tone="row.read ? 'ok' : 'neutral'">{{ row.read ? '已读' : '未读' }}</Tag>
      <span v-if="row.readTime" class="faint tnum" style="font-size: 11px; margin-left: 6px">{{ dateTimeText(row.readTime) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="collab:notice:list" icon-only icon="eye" label="查看公告" @click="openDetail(row)" />
        <PermissionButton perm="collab:notice:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
        <PermissionButton perm="collab:notice:remove" icon-only icon="trash" label="删除" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑公告' : '发布公告'"
      sub="全员公告所有同事可见；部门公告只对数据范围覆盖该部门的同事可见"
      :width="760"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="公告标题" required :error="errors.title" span>
        <TextInput v-model="form.title" placeholder="如：本周五系统维护通知" :invalid="!!errors.title" :maxlength="100" />
      </Field>
      <Field label="可见范围" group>
        <Segmented :model-value="form.scopeType" label="可见范围" :options="SCOPE_OPTIONS" @update:model-value="pickScope" />
      </Field>
      <Field label="状态">
        <Select :model-value="form.status" :options="ANNOUNCEMENT_STATUS_OPTIONS" @update:model-value="pickStatus" />
      </Field>
      <Field v-if="form.scopeType === 'dept'" label="指定部门" required :error="errors.deptId" span>
        <DeptSelect v-model="form.deptId" :invalid="!!errors.deptId" />
      </Field>
      <Field label="公告正文" required :error="errors.content" span>
        <TextInput v-model="form.content" multiline :rows="6" placeholder="写清事项、影响范围与时间安排" :invalid="!!errors.content" :maxlength="4000" />
      </Field>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        草稿只有自己（及数据范围管理员）能看到；状态改为「已发布」后立即对可见范围的同事生效。
      </p>
    </FormDialog>

    <Drawer :open="detailOpen" title="公告详情" :sub="detail?.title || ''" :width="760" @close="detailOpen = false">
      <div v-if="detail" class="stack">
        <SpecPlate
          title="公告信息"
          :rows="[
            { label: '可见范围', value: detail.scopeType === 'dept' ? `部门：${detail.deptName || '—'}` : '全员' },
            { label: '状态', value: detail.statusLabel || ANNOUNCEMENT_STATUS_TEXT[detail.status || ''] },
            { label: '发布人', value: detail.publisherName },
            { label: '发布时间', value: dateTimeText(detail.publishTime) },
            { label: '创建时间', value: dateTimeText(detail.createTime) },
            { label: '阅读状态', value: detail.read ? `已读（${dateTimeText(detail.readTime)}）` : '未读' },
          ]"
        />
        <div>
          <SectionLabel title="公告正文" />
          <p style="white-space: pre-wrap; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">{{ detail.content }}</p>
        </div>
        <p class="faint" style="font-size: 12px">
          打开详情即视为已读，已读状态由服务端记录；部门公告的可见范围由平台权限规则判定，不在页面内过滤。
        </p>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>
  </ListPage>
</template>
