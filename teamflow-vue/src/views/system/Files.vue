<script setup lang="ts">
import { ref } from 'vue'
import { Field, TextInput, Tag, type Column } from '@/components/ui'
import { ListPage, PermissionButton } from '@/components/business'
import Icon from '@/components/Icon.vue'
import { httpClient, type PageResult } from '@/api'
import { authSession } from '@/api'
import { downloadBlob } from '@/utils/download'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'

/* ============================================================
   /system/file 文件管理（M5-7）：G-1 统一文件列表。
   · 列表：分页 / 按原始名模糊搜 / 按业务类型筛选（后端 GET /api/system/files）
   · 上传：multipart 走 /api/system/files，可选 bizType / bizId
   · 下载：必须走鉴权接口（httpClient.download → Blob），不拼裸 URL
   · 删除：物理删除；refCount>0 时后端拒绝，本页前置提示并阻止请求
   ============================================================ */

/** 与后端 GET /api/system/files 的 rows 字段保持一致（FileService#page） */
interface FileRow {
  id: number
  originalName: string
  url: string
  contentType: string | null
  size: number
  sizeText: string
  bizType: string | null
  bizId: number | null
  refCount: number
  storageProvider: string | null
  createBy: string | null
  createTime: string | null
}

const list = useListPage<FileRow, { keyword: string; bizType: string }>({
  query: { keyword: '', bizType: '' },
  fetch: params => httpClient.get<PageResult<FileRow>>('/system/files', params),
})

const columns: Column[] = [
  { key: 'originalName', title: '文件名', minWidth: 280 },
  { key: 'sizeText', title: '大小', width: 100 },
  { key: 'contentType', title: 'MIME 类型', width: 180 },
  { key: 'biz', title: '业务归属', width: 170 },
  { key: 'refCount', title: '引用数', width: 90 },
  { key: 'createBy', title: '上传人', width: 120 },
  { key: 'createTime', title: '上传时间', width: 160 },
  { key: 'actions', title: '操作', width: 120, className: 'col-actions' },
]

/* ── 上传（multipart，可选 bizType / bizId） ── */
const FILE_MAX_BYTES = 20 * 1024 * 1024
const uploading = ref(false)
const uploadBizType = ref('')
const uploadBizId = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const bizIdPattern = /^\d+$/

function pickFile() {
  fileInput.value?.click()
}

function apiBase() {
  const env = (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env
  return (env?.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 允许再次选择同一个文件
  input.value = ''
  if (!file) return
  if (file.size > FILE_MAX_BYTES) {
    pushToast('单个文件不能超过 20MB', 'danger')
    return
  }
  const bizType = uploadBizType.value.trim()
  const bizId = uploadBizId.value.trim()
  if (bizId && !bizIdPattern.test(bizId)) {
    pushToast('业务 ID 必须是数字', 'danger')
    return
  }

  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    if (bizType) form.append('bizType', bizType)
    if (bizId) form.append('bizId', bizId)
    const token = authSession.token()
    const response = await fetch(`${apiBase()}/system/files`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    })
    const payload = await response.json().catch(() => ({ code: response.status, message: response.statusText }))
    if (!response.ok || payload.code !== 200) {
      throw new Error(payload.message || '上传失败')
    }
    pushToast(`已上传「${file.name}」`)
    await list.load()
  } catch (error) {
    toastError(error, '上传失败')
  } finally {
    uploading.value = false
  }
}

/* ── 下载：鉴权 Blob，不直接拼 /api/system/files/{id} 裸链 ── */
async function download(row: FileRow) {
  try {
    const blob = await httpClient.download(`/system/files/${row.id}`)
    downloadBlob(blob, row.originalName)
    pushToast(`已开始下载「${row.originalName}」`, 'info')
  } catch (error) {
    toastError(error, '下载失败')
  }
}

/* ── 删除：物理删除；被引用时前端前置拦截，后端也会拒绝（双保险） ── */
async function remove(row: FileRow) {
  if (row.refCount > 0) {
    pushToast(`「${row.originalName}」仍被业务引用 ${row.refCount} 次，解除引用后才能删除`, 'danger')
    return
  }
  const confirmed = await confirm({
    title: '删除文件',
    message: `确认删除「${row.originalName}」？文件为物理删除，删除后不可恢复。`,
    confirmText: '删除',
    danger: true,
  })
  if (!confirmed) return
  try {
    await httpClient.delete(`/system/files/${row.id}`)
    pushToast('文件已删除')
    await list.load()
  } catch (error) {
    toastError(error, '删除失败')
  }
}
</script>

<template>
  <ListPage
    title="文件管理"
    desc="平台统一文件存储（G-1）：业务模块只存文件 ID 引用；下载必须经鉴权接口，不暴露静态目录。"
    :list="list"
    :columns="columns"
    table-title="文件列表"
    :min-width="1150"
  >
    <template #actions>
      <TextInput v-model="uploadBizType" placeholder="业务类型（可选，如 task）" clearable style="width: 190px" />
      <TextInput v-model="uploadBizId" placeholder="业务 ID（可选）" clearable style="width: 140px" />
      <PermissionButton perm="system:file:upload" variant="primary" icon="upload" :loading="uploading" @click="pickFile">
        上传文件
      </PermissionButton>
    </template>

    <template #search>
      <Field label="文件名" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="按原始文件名模糊搜索" clearable @enter="list.search()" />
      </Field>
      <Field label="业务类型">
        <TextInput v-model="list.query.bizType" placeholder="精确匹配，如 task / kb_page" clearable @enter="list.search()" />
      </Field>
    </template>

    <template #cell-originalName="{ row }">
      <div class="cell-main">
        <Icon name="file" :size="18" />
        <div style="min-width: 0">
          <div class="cell-title ellipsis">{{ row.originalName }}</div>
          <div class="cell-sub">{{ row.storageProvider === 'local' ? '本地存储' : (row.storageProvider || '—') }}</div>
        </div>
      </div>
    </template>
    <template #cell-sizeText="{ row }"><span class="tnum muted">{{ row.sizeText }}</span></template>
    <template #cell-contentType="{ row }"><span class="muted ellipsis">{{ row.contentType || '—' }}</span></template>
    <template #cell-biz="{ row }">
      <Tag v-if="row.bizType" tone="info">{{ row.bizType }}{{ row.bizId ? ` #${row.bizId}` : '' }}</Tag>
      <span v-else class="faint">未关联业务</span>
    </template>
    <template #cell-refCount="{ row }">
      <Tag :tone="row.refCount > 0 ? 'danger' : 'neutral'">{{ row.refCount }}</Tag>
    </template>
    <template #cell-createBy="{ row }"><span class="muted">{{ row.createBy || '—' }}</span></template>
    <template #cell-createTime="{ row }"><span class="tnum soft">{{ row.createTime || '—' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="system:file:list" icon-only icon="download" label="下载" @click="download(row)" />
        <PermissionButton perm="system:file:remove" icon-only icon="trash" label="删除" danger @click="remove(row)" />
      </div>
    </template>

    <input ref="fileInput" type="file" hidden @change="onFileChange" />
  </ListPage>
</template>
