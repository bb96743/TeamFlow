<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  EmptyState,
  Field,
  PageHeader,
  Panel,
  Select,
  SpecPlate,
  Tag,
  TextInput,
} from '@/components/ui'
import { FormDialog, PermissionButton } from '@/components/business'
import { projectApi, type ProjectRetroData } from '@/api/project'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import { useProjectPicker } from './useProjectPicker'

/* ============================================================
   项目复盘（M14 · 2.5.4）
   ------------------------------------------------------------
   结项后才能归档：归档由后端把正文写入知识库并返回 retroPageId，
   本模块只保存该引用。已归档时页面展示「已归档到知识库」入口信息，
   不再展示正文编辑器（正文不在本模块保存）；未结项归档会被后端
   400 拒绝，页面原样展示后端提示。
   ============================================================ */

const picker = useProjectPicker()
const retro = ref<ProjectRetroData | null>(null)
const loading = ref(false)
const saving = ref(false)
const templateLoading = ref(false)
/* 未归档时直接写；已归档时默认只显示入口，点「重新归档」再展开编辑器 */
const editing = ref(false)

interface RetroForm {
  title: string
  content: string
  tags: string
  summary: string
}
const form = reactive<RetroForm>({ title: '', content: '', tags: '', summary: '' })
const errors = ref<{ content?: string }>({})

const canArchive = computed(() => retro.value?.canArchive === true)
const statusHint = computed(() => {
  if (!retro.value) return ''
  return retro.value.canArchive
    ? '项目已结项，可以归档复盘；归档后正文保存到知识库，本页只保留引用。'
    : `项目当前状态为「${retro.value.statusLabel || retro.value.status}」，结项后才能归档复盘（系统会拒绝并提示原因）。`
})

async function loadRetro() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) {
    retro.value = null
    return
  }
  loading.value = true
  try {
    retro.value = await projectApi.retro.get(projectId)
    editing.value = !retro.value.retroArchived
    if (editing.value) resetForm()
  } catch (error) {
    toastError(error, '复盘信息加载失败')
    retro.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await picker.loadProjects()
})
watch(picker.selectedProjectId, () => loadRetro())

function resetForm() {
  form.title = retro.value ? `项目复盘：${retro.value.projectName || ''}` : ''
  form.content = ''
  form.tags = ''
  form.summary = ''
  errors.value = {}
}

function startWrite() {
  editing.value = true
  resetForm()
}

async function loadTemplate() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  templateLoading.value = true
  try {
    const template = await projectApi.retro.template(projectId)
    form.title = template.templateTitle || form.title
    form.content = template.templateContent
    errors.value = {}
    pushToast('已载入结项复盘模板，请补充正文后归档')
  } catch (error) {
    toastError(error, '复盘模板加载失败')
  } finally {
    templateLoading.value = false
  }
}

async function archive() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  errors.value = validate(form, { content: [required('请填写复盘正文后再归档')] })
  if (Object.keys(errors.value).length) return
  saving.value = true
  try {
    const result = await projectApi.retro.archive(projectId, {
      title: form.title.trim() || null,
      content: form.content,
      tags: form.tags.trim()
        ? form.tags.split(/[,，]/).map(item => item.trim()).filter(Boolean)
        : null,
      summary: form.summary.trim() || null,
    })
    pushToast(
      result.created
        ? `复盘已归档到知识库（页面 #${result.retroPageId}）`
        : `复盘已归档到知识库，知识页面已更新为新版本（页面 #${result.retroPageId}）`,
    )
    await loadRetro()
  } catch (error) {
    // 未结项等业务拒绝：原样展示后端提示
    toastError(error, '复盘归档失败')
  } finally {
    saving.value = false
  }
}

const specRows = computed(() => {
  const data = retro.value
  if (!data) return []
  return [
    { label: '项目编号', value: data.projectCode },
    { label: '项目状态', value: data.statusLabel || data.status },
    {
      label: '复盘归档',
      value: data.retroArchived ? '已归档到知识库' : '未归档',
      tone: (data.retroArchived ? 'lime' : undefined) as 'lime' | undefined,
    },
    { label: '知识页面 ID', value: data.retroPageId ?? '—' },
    { label: '归档时间', value: data.retroArchivedTime ?? '—' },
    { label: '归档操作人', value: data.retroArchivedBy ?? '—' },
  ]
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="项目复盘"
      desc="项目结项后归档复盘。复盘正文保存到知识库，本项目只保留知识页面引用；已归档时这里展示归档入口信息，不再保存正文副本。"
    >
      <Select
        :model-value="picker.selectedProjectId.value"
        :options="picker.options.value"
        :placeholder="picker.loading.value ? '项目加载中…' : '请选择项目'"
        filterable
        @update:model-value="picker.selectProject($event)"
      />
      <PermissionButton
        v-if="retro && !editing"
        perm="project:retro:archive"
        variant="secondary"
        icon="edit"
        @click="startWrite"
      >重新归档（生成新版本）</PermissionButton>
    </PageHeader>

    <template v-if="!picker.selectedProjectId.value && !picker.loading.value">
      <EmptyState
        title="请先选择项目"
        desc="复盘按项目维护；如果还没有项目，可以到「项目列表」先完成立项，结项后再回来归档复盘。"
        icon="file"
      />
    </template>

    <template v-else-if="retro">
      <section class="band reveal">
        <div class="band-head">
          <span class="section-label">{{ retro.projectName }} · 复盘归档</span>
          <div class="band-tools">
            <Tag v-if="retro.retroArchived" tone="ok" dot>已归档到知识库</Tag>
            <Tag v-else-if="canArchive" tone="info" dot>待归档</Tag>
            <Tag v-else tone="neutral">未到归档阶段</Tag>
          </div>
        </div>
        <SpecPlate title="复盘归档信息" :rows="specRows" />
        <p class="faint" style="margin: 10px 0 0">{{ retro.archiveRule || statusHint }}</p>
      </section>

      <!-- 已归档：只展示知识库入口信息，不展示正文编辑器 -->
      <Panel v-if="retro.retroArchived && !editing" title="复盘正文" meta="正文保存在知识库">
        <div class="archive-entry m-surface">
          <div>
            <div class="cell-title">复盘正文已归档到知识库</div>
            <div class="cell-sub">
              知识页面 ID：<span class="tnum">#{{ retro.retroPageId }}</span>
              · 归档时间：{{ retro.retroArchivedTime || '—' }}
              · 操作人：{{ retro.retroArchivedBy || '—' }}
            </div>
            <div class="cell-sub" style="margin-top: 6px">
              本页不保存复盘正文；查看或修改正文请到知识库中打开该页面。重新归档会在知识库生成该页面的新版本。
            </div>
          </div>
          <PermissionButton
            perm="project:retro:archive"
            variant="secondary"
            icon="edit"
            @click="startWrite"
          >重新归档（生成新版本）</PermissionButton>
        </div>
      </Panel>

      <!-- 未归档（或主动重新归档）：写入后由后端归档到知识库 -->
      <Panel v-else title="撰写复盘" :meta="canArchive ? '归档后正文进入知识库' : '当前阶段不可归档'">
        <template #tools>
          <Tag v-if="!canArchive" tone="neutral">{{ statusHint }}</Tag>
        </template>
        <div class="form-grid">
          <Field label="复盘标题" wide hint="可空，默认使用「项目复盘：项目名称」">
            <TextInput v-model="form.title" placeholder="项目复盘：项目名称" :maxlength="200" />
          </Field>
          <Field label="结项结论摘要" wide hint="可空；作为知识页面的摘要，不保存在项目表中">
            <TextInput v-model="form.summary" placeholder="一句话说明项目结果与关键结论" :maxlength="200" />
          </Field>
          <Field label="标签" wide hint="用逗号分隔，如：复盘,交付,经验">
            <TextInput v-model="form.tags" placeholder="复盘,交付,经验" :maxlength="200" />
          </Field>
          <Field label="复盘正文" required :error="errors.content" span hint="支持 Markdown；归档后正文只保存在知识库">
            <TextInput
              v-model="form.content"
              multiline
              :rows="14"
              :invalid="!!errors.content"
              placeholder="项目目标达成情况、关键过程与决策、问题与风险、经验与改进措施、后续行动项…"
            />
          </Field>
        </div>
        <div class="band-head" style="margin-top: 14px">
          <span class="faint">
            可以先载入结项复盘模板（自动带入项目概况与里程碑完成情况），补充正文后归档。
          </span>
          <div style="display: flex; gap: 8px">
            <PermissionButton
              perm="project:retro:list"
              variant="secondary"
              icon="download"
              :disabled="templateLoading"
              @click="loadTemplate"
            >载入复盘模板</PermissionButton>
            <PermissionButton
              perm="project:retro:archive"
              variant="primary"
              icon="upload"
              :loading="saving"
              @click="archive"
            >归档到知识库</PermissionButton>
          </div>
        </div>
        <p v-if="!canArchive" class="faint" style="margin: 10px 0 0">{{ statusHint }}</p>
      </Panel>
    </template>
  </div>
</template>

<style scoped>
.archive-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 16px;
  border-radius: var(--r-md);
  border: 1px solid var(--line);
}
</style>
