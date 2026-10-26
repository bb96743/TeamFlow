<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Drawer, Field, SectionLabel, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import {
  recruitApi,
  type RecruitCandidateRow,
  type RecruitJobRow,
  type RecruitResumeRow,
} from '@/api/recruit'
import { useListPage } from '@/composables/list'
import { hasPermission } from '@/directives/permission'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import { downloadBlob } from '@/utils/download'
import {
  CANDIDATE_CHANNEL_OPTIONS,
  CANDIDATE_CHANNEL_TEXT,
  CANDIDATE_STAGE_TEXT,
  CANDIDATE_STAGE_TONE,
  dateTimeText,
} from './support'

/* ============================================================
   候选人库：简历入库、标签、阶段推进与简历版本管理。
   · 查重：同手机号 + 同邮箱再次入库时后端不重复创建，返回 duplicated=true，
     页面明确提示「该候选人已在库中」并给出查看入口；
   · 简历只存文件引用：上传走平台统一上传，下载走鉴权接口；
   · 列表默认脱敏展示手机号与邮箱，完整联系方式在详情里看。
   ============================================================ */

const list = useListPage<RecruitCandidateRow, { keyword: string; jobId: number | null; stage: string; channel: string }>({
  query: { keyword: '', jobId: null, stage: '', channel: '' },
  fetch: params => recruitApi.candidates.list(params),
})

const columns: Column[] = [
  { key: 'nameSnapshot', title: '候选人', minWidth: 180 },
  { key: 'jobTitle', title: '应聘职位', minWidth: 160 },
  { key: 'currentStage', title: '阶段', width: 100 },
  { key: 'channelLabel', title: '来源渠道', width: 110 },
  { key: 'contact', title: '联系方式', minWidth: 190 },
  { key: 'tags', title: '标签', minWidth: 140 },
  { key: 'stageTime', title: '阶段变更时间', width: 150 },
  { key: 'actions', title: '操作', width: 170, className: 'col-actions' },
]

/* ── 职位选项（筛选与表单共用） ── */
const jobs = ref<RecruitJobRow[]>([])
const jobOptions = computed(() => jobs.value.map(job => ({ label: `${job.jobTitle}（${job.statusLabel || job.status}）`, value: job.id })))

onMounted(async () => {
  try {
    const page = await recruitApi.jobs.list({ pageNum: 1, pageSize: 200 })
    jobs.value = page.rows
  } catch (error) {
    toastError(error, '职位列表加载失败')
  }
})

function pickJobFilter(value: string | number | null | undefined) {
  list.query.jobId = value === null || value === '' ? null : Number(value)
}

/* ── 入库 / 编辑 ── */
interface CandidateForm {
  id?: number
  jobId: number | null
  nameSnapshot: string
  phone: string
  email: string
  sourceChannel: string
  tags: string
  remark: string
}
const emptyForm = (): CandidateForm => ({
  jobId: null,
  nameSnapshot: '',
  phone: '',
  email: '',
  sourceChannel: 'website',
  tags: '',
  remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const form = reactive<CandidateForm>(emptyForm())
const errors = ref<Partial<Record<keyof CandidateForm, string>>>({})
/** 查重命中提示：不关闭弹窗，明确告知「已存在」并提供查看入口 */
const duplicatedId = ref<number | null>(null)

function pickFormJob(value: string | number | null | undefined) {
  form.jobId = value === null || value === '' ? null : Number(value)
}
function pickChannel(value: string | number | null | undefined) {
  form.sourceChannel = String(value ?? 'website')
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined, jobId: list.query.jobId })
  errors.value = {}
  duplicatedId.value = null
  dialog.value = true
}

function openEdit(row: RecruitCandidateRow) {
  Object.assign(form, {
    id: row.id,
    jobId: row.jobId ?? null,
    nameSnapshot: row.nameSnapshot,
    phone: row.phone ?? '',
    email: row.email ?? '',
    sourceChannel: row.sourceChannel ?? 'website',
    tags: row.tags ?? '',
    remark: row.remark ?? '',
  })
  errors.value = {}
  duplicatedId.value = null
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    jobId: [required('请选择应聘职位')],
    nameSnapshot: [required('请输入候选人姓名')],
  })
  if (!form.phone.trim() && !form.email.trim()) {
    errors.value.phone = '手机号与邮箱至少填写一个（用于查重）'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    jobId: form.jobId,
    nameSnapshot: form.nameSnapshot.trim(),
    phone: form.phone.trim() || null,
    email: form.email.trim() || null,
    sourceChannel: form.sourceChannel,
    tags: form.tags,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await recruitApi.candidates.update(form.id, payload)
      pushToast('候选人信息已更新')
      dialog.value = false
    } else {
      const result = await recruitApi.candidates.create(payload)
      if (result.duplicated) {
        duplicatedId.value = result.id
        pushToast('该候选人已在库中，未重复新增', 'info')
        return
      }
      pushToast('候选人已入库')
      dialog.value = false
    }
    await list.load()
  } catch (error) {
    toastError(error, '候选人保存失败')
  } finally {
    saving.value = false
  }
}

/* ── 阶段推进（目标阶段由后端 nextStages 给出；淘汰必须写原因） ── */
const stageOpen = ref(false)
const stageSaving = ref(false)
const stageTarget = ref<RecruitCandidateRow | null>(null)
const stageForm = reactive<{ stage: string; rejectReason: string }>({ stage: '', rejectReason: '' })
const stageOptions = computed(() =>
  (stageTarget.value?.nextStages ?? [])
    .filter(stage => stage !== 'hired')
    .map(stage => ({ label: CANDIDATE_STAGE_TEXT[stage] || stage, value: stage })),
)
/** 行内「推进阶段」是否可用：用后端返回的 nextStages 判断（排除需在 Offer 页完成的入职） */
function stageOptionsFor(row: RecruitCandidateRow): string[] {
  return (row.nextStages ?? []).filter(stage => stage !== 'hired')
}
const stageFilterOptions = computed(() => [
  { label: '全部阶段', value: '' },
  ...Object.entries(CANDIDATE_STAGE_TEXT).map(([value, label]) => ({ label, value })),
])

function openStage(row: RecruitCandidateRow) {
  stageTarget.value = row
  stageForm.stage = stageOptions.value[0]?.value ?? ''
  stageForm.rejectReason = ''
  stageOpen.value = true
}

async function submitStage() {
  if (!stageTarget.value || !stageForm.stage) return
  if (stageForm.stage === 'rejected' && !stageForm.rejectReason.trim()) {
    pushToast('淘汰候选人必须填写原因（留痕可追溯）', 'danger')
    return
  }
  stageSaving.value = true
  try {
    await recruitApi.candidates.changeStage(stageTarget.value.id, stageForm.stage, stageForm.rejectReason)
    pushToast(`候选人已推进到「${CANDIDATE_STAGE_TEXT[stageForm.stage] || stageForm.stage}」`)
    stageOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '阶段推进失败')
  } finally {
    stageSaving.value = false
  }
}

/* ── 详情（完整联系方式 + 简历列表） ── */
const detailOpen = ref(false)
const detail = ref<RecruitCandidateRow | null>(null)
const detailLoading = ref(false)

async function openDetail(row: { id: number }) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await recruitApi.candidates.detail(row.id)
  } catch (error) {
    toastError(error, '候选人详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/** 查重命中后查看库中已有候选人（关闭入库弹窗，打开详情） */
function viewDuplicated(id: number) {
  dialog.value = false
  openDetail({ id })
}

/* ── 简历：上传（平台统一上传）+ 下载（鉴权接口） ── */
const resumeOpen = ref(false)
const resumeTarget = ref<RecruitCandidateRow | null>(null)
const resumes = ref<RecruitResumeRow[]>([])
const resumeLoading = ref(false)
const uploading = ref(false)
const chosenFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const canUploadResume = computed(() => hasPermission('recruit:resume:add') && hasPermission('system:file:upload'))

async function openResumes(row: RecruitCandidateRow) {
  resumeTarget.value = row
  resumeOpen.value = true
  chosenFile.value = null
  await loadResumes()
}

async function loadResumes() {
  if (!resumeTarget.value) return
  resumeLoading.value = true
  try {
    resumes.value = await recruitApi.candidates.resumes(resumeTarget.value.id)
  } catch (error) {
    toastError(error, '简历列表加载失败')
    resumes.value = []
  } finally {
    resumeLoading.value = false
  }
}

function chooseFile() {
  fileInput.value?.click()
}

function onFileChange(event: Event) {
  const files = (event.target as HTMLInputElement).files
  chosenFile.value = files && files.length ? files[0] : null
  if (chosenFile.value && chosenFile.value.size > 20 * 1024 * 1024) {
    pushToast('简历文件不要超过 20MB', 'danger')
    chosenFile.value = null
  }
}

async function uploadResume() {
  if (!resumeTarget.value || !chosenFile.value) {
    pushToast('请先选择简历文件', 'danger')
    return
  }
  uploading.value = true
  try {
    const uploaded = await recruitApi.files.upload(chosenFile.value, 'recruit_resume', resumeTarget.value.id)
    await recruitApi.candidates.attachResume(resumeTarget.value.id, {
      fileId: uploaded.id,
      fileName: uploaded.originalName || chosenFile.value.name,
    })
    pushToast('简历已上传并登记新版本')
    chosenFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    await loadResumes()
    await list.load()
  } catch (error) {
    toastError(error, '简历上传失败')
  } finally {
    uploading.value = false
  }
}

async function downloadResume(row: RecruitResumeRow) {
  if (!row.fileId) {
    pushToast('该简历没有可下载的文件', 'danger')
    return
  }
  try {
    const blob = await recruitApi.files.download(row.fileId)
    downloadBlob(blob, row.fileName || `简历-${row.fileId}`)
  } catch (error) {
    toastError(error, '简历下载失败')
  }
}
</script>

<template>
  <ListPage
    title="候选人库"
    desc="候选人按手机号与邮箱自动查重，重复入库不会产生重复记录；阶段在投递、初筛、面试、Offer 之间推进，简历以版本方式登记。"
    table-title="候选人列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="recruit:candidate:add" variant="primary" icon="plus" @click="openCreate">候选人入库</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="姓名 / 手机号 / 邮箱" clearable @enter="list.search()" />
      </Field>
      <Field label="应聘职位">
        <Select
          :model-value="list.query.jobId"
          :options="[{ label: '全部职位', value: '' }, ...jobOptions]"
          filterable
          @update:model-value="pickJobFilter"
        />
      </Field>
      <Field label="阶段">
        <Select v-model="list.query.stage" :options="stageFilterOptions" />
      </Field>
      <Field label="来源渠道">
        <Select v-model="list.query.channel" :options="[{ label: '全部渠道', value: '' }, ...CANDIDATE_CHANNEL_OPTIONS]" />
      </Field>
    </template>

    <template #cell-nameSnapshot="{ row }">
      <span class="cell-title">{{ row.nameSnapshot }}</span>
      <span class="cell-sub">{{ row.candidateCode || '—' }}</span>
    </template>
    <template #cell-jobTitle="{ row }">
      <span>{{ row.jobTitle || '—' }}</span>
    </template>
    <template #cell-currentStage="{ row }">
      <Tag :tone="CANDIDATE_STAGE_TONE[row.currentStage] || 'neutral'" dot>
        {{ row.stageLabel || CANDIDATE_STAGE_TEXT[row.currentStage] || row.currentStage }}
      </Tag>
    </template>
    <template #cell-channelLabel="{ row }">
      <span class="muted">{{ row.channelLabel || CANDIDATE_CHANNEL_TEXT[row.sourceChannel || ''] || '—' }}</span>
    </template>
    <template #cell-contact="{ row }">
      <span class="tnum soft">{{ row.phone || '—' }}<br />{{ row.email || '—' }}</span>
    </template>
    <template #cell-tags="{ row }">
      <span class="muted">{{ row.tags || '—' }}</span>
    </template>
    <template #cell-stageTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.stageTime) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="recruit:candidate:list" icon-only icon="eye" label="详情" @click="openDetail(row)" />
        <PermissionButton perm="recruit:candidate:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
        <PermissionButton
          perm="recruit:candidate:edit"
          icon-only
          icon="arrowRight"
          label="推进阶段"
          :disabled="!stageOptionsFor(row).length"
          @click="openStage(row)"
        />
        <PermissionButton perm="recruit:candidate:list" icon-only icon="file" label="简历" @click="openResumes(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑候选人' : '候选人入库'"
      sub="手机号与邮箱用于去重：同一手机号 + 同一邮箱不会重复入库"
      :width="700"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="应聘职位" required :error="errors.jobId">
        <Select
          :model-value="form.jobId"
          :options="jobOptions"
          placeholder="选择职位"
          filterable
          :invalid="!!errors.jobId"
          @update:model-value="pickFormJob"
        />
      </Field>
      <Field label="候选人姓名" required :error="errors.nameSnapshot">
        <TextInput v-model="form.nameSnapshot" placeholder="如：张三" :invalid="!!errors.nameSnapshot" :maxlength="40" />
      </Field>
      <Field label="手机号" :error="errors.phone" hint="与邮箱至少填一个">
        <TextInput v-model="form.phone" placeholder="如：13800000000" :invalid="!!errors.phone" :maxlength="20" />
      </Field>
      <Field label="邮箱" hint="与手机号至少填一个">
        <TextInput v-model="form.email" placeholder="如：name@example.com" :maxlength="80" />
      </Field>
      <Field label="来源渠道">
        <Select :model-value="form.sourceChannel" :options="CANDIDATE_CHANNEL_OPTIONS" @update:model-value="pickChannel" />
      </Field>
      <Field label="标签" hint="多个标签用逗号分隔">
        <TextInput v-model="form.tags" placeholder="如：高潜,同城" :maxlength="80" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="沟通记录、期望薪资等（可选）" :maxlength="300" />
      </Field>
      <div v-if="duplicatedId" class="span-2" style="width: 100%">
        <p style="font-size: 13px; line-height: 1.8; color: var(--ink-muted)">
          该候选人已在库中（编号 {{ duplicatedId }}），本次没有重复新增。可直接查看已有记录继续推进阶段。
        </p>
        <Button variant="secondary" icon="eye" @click="duplicatedId && viewDuplicated(duplicatedId)">查看已有候选人</Button>
      </div>
    </FormDialog>

    <FormDialog
      :open="stageOpen"
      title="推进候选人阶段"
      :sub="stageTarget ? `${stageTarget.nameSnapshot} · 当前：${stageTarget.stageLabel || CANDIDATE_STAGE_TEXT[stageTarget.currentStage] || stageTarget.currentStage}` : ''"
      :saving="stageSaving"
      submit-text="确认推进"
      @close="stageOpen = false"
      @submit="submitStage"
    >
      <Field label="推进到" required span hint="可选阶段由后端状态机给出；入职需在 Offer 页完成">
        <Select v-model="stageForm.stage" :options="stageOptions" placeholder="选择目标阶段" />
      </Field>
      <Field v-if="stageForm.stage === 'rejected'" label="淘汰原因" required span hint="留痕可追溯，会记录在候选人上">
        <TextInput v-model="stageForm.rejectReason" multiline :rows="2" placeholder="如：技能不匹配岗位要求" :maxlength="200" />
      </Field>
    </FormDialog>

    <Drawer :open="detailOpen" title="候选人详情" :sub="detail?.nameSnapshot || ''" :width="760" @close="detailOpen = false">
      <div v-if="detail" class="stack">
        <SpecPlate
          title="候选人信息"
          :rows="[
            { label: '候选人编号', value: detail.candidateCode },
            { label: '应聘职位', value: detail.jobTitle },
            { label: '当前阶段', value: detail.stageLabel || CANDIDATE_STAGE_TEXT[detail.currentStage] },
            { label: '来源渠道', value: detail.channelLabel || CANDIDATE_CHANNEL_TEXT[detail.sourceChannel || ''] },
            { label: '手机号', value: detail.phone },
            { label: '邮箱', value: detail.email },
            { label: '阶段变更时间', value: dateTimeText(detail.stageTime) },
            { label: '已入职用户', value: detail.hiredUserId },
            { label: '淘汰原因', value: detail.rejectReason },
          ]"
        />
        <div>
          <SectionLabel title="标签与备注" />
          <p style="font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
            标签：{{ detail.tags || '—' }}<br />
            备注：{{ detail.remark || '—' }}
          </p>
        </div>
        <div>
          <SectionLabel title="简历版本" />
          <DataTable
            :columns="[
              { key: 'fileName', title: '文件', minWidth: 200 },
              { key: 'parseStatus', title: '解析状态', width: 100 },
              { key: 'screeningScore', title: '筛查分', width: 90, align: 'right' },
              { key: 'createTime', title: '登记时间', width: 150 },
              { key: 'actions', title: '操作', width: 90, className: 'col-actions' }
            ]"
            :data="detail.resumes ?? []"
            empty-text="还没有登记简历"
          >
            <template #cell-fileName="{ row }">
              <span class="cell-title">{{ row.fileName || `文件 #${row.fileId}` }}</span>
            </template>
            <template #cell-parseStatus="{ row }">
              <span class="muted">{{ row.parseStatus || '—' }}</span>
            </template>
            <template #cell-screeningScore="{ row }">
              <span class="tnum">{{ row.screeningScore ?? '—' }}</span>
            </template>
            <template #cell-createTime="{ row }">
              <span class="tnum soft">{{ dateTimeText(row.createTime) }}</span>
            </template>
            <template #cell-actions="{ row }">
              <div class="row-actions">
                <Button variant="ghost" size="sm" @click="downloadResume(row)">下载</Button>
              </div>
            </template>
          </DataTable>
        </div>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>

    <Drawer :open="resumeOpen" title="简历管理" :sub="resumeTarget?.nameSnapshot || ''" :width="720" @close="resumeOpen = false">
      <div class="stack">
        <div v-if="canUploadResume">
          <SectionLabel title="上传新版本简历" />
          <p class="faint" style="font-size: 12px; line-height: 1.8; margin-bottom: 10px">
            简历文件保存在平台统一文件库，这里只登记文件引用；支持 PDF / Word 等常见格式，单个文件不超过 20MB。
          </p>
          <input ref="fileInput" type="file" hidden @change="onFileChange" />
          <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap">
            <Button variant="secondary" icon="upload" @click="chooseFile">选择简历文件</Button>
            <span class="faint" style="font-size: 12px">{{ chosenFile ? chosenFile.name : '尚未选择文件' }}</span>
            <Button variant="primary" :loading="uploading" :disabled="!chosenFile" @click="uploadResume">上传并登记版本</Button>
          </div>
        </div>
        <p v-else class="faint" style="font-size: 12px">
          当前账号没有上传简历的权限（需要简历登记与文件上传权限）。
        </p>
        <div>
          <SectionLabel title="已登记的简历" />
          <DataTable
            :columns="[
              { key: 'fileName', title: '文件', minWidth: 220 },
              { key: 'version', title: '版本', width: 80, align: 'right' },
              { key: 'createTime', title: '登记时间', width: 150 },
              { key: 'actions', title: '操作', width: 100, className: 'col-actions' }
            ]"
            :data="resumes"
            :loading="resumeLoading"
            empty-text="还没有登记简历"
          >
            <template #cell-fileName="{ row }">
              <span class="cell-title">{{ row.fileName || `文件 #${row.fileId}` }}</span>
            </template>
            <template #cell-version="{ row }">
              <span class="tnum">v{{ row.id }}</span>
            </template>
            <template #cell-createTime="{ row }">
              <span class="tnum soft">{{ dateTimeText(row.createTime) }}</span>
            </template>
            <template #cell-actions="{ row }">
              <div class="row-actions">
                <Button variant="ghost" size="sm" icon="download" @click="downloadResume(row)">下载</Button>
              </div>
            </template>
          </DataTable>
        </div>
      </div>
    </Drawer>
  </ListPage>
</template>
