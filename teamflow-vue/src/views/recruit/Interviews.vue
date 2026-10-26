<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Drawer, Field, SectionLabel, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  recruitApi,
  type RecruitCandidateRow,
  type RecruitInterviewRow,
} from '@/api/recruit'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  INTERVIEW_DIMENSIONS,
  INTERVIEW_RESULT_TEXT,
  INTERVIEW_RESULT_TONE,
  INTERVIEW_STATUS_OPTIONS,
  INTERVIEW_STATUS_TEXT,
  INTERVIEW_STATUS_TONE,
  dateTimeInput,
  dateTimeText,
} from './support'

/* ============================================================
   面试安排：为处于「面试」阶段的候选人排轮次、指定面试官，
   面试完成后由面试官按维度提交结构化评价（每个维度独立评分 + 评语），
   再给出通过 / 不通过结论。
   ============================================================ */

const list = useListPage<RecruitInterviewRow, { candidateId: number | null; status: string; interviewerId: number | null }>({
  query: { candidateId: null, status: '', interviewerId: null },
  fetch: params => recruitApi.interviews.list(params),
})

const columns: Column[] = [
  { key: 'candidateName', title: '候选人', minWidth: 160 },
  { key: 'jobTitle', title: '应聘职位', minWidth: 150 },
  { key: 'roundName', title: '轮次', width: 120 },
  { key: 'interviewerName', title: '面试官', width: 110 },
  { key: 'scheduledTime', title: '面试时间', width: 150 },
  { key: 'location', title: '地点', width: 130 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'result', title: '结论', width: 90 },
  { key: 'actions', title: '操作', width: 190, className: 'col-actions' },
]

/* ── 候选人 / 职位选项：安排面试只列出处于「面试」阶段的候选人 ── */
const candidates = ref<RecruitCandidateRow[]>([])
const interviewCandidates = computed(() => candidates.value.filter(item => item.currentStage === 'interview'))
const candidateOptions = computed(() => interviewCandidates.value.map(item => ({
  label: `${item.nameSnapshot}（${item.jobTitle || '—'}）`,
  value: item.id,
})))

async function loadCandidates() {
  try {
    const page = await recruitApi.candidates.list({ pageNum: 1, pageSize: 200 })
    candidates.value = page.rows
  } catch (error) {
    candidates.value = []
    toastError(error, '候选人列表加载失败')
  }
}

onMounted(loadCandidates)

function pickCandidateFilter(value: string | number | null | undefined) {
  list.query.candidateId = value === null || value === '' ? null : Number(value)
}
function pickStatusFilter(value: string | number | null | undefined) {
  list.query.status = String(value ?? '')
}
function pickInterviewerFilter(value: string | number | null | undefined) {
  list.query.interviewerId = value === null || value === '' ? null : Number(value)
}

/* ── 安排面试 ── */
interface InterviewForm {
  candidateId: number | null
  roundName: string
  interviewerId: number | null
  scheduledTime: string
  durationMinutes: number | null
  location: string
  remark: string
}
const emptyForm = (): InterviewForm => ({
  candidateId: null,
  roundName: '',
  interviewerId: null,
  scheduledTime: '',
  durationMinutes: 60,
  location: '',
  remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const form = reactive<InterviewForm>(emptyForm())
const errors = ref<Partial<Record<keyof InterviewForm, string>>>({})
const TIME_RULE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/

function pickFormCandidate(value: string | number | null | undefined) {
  form.candidateId = value === null || value === '' ? null : Number(value)
}
function pickFormInterviewer(value: string | number | null | undefined) {
  form.interviewerId = value === null || value === '' ? null : Number(value)
}

function openCreate() {
  Object.assign(form, emptyForm(), { candidateId: list.query.candidateId })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    candidateId: [required('请选择候选人')],
    interviewerId: [required('请选择面试官')],
    scheduledTime: [required('请填写面试时间')],
  })
  if (!errors.value.scheduledTime && !TIME_RULE.test(form.scheduledTime)) {
    errors.value.scheduledTime = '时间格式为 2026-01-31 14:00'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  try {
    await recruitApi.interviews.schedule({
      candidateId: form.candidateId,
      roundName: form.roundName,
      interviewerId: form.interviewerId,
      scheduledTime: form.scheduledTime,
      durationMinutes: form.durationMinutes,
      location: form.location,
      remark: form.remark,
    })
    pushToast('面试已安排')
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '面试安排失败')
  } finally {
    saving.value = false
  }
}

/* ── 改期 / 完成 / 取消 ──
   面试状态只有「已安排 → 已完成 / 已取消」两种流转（后端状态机固定），
   因此按当前状态给出对应操作；结论只能在面试完成后给出。 */
const updateOpen = ref(false)
const updateSaving = ref(false)
const updateTarget = ref<RecruitInterviewRow | null>(null)
const updateForm = reactive<{ interviewerId: number | null; scheduledTime: string; durationMinutes: number | null; location: string; status: string; result: string; remark: string }>({
  interviewerId: null,
  scheduledTime: '',
  durationMinutes: 60,
  location: '',
  status: 'scheduled',
  result: 'pending',
  remark: '',
})

const statusChoices = computed(() => {
  const current = updateTarget.value?.status
  if (current === 'scheduled') {
    return [
      { label: INTERVIEW_STATUS_TEXT.scheduled, value: 'scheduled' },
      { label: INTERVIEW_STATUS_TEXT.completed, value: 'completed' },
      { label: INTERVIEW_STATUS_TEXT.cancelled, value: 'cancelled' },
    ]
  }
  return [{ label: INTERVIEW_STATUS_TEXT[current || ''] || current || '—', value: current || '' }]
})
const resultChoices = computed(() => [
  { label: INTERVIEW_RESULT_TEXT.pending, value: 'pending' },
  { label: INTERVIEW_RESULT_TEXT.pass, value: 'pass' },
  { label: INTERVIEW_RESULT_TEXT.fail, value: 'fail' },
])

function openUpdate(row: RecruitInterviewRow) {
  updateTarget.value = row
  Object.assign(updateForm, {
    interviewerId: row.interviewerId ?? null,
    scheduledTime: dateTimeInput(row.scheduledTime),
    durationMinutes: row.durationMinutes ?? 60,
    location: row.location ?? '',
    status: row.status,
    result: row.result ?? 'pending',
    remark: row.remark ?? '',
  })
  updateOpen.value = true
}
function pickUpdateInterviewer(value: string | number | null | undefined) {
  updateForm.interviewerId = value === null || value === '' ? null : Number(value)
}
function pickUpdateStatus(value: string | number | null | undefined) {
  updateForm.status = String(value ?? 'scheduled')
  if (updateForm.status !== 'completed') updateForm.result = 'pending'
}
function pickUpdateResult(value: string | number | null | undefined) {
  updateForm.result = String(value ?? 'pending')
}

async function submitUpdate() {
  if (!updateTarget.value) return
  if (!TIME_RULE.test(updateForm.scheduledTime)) {
    pushToast('面试时间格式为 2026-01-31 14:00', 'danger')
    return
  }
  if (updateForm.result !== 'pending' && updateForm.status !== 'completed') {
    pushToast('只有已完成的面试才能给出通过/不通过结论', 'danger')
    return
  }
  updateSaving.value = true
  try {
    await recruitApi.interviews.update(updateTarget.value.id, { ...updateForm })
    pushToast('面试信息已更新')
    updateOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '面试更新失败')
  } finally {
    updateSaving.value = false
  }
}

/* ── 结构化评价（两个维度，各自评分 + 评语） ── */
const feedbackOpen = ref(false)
const feedbackSaving = ref(false)
const feedbackTarget = ref<RecruitInterviewRow | null>(null)
const feedbackForm = reactive<Record<string, { score: number | null; comment: string }>>(
  Object.fromEntries(INTERVIEW_DIMENSIONS.map(dimension => [dimension.code, { score: null, comment: '' }])),
)
const feedbackErrors = ref<Record<string, string>>({})

function openFeedback(row: RecruitInterviewRow) {
  feedbackTarget.value = row
  feedbackErrors.value = {}
  INTERVIEW_DIMENSIONS.forEach(dimension => {
    const existing = (row.feedbacks ?? []).find(item => item.dimensionCode === dimension.code)
    feedbackForm[dimension.code] = { score: existing?.score ?? null, comment: existing?.comment ?? '' }
  })
  feedbackOpen.value = true
}

async function submitFeedback() {
  if (!feedbackTarget.value) return
  const nextErrors: Record<string, string> = {}
  const dimensions = INTERVIEW_DIMENSIONS.map(dimension => {
    const value = feedbackForm[dimension.code] ?? { score: null, comment: '' }
    if (value.score === null) nextErrors[dimension.code] = `请给「${dimension.name}」评分`
    else if (value.score < 0 || value.score > 100) nextErrors[dimension.code] = '评分需在 0-100 之间'
    if (!value.comment.trim()) nextErrors[`${dimension.code}-comment`] = `请填写「${dimension.name}」的评语`
    return {
      dimensionCode: dimension.code,
      dimensionName: dimension.name,
      score: value.score,
      comment: value.comment.trim(),
    }
  })
  feedbackErrors.value = nextErrors
  if (Object.keys(nextErrors).length) return
  feedbackSaving.value = true
  try {
    await recruitApi.interviews.submitFeedback(feedbackTarget.value.id, { dimensions })
    pushToast('结构化评价已提交')
    feedbackOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '评价提交失败')
  } finally {
    feedbackSaving.value = false
  }
}

/* ── 详情（含评价列表） ── */
const detailOpen = ref(false)
const detail = ref<RecruitInterviewRow | null>(null)
const detailLoading = ref(false)

async function openDetail(row: RecruitInterviewRow) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await recruitApi.interviews.detail(row.id)
  } catch (error) {
    toastError(error, '面试详情加载失败')
  } finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <ListPage
    title="面试安排"
    desc="为处于「面试」阶段的候选人排轮次并指定面试官；面试完成后按维度提交结构化评价，再给出通过或不通过的结论。"
    table-title="面试列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="recruit:interview:add" variant="primary" icon="plus" @click="openCreate">安排面试</PermissionButton>
    </template>

    <template #search>
      <Field label="候选人" wide>
        <Select
          :model-value="list.query.candidateId"
          :options="[{ label: '全部候选人', value: '' }, ...candidates.map(item => ({ label: item.nameSnapshot, value: item.id }))]"
          filterable
          @update:model-value="pickCandidateFilter"
        />
      </Field>
      <Field label="面试状态">
        <Select
          :model-value="list.query.status"
          :options="[{ label: '全部状态', value: '' }, ...INTERVIEW_STATUS_OPTIONS]"
          @update:model-value="pickStatusFilter"
        />
      </Field>
      <Field label="面试官">
        <UserSelect :model-value="list.query.interviewerId" placeholder="全部面试官" @update:model-value="pickInterviewerFilter" />
      </Field>
    </template>

    <template #cell-candidateName="{ row }">
      <span class="cell-title">{{ row.candidateName || '—' }}</span>
    </template>
    <template #cell-jobTitle="{ row }">
      <span class="muted">{{ row.jobTitle || '—' }}</span>
    </template>
    <template #cell-roundName="{ row }">
      <span>{{ row.roundName || `第 ${row.roundNo ?? 1} 轮` }}</span>
    </template>
    <template #cell-interviewerName="{ row }">
      <span>{{ row.interviewerName || '—' }}</span>
    </template>
    <template #cell-scheduledTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.scheduledTime) }}</span>
    </template>
    <template #cell-location="{ row }">
      <span class="muted">{{ row.location || '—' }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="INTERVIEW_STATUS_TONE[row.status] || 'neutral'" dot>{{ row.statusLabel || INTERVIEW_STATUS_TEXT[row.status] || row.status }}</Tag>
    </template>
    <template #cell-result="{ row }">
      <Tag :tone="INTERVIEW_RESULT_TONE[row.result || 'pending'] || 'neutral'">
        {{ row.resultLabel || INTERVIEW_RESULT_TEXT[row.result || 'pending'] }}
      </Tag>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="recruit:interview:list" icon-only icon="eye" label="详情与评价" @click="openDetail(row)" />
        <PermissionButton
          perm="recruit:interview:edit"
          icon-only
          icon="clock"
          label="改期 / 完成 / 取消"
          :disabled="row.status === 'cancelled'"
          @click="openUpdate(row)"
        />
        <PermissionButton
          perm="recruit:interview:edit"
          icon-only
          icon="edit"
          label="结构化评价"
          :disabled="row.status !== 'completed'"
          @click="openFeedback(row)"
        />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      title="安排面试"
      sub="轮次由系统按候选人递增；候选人需先在候选人库推进到「面试」阶段"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="候选人" required :error="errors.candidateId" span>
        <Select
          :model-value="form.candidateId"
          :options="candidateOptions"
          placeholder="选择处于面试阶段的候选人"
          filterable
          :invalid="!!errors.candidateId"
          @update:model-value="pickFormCandidate"
        />
      </Field>
      <p v-if="!candidateOptions.length" class="span-2 faint" style="width: 100%; font-size: 12px">
        目前没有处于「面试」阶段的候选人，请先到候选人库推进阶段。
      </p>
      <Field label="轮次名称" hint="可留空，默认「第 N 轮面试」">
        <TextInput v-model="form.roundName" placeholder="如：技术二面" :maxlength="40" />
      </Field>
      <Field label="面试官" required :error="errors.interviewerId">
        <UserSelect :model-value="form.interviewerId" placeholder="选择面试官" @update:model-value="pickFormInterviewer" />
      </Field>
      <Field label="面试时间" required :error="errors.scheduledTime" hint="格式：2026-01-31 14:00">
        <TextInput v-model="form.scheduledTime" placeholder="2026-01-31 14:00" :invalid="!!errors.scheduledTime" />
      </Field>
      <Field label="时长（分钟）">
        <TextInput v-model="form.durationMinutes" type="number" />
      </Field>
      <Field label="地点">
        <TextInput v-model="form.location" placeholder="如：3 号会议室 / 线上链接" :maxlength="80" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="面试重点、准备材料（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="updateOpen"
      title="面试改期 / 完成 / 取消"
      :sub="updateTarget ? `${updateTarget.candidateName || ''} · ${updateTarget.roundName || ''}` : ''"
      :width="720"
      :saving="updateSaving"
      @close="updateOpen = false"
      @submit="submitUpdate"
    >
      <Field label="面试状态">
        <Select :model-value="updateForm.status" :options="statusChoices" @update:model-value="pickUpdateStatus" />
      </Field>
      <Field label="面试结论" hint="只有已完成的面试才能给出结论">
        <Select
          :model-value="updateForm.result"
          :options="resultChoices"
          :disabled="updateForm.status !== 'completed'"
          @update:model-value="pickUpdateResult"
        />
      </Field>
      <Field label="面试官">
        <UserSelect :model-value="updateForm.interviewerId" placeholder="选择面试官" @update:model-value="pickUpdateInterviewer" />
      </Field>
      <Field label="面试时间" required hint="格式：2026-01-31 14:00">
        <TextInput v-model="updateForm.scheduledTime" />
      </Field>
      <Field label="时长（分钟）">
        <TextInput v-model="updateForm.durationMinutes" type="number" />
      </Field>
      <Field label="地点">
        <TextInput v-model="updateForm.location" :maxlength="80" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="updateForm.remark" multiline :rows="2" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="feedbackOpen"
      title="结构化评价"
      :sub="feedbackTarget ? `${feedbackTarget.candidateName || ''} · ${feedbackTarget.roundName || ''}` : ''"
      :width="760"
      :saving="feedbackSaving"
      submit-text="提交评价"
      @close="feedbackOpen = false"
      @submit="submitFeedback"
    >
      <div v-for="dimension in INTERVIEW_DIMENSIONS" :key="dimension.code" class="span-2" style="width: 100%">
        <SectionLabel :title="dimension.name" />
        <div class="form-grid" style="margin-top: 8px">
          <Field label="评分（0-100）" required :error="feedbackErrors[dimension.code]">
            <TextInput
              v-model="feedbackForm[dimension.code].score"
              type="number"
              :invalid="!!feedbackErrors[dimension.code]"
            />
          </Field>
          <Field label="评语" required :error="feedbackErrors[`${dimension.code}-comment`]">
            <TextInput
              v-model="feedbackForm[dimension.code].comment"
              placeholder="具体表现与判断依据"
              :invalid="!!feedbackErrors[`${dimension.code}-comment`]"
              :maxlength="300"
            />
          </Field>
        </div>
      </div>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        每个维度都必须评分并填写评语（不能只给一个总分），评价会作为面试记录保留。
      </p>
    </FormDialog>

    <Drawer :open="detailOpen" title="面试详情" :sub="detail ? `${detail.candidateName || ''} · ${detail.roundName || ''}` : ''" :width="760" @close="detailOpen = false">
      <div v-if="detail" class="stack">
        <SpecPlate
          title="面试信息"
          :rows="[
            { label: '候选人', value: detail.candidateName },
            { label: '应聘职位', value: detail.jobTitle },
            { label: '轮次', value: detail.roundName || `第 ${detail.roundNo ?? 1} 轮` },
            { label: '面试官', value: detail.interviewerName },
            { label: '面试时间', value: dateTimeText(detail.scheduledTime) },
            { label: '时长', value: detail.durationMinutes ? `${detail.durationMinutes} 分钟` : null },
            { label: '地点', value: detail.location },
            { label: '状态', value: detail.statusLabel || INTERVIEW_STATUS_TEXT[detail.status] },
            { label: '结论', value: detail.resultLabel || INTERVIEW_RESULT_TEXT[detail.result || 'pending'] },
          ]"
        />
        <div>
          <SectionLabel title="结构化评价" />
          <DataTable
            :columns="[
              { key: 'dimensionName', title: '维度', width: 130 },
              { key: 'score', title: '评分', width: 90, align: 'right' },
              { key: 'comment', title: '评语', minWidth: 220 },
              { key: 'submitTime', title: '提交时间', width: 150 }
            ]"
            :data="detail.feedbacks ?? []"
            empty-text="还没有提交评价"
          >
            <template #cell-dimensionName="{ row }">
              <span class="cell-title">{{ row.dimensionName || row.dimensionCode }}</span>
            </template>
            <template #cell-score="{ row }">
              <span class="tnum">{{ row.score ?? '—' }}</span>
            </template>
            <template #cell-comment="{ row }">
              <span class="muted">{{ row.comment || '—' }}</span>
            </template>
            <template #cell-submitTime="{ row }">
              <span class="tnum soft">{{ dateTimeText(row.submitTime) }}</span>
            </template>
          </DataTable>
        </div>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>
  </ListPage>
</template>
