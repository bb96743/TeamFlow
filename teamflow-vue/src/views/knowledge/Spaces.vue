<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button,
  DataTable,
  Drawer,
  Field,
  Select,
  SectionLabel,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import {
  knowledgeApi,
  type KnowledgeNodeRow,
  type KnowledgePageRow,
  type KnowledgeSpaceRow,
  type KnowledgeTree,
} from '@/api/knowledge'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { pathOf } from '@/composables/auth'
import { validate, required, pattern } from '@/utils/validate'
import {
  PAGE_STATUS_OPTIONS,
  PAGE_TYPE_OPTIONS,
  SPACE_STATUS_OPTIONS,
  SPACE_VISIBILITY_OPTIONS,
  dateTimeText,
  nodeSelectOptions,
  pageStatusLabel,
  pageStatusTone,
  pageTypeLabel,
  visibilityLabel,
} from './support'

/* ============================================================
   知识空间（M07 · 1.2.1）
   ------------------------------------------------------------
   三层结构「空间 → 目录 → 页面」：左侧是当前空间的目录/页面树，右侧是可筛选的页面列表。
   可见性与可编辑性完全由后端按权限链计算（树/页面已被后端过滤；详情返回 access.reason），
   页面只做展示与操作入口，不自行推断权限。
   ============================================================ */

const router = useRouter()

/* ── 空间 / 目录树 ── */
const spaces = ref<KnowledgeSpaceRow[]>([])
const spaceId = ref<number | null>(null)
const nodeId = ref<number | null>(null)
const spaceDetail = ref<KnowledgeSpaceRow | null>(null)
const tree = ref<KnowledgeTree | null>(null)
const treeLoading = ref(false)

const spaceOptions = computed(() => spaces.value.map(space => ({
  label: space.spaceName,
  value: space.id,
  hint: `${space.nodeCount ?? 0} 个目录 · ${space.pageCount ?? 0} 个页面`,
})))

interface TreeRow {
  key: string
  name: string
  kind: 'node' | 'page'
  node?: KnowledgeNodeRow
  page?: KnowledgePageRow
  children?: TreeRow[]
}

function toPageRow(page: KnowledgePageRow): TreeRow {
  return { key: `p-${page.id}`, name: page.pageTitle, kind: 'page', page }
}

function toNodeRow(node: KnowledgeNodeRow): TreeRow {
  const childNodes = (node.children ?? []).map(toNodeRow)
  const childPages = (node.pages ?? []).map(toPageRow)
  return {
    key: `n-${node.id}`,
    name: node.nodeName,
    kind: 'node',
    node,
    children: [...childNodes, ...childPages],
  }
}

const treeRows = computed<TreeRow[]>(() => {
  if (!tree.value) return []
  return [...tree.value.nodes.map(toNodeRow), ...(tree.value.pages ?? []).map(toPageRow)]
})

const treeColumns: Column[] = [{ key: 'name', title: '目录与页面', minWidth: 240 }]

/* ── 页面列表：服务端已按权限过滤，前端只筛选与展示 ── */
const list = useListPage<KnowledgePageRow, {
  spaceId: number | null
  nodeId: number | null
  keyword: string
  pageType: string
  status: string
}>({
  query: { spaceId: null, nodeId: null, keyword: '', pageType: '', status: '' },
  fetch: params => knowledgeApi.pages.list(params),
  pageSize: 10,
  immediate: false,
})

const columns: Column[] = [
  { key: 'pageTitle', title: '页面', minWidth: 240 },
  { key: 'pageType', title: '类型', width: 108 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'currentVersion', title: '版本', width: 84, align: 'right' },
  { key: 'ownerName', title: '负责人', width: 110 },
  { key: 'updateTime', title: '最近更新', width: 150 },
  { key: 'actions', title: '操作', width: 120, className: 'col-actions' },
]

/* ── 加载 ── */
async function loadSpaces() {
  try {
    const page = await knowledgeApi.spaces.list({ pageNum: 1, pageSize: 100 })
    spaces.value = page.rows
    const exists = page.rows.some(space => space.id === spaceId.value)
    const target = exists ? spaceId.value : (page.rows[0]?.id ?? null)
    if (target !== null) {
      await pickSpace(target)
    } else {
      spaceId.value = null
      tree.value = null
      spaceDetail.value = null
      list.rows.value = []
      list.total.value = 0
    }
  } catch (error) {
    toastError(error, '知识空间加载失败')
  }
}

async function pickSpace(value: string | number | null | undefined) {
  const id = value === null || value === undefined || value === '' ? null : Number(value)
  if (id === spaceId.value) return
  spaceId.value = id
  nodeId.value = null
  list.query.spaceId = id
  list.query.nodeId = null
  await Promise.all([loadTree(), loadSpaceDetail(), list.search()])
}

async function loadTree() {
  if (spaceId.value === null) {
    tree.value = null
    return
  }
  treeLoading.value = true
  try {
    tree.value = await knowledgeApi.spaces.tree(spaceId.value)
  } catch (error) {
    tree.value = null
    toastError(error, '目录树加载失败')
  } finally {
    treeLoading.value = false
  }
}

async function loadSpaceDetail() {
  if (spaceId.value === null) {
    spaceDetail.value = null
    return
  }
  try {
    spaceDetail.value = await knowledgeApi.spaces.detail(spaceId.value)
  } catch (error) {
    spaceDetail.value = null
    toastError(error, '空间详情加载失败')
  }
}

function selectNode(value: number | null) {
  nodeId.value = value
  list.query.nodeId = value
  list.search()
}

function onTreeRowClick(row?: TreeRow | null) {
  if (!row) return
  if (row.kind === 'page' && row.page) {
    openPreview(row.page)
  } else if (row.kind === 'node' && row.node) {
    selectNode(row.node.id)
  }
}

async function refreshAll() {
  await loadSpaces()
  pushToast('知识空间已刷新')
}

onMounted(loadSpaces)

/* ── 页面预览 ── */
const previewOpen = ref(false)
const previewLoading = ref(false)
const preview = ref<KnowledgePageRow | null>(null)

async function openPreview(row: KnowledgePageRow) {
  previewOpen.value = true
  previewLoading.value = true
  preview.value = null
  try {
    preview.value = await knowledgeApi.pages.detail(row.id)
  } catch (error) {
    toastError(error, '页面详情加载失败')
    previewOpen.value = false
  } finally {
    previewLoading.value = false
  }
}

function editorPath(options: { pageId?: number; spaceId?: number | null; nodeId?: number | null }) {
  const base = pathOf('knowledge/page/index') ?? '/knowledge/page'
  const query: Record<string, string> = {}
  if (options.pageId !== undefined) query.pageId = String(options.pageId)
  if (options.spaceId !== undefined && options.spaceId !== null) query.spaceId = String(options.spaceId)
  if (options.nodeId !== undefined && options.nodeId !== null) query.nodeId = String(options.nodeId)
  return { path: base, query }
}

function goEditor(options: { pageId?: number; spaceId?: number | null; nodeId?: number | null }) {
  previewOpen.value = false
  router.push(editorPath(options))
}

function createPage() {
  router.push(editorPath({ spaceId: spaceId.value, nodeId: nodeId.value }))
}

/* ── 空间表单 ── */
interface SpaceForm {
  id?: number
  spaceName: string
  spaceCode: string
  spaceDesc: string
  visibility: string
  status: string
  sort: number | null
  remark: string
}

const emptySpace = (): SpaceForm => ({
  spaceName: '',
  spaceCode: '',
  spaceDesc: '',
  visibility: 'internal',
  status: 'active',
  sort: 10,
  remark: '',
})

const spaceDialog = ref(false)
const spaceSaving = ref(false)
const spaceForm = reactive<SpaceForm>(emptySpace())
const spaceErrors = ref<Partial<Record<keyof SpaceForm, string>>>({})

function openSpaceCreate() {
  Object.assign(spaceForm, emptySpace(), { id: undefined })
  spaceErrors.value = {}
  spaceDialog.value = true
}

function openSpaceEdit() {
  const space = spaceDetail.value
  if (!space) return
  Object.assign(spaceForm, {
    id: space.id,
    spaceName: space.spaceName,
    spaceCode: space.spaceCode,
    spaceDesc: space.spaceDesc ?? '',
    visibility: space.visibility,
    status: space.status,
    sort: space.sort ?? 0,
    remark: space.remark ?? '',
  })
  spaceErrors.value = {}
  spaceDialog.value = true
}

async function submitSpace() {
  spaceErrors.value = validate(spaceForm, {
    spaceName: [required('请填写空间名称')],
    spaceCode: spaceForm.id ? [] : [required('请填写空间编码'), pattern(/^[A-Za-z][A-Za-z0-9_-]{1,63}$/, '字母开头，仅含字母、数字、下划线、短横线')],
  })
  if (Object.keys(spaceErrors.value).length) return
  spaceSaving.value = true
  const payload = {
    spaceName: spaceForm.spaceName.trim(),
    spaceCode: spaceForm.spaceCode.trim(),
    spaceDesc: spaceForm.spaceDesc.trim() || null,
    visibility: spaceForm.visibility,
    status: spaceForm.status,
    sort: spaceForm.sort ?? 0,
    remark: spaceForm.remark.trim() || null,
  }
  try {
    let createdId: number | null = null
    if (spaceForm.id) {
      await knowledgeApi.spaces.update(spaceForm.id, payload)
      pushToast('知识空间已更新')
    } else {
      const created = await knowledgeApi.spaces.create(payload)
      createdId = created.id
      pushToast('知识空间已创建')
    }
    spaceDialog.value = false
    await loadSpaces()
    if (createdId !== null) await pickSpace(createdId)
    await loadSpaceDetail()
  } catch (error) {
    toastError(error, '知识空间保存失败')
  } finally {
    spaceSaving.value = false
  }
}

async function removeSpace() {
  const space = spaceDetail.value
  if (!space) return
  const ok = await confirm({
    title: '删除知识空间',
    message: `确认删除空间「${space.spaceName}」吗？空间下仍有目录或页面时不能删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await knowledgeApi.spaces.remove(space.id)
    pushToast('知识空间已删除')
    spaceId.value = null
    await loadSpaces()
  } catch (error) {
    toastError(error, '知识空间删除失败')
  }
}

/* ── 目录表单 ── */
interface NodeForm {
  id?: number
  parentId: number | null
  nodeName: string
  nodeDesc: string
  visibility: string
  sort: number | null
  remark: string
}

const emptyNode = (): NodeForm => ({
  parentId: 0,
  nodeName: '',
  nodeDesc: '',
  visibility: 'inherit',
  sort: 10,
  remark: '',
})

const nodeDialog = ref(false)
const nodeSaving = ref(false)
const nodeForm = reactive<NodeForm>(emptyNode())
const nodeErrors = ref<Partial<Record<keyof NodeForm, string>>>({})
const parentOptions = computed(() => nodeSelectOptions(tree.value?.nodes ?? []))

function openNodeCreate(parent?: number | null) {
  Object.assign(nodeForm, emptyNode(), {
    id: undefined,
    parentId: parent ?? nodeId.value ?? 0,
  })
  nodeErrors.value = {}
  nodeDialog.value = true
}

function openNodeEdit(node: KnowledgeNodeRow) {
  Object.assign(nodeForm, {
    id: node.id,
    parentId: node.parentId ?? 0,
    nodeName: node.nodeName,
    nodeDesc: node.nodeDesc ?? '',
    visibility: node.visibility,
    sort: node.sort ?? 0,
    remark: node.remark ?? '',
  })
  nodeErrors.value = {}
  nodeDialog.value = true
}

async function submitNode() {
  if (!spaceId.value) {
    pushToast('请先选择知识空间', 'danger')
    return
  }
  nodeErrors.value = validate(nodeForm, { nodeName: [required('请填写目录名称')] })
  if (Object.keys(nodeErrors.value).length) return
  nodeSaving.value = true
  const payload = {
    spaceId: spaceId.value,
    parentId: nodeForm.id ? undefined : (nodeForm.parentId ?? 0),
    nodeName: nodeForm.nodeName.trim(),
    nodeDesc: nodeForm.nodeDesc.trim() || null,
    visibility: nodeForm.visibility,
    sort: nodeForm.sort ?? 0,
    remark: nodeForm.remark.trim() || null,
  }
  try {
    if (nodeForm.id) {
      await knowledgeApi.nodes.update(nodeForm.id, payload)
      pushToast('目录已更新')
    } else {
      await knowledgeApi.nodes.create(payload)
      pushToast('目录已创建')
    }
    nodeDialog.value = false
    await Promise.all([loadTree(), loadSpaceDetail(), list.load()])
  } catch (error) {
    toastError(error, '目录保存失败')
  } finally {
    nodeSaving.value = false
  }
}

function editTreeNode(row: TreeRow) {
  if (row.node) openNodeEdit(row.node)
}

function removeTreeNode(row: TreeRow) {
  if (row.node) removeNode(row.node)
}

async function removeNode(node: KnowledgeNodeRow) {
  const ok = await confirm({
    title: '删除目录',
    message: `确认删除目录「${node.nodeName}」吗？目录下仍有子目录或页面时不能删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await knowledgeApi.nodes.remove(node.id)
    pushToast('目录已删除')
    if (nodeId.value === node.id) selectNode(null)
    await Promise.all([loadTree(), loadSpaceDetail()])
  } catch (error) {
    toastError(error, '目录删除失败')
  }
}

/* ── 页面操作 ── */
async function removePage(row: KnowledgePageRow) {
  const ok = await confirm({
    title: '删除知识页面',
    message: `确认删除页面「${row.pageTitle}」吗？页面删除后保留历史版本，但不再出现在知识库中。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await knowledgeApi.pages.remove(row.id)
    pushToast('知识页面已删除')
    await Promise.all([list.load(), loadTree(), loadSpaceDetail()])
  } catch (error) {
    toastError(error, '页面删除失败')
  }
}

const currentSpaceLabel = computed(() => spaceDetail.value?.spaceName ?? '未选择空间')
</script>

<template>
  <ListPage
    title="知识空间"
    desc="按「空间 → 目录 → 页面」组织团队知识：左侧目录树反映当前可见结构，右侧是可筛选的页面列表；可见性与可编辑性由后端按权限链计算。"
    table-title="页面列表"
    :list="list"
    :columns="columns"
    :min-width="1000"
    row-key="id"
  >
    <template #actions>
      <div style="width: 220px">
        <Select
          :model-value="spaceId"
          :options="spaceOptions"
          placeholder="选择知识空间"
          filterable
          @update:model-value="pickSpace"
        />
      </div>
      <PermissionButton perm="knowledge:space:add" icon="plus" @click="openSpaceCreate">新建空间</PermissionButton>
      <Button variant="secondary" icon="refresh" :loading="treeLoading" @click="refreshAll">刷新</Button>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="页面标题 / 标签" clearable @enter="list.search()" />
      </Field>
      <Field label="页面类型">
        <Select v-model="list.query.pageType" :options="[{ label: '全部类型', value: '' }, ...PAGE_TYPE_OPTIONS]" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...PAGE_STATUS_OPTIONS]" />
      </Field>
    </template>

    <template #side>
      <div class="split-side-head">
        <SectionLabel title="目录与页面" />
        <span class="cell-sub">{{ currentSpaceLabel }} · {{ tree?.nodeCount ?? 0 }} 个目录 · {{ tree?.pageCount ?? 0 }} 个页面</span>
      </div>
      <div style="padding: 0 6px 10px; display: flex; flex-direction: column; gap: 6px">
        <Button variant="text" size="sm" icon="layers" @click="selectNode(null)">查看全部页面</Button>
        <div style="display: flex; gap: 6px; flex-wrap: wrap">
          <PermissionButton perm="knowledge:node:add" variant="text" size="sm" icon="plus" @click="openNodeCreate()">新建目录</PermissionButton>
          <PermissionButton perm="knowledge:page:add" variant="text" size="sm" icon="file" @click="createPage">新建页面</PermissionButton>
        </div>
        <div v-if="spaceDetail?.access" style="padding: 2px 4px">
          <Tag :tone="spaceDetail.access.canManage ? 'ok' : spaceDetail.access.canWrite ? 'info' : 'neutral'" dot>
            {{ spaceDetail.access.levelLabel }}
          </Tag>
          <p class="cell-sub" style="margin-top: 4px; line-height: 1.7">{{ spaceDetail.access.reason }}</p>
        </div>
        <div v-if="spaceDetail?.access?.canManage" style="display: flex; gap: 6px; flex-wrap: wrap">
          <PermissionButton perm="knowledge:space:edit" variant="text" size="sm" icon="edit" @click="openSpaceEdit">编辑空间</PermissionButton>
          <PermissionButton perm="knowledge:space:remove" variant="text" size="sm" icon="trash" danger @click="removeSpace">删除空间</PermissionButton>
        </div>
      </div>
      <DataTable
        :columns="treeColumns"
        :data="treeRows"
        row-key="key"
        tree
        :loading="treeLoading"
        :min-width="0"
        empty-text="该空间下暂无目录与页面"
        @row-click="onTreeRowClick"
      >
        <template #cell-name="{ row }">
          <div style="display: flex; align-items: center; gap: 6px; min-width: 0; width: 100%">
            <span class="cell-title ellipsis" style="flex: 1">{{ row.name }}</span>
            <Tag v-if="row.kind === 'page'" tone="neutral">{{ pageTypeLabel(row.page?.pageType) }}</Tag>
            <Tag v-else tone="info">目录</Tag>
            <template v-if="row.kind === 'node'">
              <PermissionButton perm="knowledge:node:edit" icon-only icon="edit" label="编辑目录" @click.stop="editTreeNode(row)" />
              <PermissionButton perm="knowledge:node:remove" icon-only icon="trash" label="删除目录" danger @click.stop="removeTreeNode(row)" />
            </template>
          </div>
        </template>
      </DataTable>
      <p v-if="!spaces.length" class="faint" style="padding: 0 10px; font-size: 12px; line-height: 1.8">
        当前账号还没有可见的知识空间；如有「知识空间新增」权限可先新建空间。
      </p>
    </template>

    <template #cell-pageTitle="{ row }">
      <div>
        <div class="cell-title">{{ row.pageTitle }}</div>
        <div class="cell-sub">
          {{ row.nodeName || '空间根' }}<template v-if="row.tags"> · {{ row.tags }}</template>
        </div>
      </div>
    </template>
    <template #cell-pageType="{ row }">
      <Tag tone="neutral">{{ pageTypeLabel(row.pageType) }}</Tag>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="pageStatusTone(row.status)" dot>{{ pageStatusLabel(row.status) }}</Tag>
    </template>
    <template #cell-currentVersion="{ row }"><span class="tnum">v{{ row.currentVersion }}</span></template>
    <template #cell-ownerName="{ row }"><span class="muted">{{ row.ownerName || '—' }}</span></template>
    <template #cell-updateTime="{ row }"><span class="tnum soft">{{ dateTimeText(row.updateTime) }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="knowledge:page:list" icon-only icon="eye" label="查看页面" @click="openPreview(row)" />
        <PermissionButton perm="knowledge:page:edit" icon-only icon="edit" label="编辑页面" @click="goEditor({ pageId: row.id })" />
        <PermissionButton perm="knowledge:page:remove" icon-only icon="trash" label="删除页面" danger @click="removePage(row)" />
      </div>
    </template>

    <!-- 页面预览（只读；正文按 Markdown 原文展示，不做富文本渲染） -->
    <Drawer
      :open="previewOpen"
      :title="preview?.pageTitle || '页面预览'"
      :sub="preview ? `${pageTypeLabel(preview.pageType)} · ${preview.spaceName || ''}${preview.nodeName ? ' / ' + preview.nodeName : ''}` : ''"
      :width="720"
      @close="previewOpen = false"
    >
      <div v-if="previewLoading" class="faint">正在加载页面…</div>
      <div v-else-if="preview" style="display: flex; flex-direction: column; gap: 16px">
        <div style="display: flex; gap: 8px; flex-wrap: wrap">
          <Tag :tone="pageStatusTone(preview.status)" dot>{{ pageStatusLabel(preview.status) }}</Tag>
          <Tag tone="neutral">{{ pageTypeLabel(preview.pageType) }}</Tag>
          <Tag tone="info">当前版本 v{{ preview.currentVersion }}</Tag>
          <Tag tone="neutral">{{ visibilityLabel(preview.visibility) }}</Tag>
        </div>
        <dl class="kv-grid">
          <div class="kv"><div class="kv-label">归属</div><div class="kv-value">{{ preview.spaceName || '—' }} / {{ preview.nodeName || '空间根' }}</div></div>
          <div class="kv"><div class="kv-label">负责人 / 版本</div><div class="kv-value">{{ preview.ownerName || '—' }} · v{{ preview.currentVersion }}</div></div>
          <div class="kv"><div class="kv-label">最近更新</div><div class="kv-value tnum">{{ dateTimeText(preview.updateTime) }}</div></div>
          <div class="kv"><div class="kv-label">标签</div><div class="kv-value">{{ preview.tags || '—' }}</div></div>
        </dl>
        <div v-if="preview.summary">
          <SectionLabel title="摘要" />
          <p class="soft">{{ preview.summary }}</p>
        </div>
        <div v-if="preview.access">
          <SectionLabel title="当前权限" />
          <p class="cell-sub" style="line-height: 1.8">{{ preview.access.levelLabel }} · {{ preview.access.reason }}</p>
        </div>
        <div>
          <SectionLabel title="正文（Markdown 原文）" />
          <pre class="code-block" style="white-space: pre-wrap">{{ preview.content || '（空）' }}</pre>
          <p class="faint" style="font-size: 12px; line-height: 1.8">
            正文统一按 Markdown 存储；此处为纯文本预览，富文本渲染由后续前端能力提供。
          </p>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap">
          <PermissionButton perm="knowledge:page:edit" variant="primary" icon="edit" @click="goEditor({ pageId: preview.id })">编辑页面（含版本与附件）</PermissionButton>
        </div>
      </div>
    </Drawer>

    <!-- 空间表单 -->
    <FormDialog
      :open="spaceDialog"
      :title="spaceForm.id ? '编辑知识空间' : '新建知识空间'"
      :sub="spaceForm.id ? '空间编码不可修改；可见性变更只影响之后按权限链推导的结果' : '空间是知识库的第一层，页面可见性默认继承所属空间'"
      :saving="spaceSaving"
      @close="spaceDialog = false"
      @submit="submitSpace"
    >
      <Field label="空间名称" required :error="spaceErrors.spaceName">
        <TextInput v-model="spaceForm.spaceName" placeholder="如：研发知识库" :invalid="!!spaceErrors.spaceName" />
      </Field>
      <Field label="空间编码" required :error="spaceErrors.spaceCode" hint="唯一标识，创建后不可修改">
        <TextInput v-model="spaceForm.spaceCode" placeholder="如：rd-wiki" :disabled="!!spaceForm.id" :invalid="!!spaceErrors.spaceCode" />
      </Field>
      <Field label="默认可见性" hint="目录/页面可单独覆盖">
        <Select v-model="spaceForm.visibility" :options="SPACE_VISIBILITY_OPTIONS" />
      </Field>
      <Field label="空间状态">
        <Select v-model="spaceForm.status" :options="SPACE_STATUS_OPTIONS" />
      </Field>
      <Field label="说明" span>
        <TextInput v-model="spaceForm.spaceDesc" multiline :rows="2" placeholder="这个空间放什么内容" :maxlength="500" />
      </Field>
      <Field label="排序">
        <TextInput v-model="spaceForm.sort" type="number" />
      </Field>
      <Field label="备注">
        <TextInput v-model="spaceForm.remark" placeholder="选填" />
      </Field>
    </FormDialog>

    <!-- 目录表单 -->
    <FormDialog
      :open="nodeDialog"
      :title="nodeForm.id ? '编辑目录' : '新建目录'"
      :sub="nodeForm.id ? '目录层级移动暂不支持；需要调整请新建目录并移动页面' : '目录用于组织页面，可见性默认继承上级'"
      :saving="nodeSaving"
      @close="nodeDialog = false"
      @submit="submitNode"
    >
      <Field v-if="!nodeForm.id" label="上级目录" span>
        <Select v-model="nodeForm.parentId" :options="parentOptions" filterable />
      </Field>
      <Field label="目录名称" required :error="nodeErrors.nodeName">
        <TextInput v-model="nodeForm.nodeName" placeholder="如：接口规范" :invalid="!!nodeErrors.nodeName" />
      </Field>
      <Field label="可见性">
        <Select v-model="nodeForm.visibility" :options="[
          { label: '继承上级（默认）', value: 'inherit' },
          { label: '已登录可读（覆盖）', value: 'internal' },
          { label: '仅授权可见（覆盖）', value: 'restricted' },
        ]" />
      </Field>
      <Field label="排序">
        <TextInput v-model="nodeForm.sort" type="number" />
      </Field>
      <Field label="说明" span>
        <TextInput v-model="nodeForm.nodeDesc" multiline :rows="2" placeholder="目录用途说明" :maxlength="500" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="nodeForm.remark" placeholder="选填" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
