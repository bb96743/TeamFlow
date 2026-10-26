<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Button,
  Drawer,
  Field,
  PageHeader,
  Panel,
  SectionLabel,
  Segmented,
  Select,
  SpecPlate,
  Tag,
  TextInput,
} from '@/components/ui'
import { PermissionButton } from '@/components/business'
import {
  knowledgeApi,
  type KnowledgeFileRow,
  type KnowledgeNodeRow,
  type KnowledgePageRow,
  type KnowledgeVersionRow,
} from '@/api/knowledge'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { pathOf } from '@/composables/auth'
import { hasPermission } from '@/directives/permission'
import { required, validate } from '@/utils/validate'
import { downloadBlob } from '@/utils/download'
import {
  PAGE_STATUS_OPTIONS,
  PAGE_TYPE_OPTIONS,
  PAGE_VISIBILITY_OPTIONS,
  dateTimeText,
  nodeSelectOptions,
  pageStatusLabel,
  pageStatusTone,
  pageTypeLabel,
  visibilityLabel,
} from './support'

/* ============================================================
   页面编辑（M07 · 1.2.1 / 1.2.2 / 1.2.4 / 1.2.5）
   ------------------------------------------------------------
   · 正文统一 Markdown，只用 textarea 编辑；预览是「原文纯文本降级」，不引入渲染库；
   · 保存即版本化：保存后生成新版本，右侧版本历史可查看历史内容并回滚
     （回滚会生成新版本、不覆盖历史，页面状态回到草稿）；
   · 附件只存平台文件 ID：上传走统一上传接口拿 fileId，再挂到本页面；下载走鉴权接口；
   · 可见性完全由后端权限链计算，页面按 access 字段只读展示与限制保存。
   ============================================================ */

const route = useRoute()
const router = useRouter()

const spaces = ref<Array<{ id: number; spaceName: string }>>([])
const spaceId = ref<number | null>(null)
const nodeId = ref<number | null>(0)
const nodes = ref<KnowledgeNodeRow[]>([])
const pageId = ref<number | null>(null)
const page = ref<KnowledgePageRow | null>(null)
const loading = ref(false)
const saving = ref(false)
const view = ref<'edit' | 'preview'>('edit')

interface PageForm {
  pageTitle: string
  pageType: string
  tags: string
  summary: string
  content: string
  visibility: string
  status: string
  changeSummary: string
}

const emptyForm = (): PageForm => ({
  pageTitle: '',
  pageType: 'wiki',
  tags: '',
  summary: '',
  content: '',
  visibility: 'inherit',
  status: 'published',
  changeSummary: '',
})

const form = reactive<PageForm>(emptyForm())
const errors = ref<Partial<Record<keyof PageForm, string>>>({})

const isEdit = computed(() => pageId.value !== null)
const access = computed(() => page.value?.access)
const canWrite = computed(() => (isEdit.value ? (access.value?.canWrite ?? false) : true))
const savePerm = computed(() => (isEdit.value ? 'knowledge:page:edit' : 'knowledge:page:add'))

const spaceOptions = computed(() => spaces.value.map(space => ({ label: space.spaceName, value: space.id })))
const nodeOptions = computed(() => nodeSelectOptions(nodes.value))

const specRows = computed(() => [
  { label: '归属', value: page.value ? `${page.value.spaceName || '—'} / ${page.value.nodeName || '空间根'}` : '待保存' },
  { label: '状态', value: page.value ? pageStatusLabel(page.value.status) : '新建' },
  { label: '当前版本', value: page.value ? `v${page.value.currentVersion}` : '保存后生成 v1' },
  { label: '可见权限', value: access.value ? `${access.value.levelLabel}` : '保存后由后端判定' },
  { label: '最近更新', value: page.value ? dateTimeText(page.value.updateTime) : '—' },
])

/* ── 加载 ── */
async function loadSpaces() {
  try {
    const result = await knowledgeApi.spaces.list({ pageNum: 1, pageSize: 100 })
    spaces.value = result.rows.map(space => ({ id: space.id, spaceName: space.spaceName }))
  } catch (error) {
    toastError(error, '知识空间加载失败')
  }
}

async function loadNodes() {
  if (spaceId.value === null) {
    nodes.value = []
    return
  }
  try {
    const tree = await knowledgeApi.spaces.tree(spaceId.value)
    nodes.value = tree.nodes
  } catch (error) {
    nodes.value = []
    toastError(error, '目录加载失败')
  }
}

async function loadPage(id: number) {
  loading.value = true
  try {
    const detail = await knowledgeApi.pages.detail(id)
    page.value = detail
    spaceId.value = detail.spaceId
    nodeId.value = detail.nodeId ?? 0
    Object.assign(form, {
      pageTitle: detail.pageTitle,
      pageType: detail.pageType,
      tags: detail.tags ?? '',
      summary: detail.summary ?? '',
      content: detail.content ?? '',
      visibility: detail.visibility,
      status: detail.status,
      changeSummary: '',
    })
    errors.value = {}
    await Promise.all([loadNodes(), loadVersions(), loadFiles()])
  } catch (error) {
    toastError(error, '页面加载失败')
  } finally {
    loading.value = false
  }
}

function resetForCreate() {
  page.value = null
  Object.assign(form, emptyForm())
  errors.value = {}
  const querySpaceId = Number(route.query.spaceId)
  const queryNodeId = Number(route.query.nodeId)
  spaceId.value = Number.isFinite(querySpaceId) && querySpaceId > 0
    ? querySpaceId
    : (spaces.value[0]?.id ?? null)
  nodeId.value = Number.isFinite(queryNodeId) && queryNodeId >= 0 ? queryNodeId : 0
  versions.value = []
  files.value = []
}

async function bootstrap() {
  await loadSpaces()
  const queryPageId = Number(route.query.pageId)
  if (Number.isFinite(queryPageId) && queryPageId > 0) {
    pageId.value = queryPageId
    await loadPage(queryPageId)
  } else {
    pageId.value = null
    resetForCreate()
  }
}

onMounted(bootstrap)

watch(() => route.query.pageId, async (value) => {
  const next = Number(value)
  const target = Number.isFinite(next) && next > 0 ? next : null
  if (target === pageId.value) return
  pageId.value = target
  if (target !== null) {
    await loadPage(target)
  } else {
    resetForCreate()
  }
})

function pickSpace(value: string | number | null | undefined) {
  spaceId.value = value === null || value === undefined || value === '' ? null : Number(value)
}

function pickNode(value: string | number | null | undefined) {
  nodeId.value = value === null || value === undefined || value === '' ? 0 : Number(value)
}

watch(spaceId, () => {
  if (!page.value) {
    nodeId.value = 0
    loadNodes()
  }
})

/* ── 保存 ── */
async function save() {
  errors.value = validate(form, {
    pageTitle: [required('请填写页面标题')],
    content: [required('请填写页面正文')],
  })
  if (Object.keys(errors.value).length) {
    view.value = 'edit'
    return
  }
  if (!spaceId.value) {
    pushToast('请先选择所属知识空间', 'danger')
    return
  }
  saving.value = true
  const payload = {
    spaceId: spaceId.value,
    nodeId: nodeId.value ?? 0,
    pageTitle: form.pageTitle.trim(),
    pageType: form.pageType,
    tags: form.tags.trim() || null,
    summary: form.summary.trim() || null,
    content: form.content,
    contentFormat: 'markdown',
    visibility: form.visibility,
    status: form.status,
    changeSummary: form.changeSummary.trim() || null,
  }
  try {
    if (pageId.value !== null) {
      const result = await knowledgeApi.pages.update(pageId.value, payload)
      pushToast(`已保存，生成新版本 v${result.versionNo}`)
      await loadPage(pageId.value)
    } else {
      const created = await knowledgeApi.pages.create(payload)
      pageId.value = created.id
      const base = pathOf('knowledge/page/index') ?? '/knowledge/page'
      await router.replace({ path: base, query: { pageId: String(created.id) } })
      pushToast('页面已创建，并生成版本 v1')
      await loadPage(created.id)
    }
  } catch (error) {
    toastError(error, '页面保存失败')
  } finally {
    saving.value = false
  }
}

function backToSpaces() {
  const base = pathOf('knowledge/space/index') ?? '/knowledge/space'
  router.push(base)
}

/* ── 版本历史 ── */
const versions = ref<KnowledgeVersionRow[]>([])
const versionLoading = ref(false)
const versionOpen = ref(false)
const versionDetail = ref<KnowledgeVersionRow | null>(null)

async function loadVersions() {
  if (pageId.value === null) {
    versions.value = []
    return
  }
  versionLoading.value = true
  try {
    versions.value = await knowledgeApi.pages.versions(pageId.value)
  } catch (error) {
    versions.value = []
    toastError(error, '版本历史加载失败')
  } finally {
    versionLoading.value = false
  }
}

async function openVersion(version: KnowledgeVersionRow) {
  if (pageId.value === null) return
  versionOpen.value = true
  versionDetail.value = null
  try {
    versionDetail.value = await knowledgeApi.pages.versionDetail(pageId.value, version.versionNo)
  } catch (error) {
    toastError(error, '版本内容加载失败')
  }
}

async function rollback(version: KnowledgeVersionRow) {
  if (pageId.value === null) return
  const ok = await confirm({
    title: '回滚页面版本',
    message: `将正文恢复到 v${version.versionNo}（${version.changeSummary || '无变更说明'}），并生成一个新版本；历史版本会保留，页面状态回到草稿。`,
    confirmText: '回滚',
  })
  if (!ok) return
  try {
    const result = await knowledgeApi.pages.rollback(pageId.value, version.versionNo, {
      changeSummary: `回滚到版本 v${version.versionNo}`,
    })
    versionOpen.value = false
    pushToast(`已回滚到 v${version.versionNo}，生成新版本 v${result.versionNo}`)
    await loadPage(pageId.value)
  } catch (error) {
    toastError(error, '版本回滚失败')
  }
}

/* ── 附件（只存平台文件 ID） ── */
const files = ref<KnowledgeFileRow[]>([])
const fileLoading = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function loadFiles() {
  if (pageId.value === null) {
    files.value = []
    return
  }
  fileLoading.value = true
  try {
    files.value = await knowledgeApi.files.list({ pageId: pageId.value })
  } catch (error) {
    files.value = []
    toastError(error, '附件列表加载失败')
  } finally {
    fileLoading.value = false
  }
}

function chooseFile() {
  fileInput.value?.click()
}

async function onFileChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (pageId.value === null || spaceId.value === null) {
    pushToast('请先保存页面，再挂载附件', 'danger')
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    pushToast('附件不能超过 20 MB', 'danger')
    return
  }
  uploading.value = true
  const targetPageId = pageId.value
  const targetSpaceId = spaceId.value
  try {
    const uploaded = await knowledgeApi.files.upload(file)
    await knowledgeApi.files.attach({
      spaceId: targetSpaceId,
      pageId: targetPageId,
      fileId: uploaded.id,
      fileTitle: uploaded.originalName || file.name,
      category: 'attachment',
    })
    pushToast('附件已挂载到本页面')
    await loadFiles()
  } catch (error) {
    toastError(error, '附件上传或挂载失败')
  } finally {
    uploading.value = false
  }
}

async function downloadFile(row: KnowledgeFileRow) {
  try {
    const blob = await knowledgeApi.files.download(row.id)
    downloadBlob(blob, row.fileTitle)
  } catch (error) {
    toastError(error, '附件下载失败')
  }
}

async function removeFile(row: KnowledgeFileRow) {
  const ok = await confirm({
    title: '移除附件引用',
    message: `确认从本页面移除「${row.fileTitle}」吗？文件本体由平台维护，引用归零后才可清理。`,
    confirmText: '移除',
    danger: true,
  })
  if (!ok) return
  try {
    await knowledgeApi.files.remove(row.id)
    pushToast('附件引用已移除')
    await loadFiles()
  } catch (error) {
    toastError(error, '附件移除失败')
  }
}

/** 挂载附件需要知识库引用权限，上传本身还需要平台统一上传权限（G-1） */
const canUpload = computed(() => hasPermission('knowledge:file:add') && hasPermission('system:file:upload'))
</script>

<template>
  <div class="page">
    <PageHeader
      :title="isEdit ? (page?.pageTitle || '页面编辑') : '新建知识页面'"
      desc="页面正文统一按 Markdown 保存；保存即生成版本，可查看历史并回滚（回滚生成新版本，不覆盖历史）。可见性与可编辑性由后端按权限链判定。"
    >
      <template #spec>
        <SpecPlate title="页面规格" :rows="specRows" />
      </template>
      <Button variant="secondary" icon="layers" @click="backToSpaces">返回知识空间</Button>
      <PermissionButton
        :perm="savePerm"
        variant="primary"
        icon="check"
        :loading="saving"
        :disabled="!canWrite"
        @click="save"
      >保存页面</PermissionButton>
    </PageHeader>

    <div v-if="loading" class="faint">正在加载页面…</div>

    <div v-else class="split">
      <div class="stack">
        <Panel title="页面内容" :meta="isEdit ? `当前版本 v${page?.currentVersion ?? 1}` : '新建页面'">
          <template #tools>
            <Segmented v-model="view" :options="[{ label: '编辑', value: 'edit' }, { label: '预览', value: 'preview' }]" label="编辑或预览" />
          </template>

          <div v-if="view === 'edit'" style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px">
            <Field label="页面标题" span required :error="errors.pageTitle">
              <TextInput v-model="form.pageTitle" placeholder="如：接口设计规范" :invalid="!!errors.pageTitle" :maxlength="200" />
            </Field>
            <Field label="所属空间">
              <Select
                :model-value="spaceId"
                :options="spaceOptions"
                placeholder="选择空间"
                filterable
                :disabled="isEdit"
                @update:model-value="pickSpace"
              />
            </Field>
            <Field label="归属目录">
              <Select
                :model-value="nodeId"
                :options="nodeOptions"
                placeholder="选择目录"
                filterable
                @update:model-value="pickNode"
              />
            </Field>
            <Field label="页面类型" hint="流程说明、决策记录用页面 + 标签表达">
              <Select v-model="form.pageType" :options="PAGE_TYPE_OPTIONS" />
            </Field>
            <Field label="可见性" hint="继承上级即沿用空间/目录的权限链结论">
              <Select v-model="form.visibility" :options="PAGE_VISIBILITY_OPTIONS" />
            </Field>
            <Field label="页面状态">
              <Select v-model="form.status" :options="PAGE_STATUS_OPTIONS" />
            </Field>
            <Field label="标签" hint="逗号分隔，用于流程说明库/决策记录筛选">
              <TextInput v-model="form.tags" placeholder="如：接口规范,流程" :maxlength="500" />
            </Field>
            <Field label="本次变更说明" hint="写入本次版本快照，便于回看">
              <TextInput v-model="form.changeSummary" placeholder="如：补充异常返回码约定" :maxlength="500" />
            </Field>
            <Field label="摘要" span>
              <TextInput v-model="form.summary" multiline :rows="2" placeholder="一句话说明页面内容" :maxlength="500" />
            </Field>
            <Field label="正文（Markdown）" span required :error="errors.content">
              <TextInput
                v-model="form.content"
                multiline
                :rows="18"
                placeholder="使用 Markdown 编写：标题、列表、代码块等"
                :invalid="!!errors.content"
              />
            </Field>
          </div>

          <div v-else style="display: flex; flex-direction: column; gap: 10px">
            <div style="display: flex; gap: 8px; flex-wrap: wrap">
              <Tag tone="neutral">{{ pageTypeLabel(form.pageType) }}</Tag>
              <Tag :tone="pageStatusTone(form.status)" dot>{{ pageStatusLabel(form.status) }}</Tag>
              <Tag tone="neutral">{{ visibilityLabel(form.visibility) }}</Tag>
            </div>
            <pre class="code-block" style="white-space: pre-wrap">{{ form.content || '（正文为空）' }}</pre>
            <p class="faint" style="font-size: 12px; line-height: 1.8">
              预览为 Markdown 原文的纯文本降级展示，不渲染富文本；最终展示由后续前端能力接入。
            </p>
          </div>
        </Panel>

        <Panel v-if="access" title="权限判定" :meta="access.levelLabel">
          <p class="cell-sub" style="line-height: 1.8">{{ access.reason }}</p>
          <p class="faint" style="font-size: 12px; line-height: 1.8">
            空间 → 目录 → 页面逐级继承，子级授权可覆盖父级；无权限时后端拒绝访问，页面不会自行推断。
          </p>
        </Panel>
      </div>

      <aside class="split-side m-surface">
        <div class="split-side-head">
          <SectionLabel title="版本历史" />
          <span class="cell-sub">{{ versions.length }} 个版本 · 保存即留快照</span>
        </div>
        <div v-if="versionLoading" class="faint" style="padding: 0 8px 12px">版本加载中…</div>
        <div v-else-if="!versions.length" class="faint" style="padding: 0 8px 12px; font-size: 12px; line-height: 1.8">
          保存页面后会在这里出现历史版本。
        </div>
        <div v-else style="display: flex; flex-direction: column; gap: 6px; padding: 0 6px 12px">
          <div v-for="version in versions" :key="version.versionNo" style="padding: 8px; border-radius: var(--r-sm); background: var(--surface-solid)">
            <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
              <span class="cell-title">v{{ version.versionNo }}</span>
              <span class="cell-sub tnum">{{ dateTimeText(version.createTime) }}</span>
            </div>
            <p class="cell-sub" style="margin: 4px 0">{{ version.changeSummary || '（无变更说明）' }} · {{ version.editorName || '—' }}</p>
            <div style="display: flex; gap: 4px; flex-wrap: wrap">
              <Button variant="text" size="sm" icon="eye" @click="openVersion(version)">查看</Button>
              <PermissionButton
                perm="knowledge:page:edit"
                variant="text"
                size="sm"
                icon="refresh"
                :disabled="!canWrite"
                @click="rollback(version)"
              >回滚</PermissionButton>
            </div>
          </div>
          <p class="faint" style="font-size: 12px; line-height: 1.8">
            回滚会用历史内容生成一个新版本，历史版本不会被覆盖；回滚后页面状态为草稿，便于复核后再发布。
          </p>
        </div>

        <div class="split-side-head" style="margin-top: 6px">
          <SectionLabel title="附件" />
          <span class="cell-sub">只保存平台文件 ID</span>
        </div>
        <div style="padding: 0 8px 12px">
          <input ref="fileInput" type="file" hidden @change="onFileChosen" />
          <div v-if="!isEdit" class="faint" style="font-size: 12px; line-height: 1.8; margin-bottom: 10px">
            先保存页面，再上传并挂载附件。
          </div>
          <div v-if="fileLoading" class="faint" style="font-size: 12px">附件加载中…</div>
          <div v-else-if="!files.length" class="faint" style="font-size: 12px; line-height: 1.8">暂无附件。</div>
          <div v-else style="display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px">
            <div v-for="file in files" :key="file.id" style="padding: 8px; border-radius: var(--r-sm); background: var(--surface-solid)">
              <div class="cell-title ellipsis" :title="file.fileTitle">{{ file.fileTitle }}</div>
              <div class="cell-sub">{{ file.category }} · {{ file.createBy || '—' }} · {{ dateTimeText(file.createTime) }}</div>
              <div style="display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px">
                <PermissionButton perm="knowledge:file:list" variant="text" size="sm" icon="download" @click="downloadFile(file)">下载</PermissionButton>
                <PermissionButton perm="knowledge:file:remove" variant="text" size="sm" icon="trash" danger @click="removeFile(file)">移除</PermissionButton>
              </div>
            </div>
          </div>
          <Button v-if="canUpload" variant="secondary" size="sm" icon="upload" :loading="uploading" :disabled="!isEdit" @click="chooseFile">
            上传并挂载附件
          </Button>
          <p class="faint" style="font-size: 12px; line-height: 1.8; margin-top: 8px">
            上传走平台统一上传接口（单文件 ≤ 20 MB），知识库只保存文件 ID；下载经鉴权接口，不暴露静态地址。
          </p>
        </div>
      </aside>
    </div>

    <!-- 历史版本内容 -->
    <Drawer
      :open="versionOpen"
      :title="versionDetail ? `版本 v${versionDetail.versionNo}` : '历史版本'"
      :sub="versionDetail ? `${versionDetail.editorName || '—'} · ${dateTimeText(versionDetail.createTime)} · ${versionDetail.changeSummary || '无变更说明'}` : ''"
      :width="720"
      @close="versionOpen = false"
    >
      <div v-if="!versionDetail" class="faint">版本内容加载中…</div>
      <div v-else style="display: flex; flex-direction: column; gap: 12px">
        <div style="display: flex; gap: 8px; flex-wrap: wrap">
          <Tag tone="info">v{{ versionDetail.versionNo }}</Tag>
          <Tag v-if="versionDetail.tags" tone="neutral">{{ versionDetail.tags }}</Tag>
        </div>
        <div>
          <div class="faint" style="font-size: 11.5px">标题</div>
          <div class="soft">{{ versionDetail.pageTitle }}</div>
        </div>
        <div>
          <div class="faint" style="font-size: 11.5px">正文（Markdown 原文）</div>
          <pre class="code-block" style="white-space: pre-wrap">{{ versionDetail.content }}</pre>
        </div>
        <div style="display: flex; gap: 8px">
          <PermissionButton perm="knowledge:page:edit" variant="primary" icon="refresh" :disabled="!canWrite" @click="rollback(versionDetail)">回滚到该版本</PermissionButton>
        </div>
      </div>
    </Drawer>
  </div>
</template>
