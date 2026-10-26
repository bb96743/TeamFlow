<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Drawer, Field, SectionLabel, Select, SpecPlate, StatusTag, Tag, TextInput, type Column } from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  recruitApi,
  type RecruitApprovalNode,
  type RecruitCandidateRow,
  type RecruitJobRow,
  type RecruitOfferRow,
  type RecruitOfferTemplateRow,
  type RecruitOnboardResult,
} from '@/api/recruit'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  APPROVAL_INSTANCE_STATUS_TEXT,
  APPROVAL_STATE_TEXT,
  APPROVAL_STATE_TONE,
  NODE_STATUS_TEXT,
  OFFER_STATUS_OPTIONS,
  OFFER_STATUS_TEXT,
  OFFER_STATUS_TONE,
  dateText,
  dateTimeText,
  moneyText,
} from './support'

/* ============================================================
   Offer 管理：从草稿到入职的完整链路——
   草稿 → 提交审批 → 审批通过 → 发送 → 候选人答复 → 入职转用户。
   · 只有草稿可以修改；审批中禁止编辑（后端会拒绝，这里同步禁用），
     审批进展在详情里可见；
   · 入职调用平台用户创建能力（不建用户表），成功后展示生成的用户 ID；
     重复入职是幂等的，会返回同一个用户。
   ============================================================ */

const list = useListPage<RecruitOfferRow, { candidateId: number | null; jobId: number | null; status: string }>({
  query: { candidateId: null, jobId: null, status: '' },
  fetch: params => recruitApi.offers.list(params),
})

const columns: Column[] = [
  { key: 'candidateName', title: '候选人', minWidth: 160 },
  { key: 'jobTitle', title: '应聘职位', minWidth: 150 },
  { key: 'salary', title: '薪资', width: 130, align: 'right' },
  { key: 'expectedOnboardDate', title: '期望入职', width: 120 },
  { key: 'status', title: 'Offer 状态', width: 110 },
  { key: 'approvalState', title: '审批', width: 96 },
  { key: 'sentTime', title: '发送时间', width: 150 },
  { key: 'actions', title: '操作', width: 210, className: 'col-actions' },
]

/* ── 基础数据：职位、候选人、Offer 模板 ── */
const jobs = ref<RecruitJobRow[]>([])
const candidates = ref<RecruitCandidateRow[]>([])
const templates = ref<RecruitOfferTemplateRow[]>([])

const jobOptions = computed(() => jobs.value.map(job => ({ label: job.jobTitle, value: job.id })))
const candidateOptions = computed(() =>
  candidates.value.map(item => ({ label: `${item.nameSnapshot}（${item.jobTitle || '—'}）`, value: item.id })),
)
/** 创建 Offer 只列出处于「面试」阶段的候选人（后端要求） */
const offerableCandidates = computed(() =>
  candidates.value.filter(item => item.currentStage === 'interview').map(item => ({
    label: `${item.nameSnapshot}（${item.jobTitle || '—'}）`,
    value: item.id,
  })),
)
const templateOptions = computed(() => templates.value.map(item => ({ label: item.templateName, value: item.id })))

async function loadBasics() {
  try {
    const [jobPage, candidatePage, templateRows] = await Promise.all([
      recruitApi.jobs.list({ pageNum: 1, pageSize: 200 }),
      recruitApi.candidates.list({ pageNum: 1, pageSize: 200 }),
      recruitApi.offers.templates(),
    ])
    jobs.value = jobPage.rows
    candidates.value = candidatePage.rows
    templates.value = templateRows
  } catch (error) {
    toastError(error, '招聘基础数据加载失败')
  }
}

onMounted(loadBasics)

function pickCandidateFilter(value: string | number | null | undefined) {
  list.query.candidateId = value === null || value === undefined || value === '' ? null : Number(value)
}
function pickJobFilter(value: string | number | null | undefined) {
  list.query.jobId = value === null || value === undefined || value === '' ? null : Number(value)
}

/* ── 新增 / 编辑 Offer ── */
interface OfferForm {
  id?: number
  candidateId: number | null
  templateId: number | null
  salary: number | null
  expectedOnboardDate: string
  remark: string
}
const emptyForm = (): OfferForm => ({ candidateId: null, templateId: null, salary: null, expectedOnboardDate: '', remark: '' })
const dateRule = /^\d{4}-\d{2}-\d{2}$/

const dialog = ref(false)
const saving = ref(false)
const form = reactive<OfferForm>(emptyForm())
const errors = ref<Partial<Record<keyof OfferForm, string>>>({})

function pickFormCandidate(value: string | number | null | undefined) {
  form.candidateId = value === null || value === undefined || value === '' ? null : Number(value)
}
function pickFormTemplate(value: string | number | null | undefined) {
  form.templateId = value === null || value === undefined || value === '' ? null : Number(value)
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined, candidateId: list.query.candidateId })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: RecruitOfferRow) {
  if (row.status !== 'draft') {
    pushToast('只有草稿状态的 Offer 可以修改', 'danger')
    return
  }
  Object.assign(form, {
    id: row.id,
    candidateId: row.candidateId,
    templateId: row.templateId ?? null,
    salary: row.salary ?? null,
    expectedOnboardDate: row.expectedOnboardDate ? dateText(row.expectedOnboardDate) : '',
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    candidateId: [required('请选择候选人')],
    salary: [required('请填写薪资')],
  })
  if (form.expectedOnboardDate && !dateRule.test(form.expectedOnboardDate)) {
    errors.value.expectedOnboardDate = '日期格式为 2026-01-31'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    candidateId: form.candidateId,
    templateId: form.templateId,
    salary: form.salary,
    expectedOnboardDate: form.expectedOnboardDate || null,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await recruitApi.offers.update(form.id, payload)
      pushToast('Offer 已更新')
    } else {
      await recruitApi.offers.create(payload)
      pushToast('Offer 已创建为草稿，可提交审批')
    }
    dialog.value = false
    await list.load()
    loadBasics()
  } catch (error) {
    toastError(error, 'Offer 保存失败')
  } finally {
    saving.value = false
  }
}

/* ── 提交审批 / 审批处理 ── */
const approvalOpen = ref(false)
const approvalSaving = ref(false)
const approvalTarget = ref<RecruitOfferRow | null>(null)
const approvalForm = reactive<{ approverId: number | null }>({ approverId: null })

function openSubmitApproval(row: RecruitOfferRow) {
  approvalTarget.value = row
  approvalForm.approverId = null
  approvalOpen.value = true
}
function pickApprover(value: string | number | null | undefined) {
  approvalForm.approverId = value === null || value === undefined || value === '' ? null : Number(value)
}

async function submitApproval() {
  if (!approvalTarget.value) return
  approvalSaving.value = true
  try {
    await recruitApi.offers.submitApproval(approvalTarget.value.id, approvalForm.approverId)
    pushToast('已提交 Offer 审批')
    approvalOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '提交审批失败')
  } finally {
    approvalSaving.value = false
  }
}

const decideOpen = ref(false)
const decideSaving = ref(false)
const decideTarget = ref<RecruitOfferRow | null>(null)
const decideForm = reactive<{ opinion: string }>({ opinion: '' })

function openDecide(row: RecruitOfferRow) {
  decideTarget.value = row
  decideForm.opinion = ''
  decideOpen.value = true
}

async function decide(approved: boolean) {
  if (!decideTarget.value) return
  decideSaving.value = true
  try {
    await recruitApi.offers.decideApproval(decideTarget.value.id, { approved, opinion: decideForm.opinion })
    pushToast(approved ? '审批已通过，可发送 Offer' : '审批已驳回，可修订后重新提交')
    decideOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '审批处理失败')
  } finally {
    decideSaving.value = false
  }
}

/* ── 发送 / 答复登记 ── */
async function sendOffer(row: RecruitOfferRow) {
  const ok = await confirm({
    title: '发送 Offer',
    message: `确认向「${row.candidateName || '该候选人'}」发送 Offer 吗？发送后可登记候选人的答复。`,
    confirmText: '发送',
  })
  if (!ok) return
  try {
    await recruitApi.offers.send(row.id)
    pushToast('Offer 已发送')
    await list.load()
  } catch (error) {
    toastError(error, '发送失败')
  }
}

const respondOpen = ref(false)
const respondSaving = ref(false)
const respondTarget = ref<RecruitOfferRow | null>(null)
const respondForm = reactive<{ accepted: boolean; reason: string }>({ accepted: true, reason: '' })

function openRespond(row: RecruitOfferRow) {
  respondTarget.value = row
  respondForm.accepted = true
  respondForm.reason = ''
  respondOpen.value = true
}

async function submitRespond() {
  if (!respondTarget.value) return
  if (!respondForm.accepted && !respondForm.reason.trim()) {
    pushToast('候选人拒绝 Offer 时必须填写原因', 'danger')
    return
  }
  respondSaving.value = true
  try {
    await recruitApi.offers.respond(respondTarget.value.id, {
      accepted: respondForm.accepted,
      reason: respondForm.reason,
    })
    pushToast(respondForm.accepted ? '已登记候选人接受 Offer' : '已登记候选人拒绝 Offer')
    respondOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '答复登记失败')
  } finally {
    respondSaving.value = false
  }
}

/* ── 入职转用户 ── */
const onboardOpen = ref(false)
const onboardSaving = ref(false)
const onboardTarget = ref<RecruitOfferRow | null>(null)
const onboardForm = reactive<{ username: string; deptId: number | null }>({ username: '', deptId: null })
const onboardResult = ref<RecruitOnboardResult | null>(null)
const onboardResultOpen = ref(false)

function openOnboard(row: RecruitOfferRow) {
  onboardTarget.value = row
  onboardForm.username = ''
  onboardForm.deptId = null
  onboardOpen.value = true
}

async function submitOnboard() {
  if (!onboardTarget.value) return
  onboardSaving.value = true
  try {
    const result = await recruitApi.offers.onboard(onboardTarget.value.id, {
      username: onboardForm.username.trim() || null,
      deptId: onboardForm.deptId,
    })
    onboardResult.value = result
    onboardOpen.value = false
    onboardResultOpen.value = true
    await list.load()
  } catch (error) {
    toastError(error, '入职处理失败')
  } finally {
    onboardSaving.value = false
  }
}

/* ── 详情（含审批进展） ── */
const detailOpen = ref(false)
const detail = ref<RecruitOfferRow | null>(null)
const detailLoading = ref(false)

async function openDetail(row: RecruitOfferRow) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await recruitApi.offers.detail(row.id)
  } catch (error) {
    toastError(error, 'Offer 详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

const approvalNodes = computed<RecruitApprovalNode[]>(() => detail.value?.approvalHistory?.nodes ?? [])

/* ── Offer 模板维护 ── */
const templateOpen = ref(false)
const templateLoading = ref(false)
const templateDialog = ref(false)
const templateSaving = ref(false)
const templateForm = reactive<{ id?: number; templateCode: string; templateName: string; content: string; status: string; remark: string }>({
  templateCode: '',
  templateName: '',
  content: '',
  status: 'enabled',
  remark: '',
})
const templateErrors = ref<Record<string, string>>({})

async function openTemplates() {
  templateOpen.value = true
  await loadTemplates()
}

async function loadTemplates() {
  templateLoading.value = true
  try {
    templates.value = await recruitApi.offers.templates()
  } catch (error) {
    toastError(error, 'Offer 模板加载失败')
  } finally {
    templateLoading.value = false
  }
}

function openTemplateCreate() {
  Object.assign(templateForm, { id: undefined, templateCode: '', templateName: '', content: '', status: 'enabled', remark: '' })
  templateErrors.value = {}
  templateDialog.value = true
}

function openTemplateEdit(row: RecruitOfferTemplateRow) {
  Object.assign(templateForm, {
    id: row.id,
    templateCode: row.templateCode ?? '',
    templateName: row.templateName,
    content: row.content ?? '',
    status: row.status || 'enabled',
    remark: row.remark ?? '',
  })
  templateErrors.value = {}
  templateDialog.value = true
}

async function submitTemplate() {
  const next: Record<string, string> = {}
  if (!templateForm.templateName.trim()) next.templateName = '请输入模板名称'
  if (!templateForm.templateCode.trim()) next.templateCode = '请输入模板编码'
  if (!templateForm.content.trim()) next.content = '请填写模板正文'
  templateErrors.value = next
  if (Object.keys(next).length) return
  templateSaving.value = true
  const payload = {
    templateCode: templateForm.templateCode.trim(),
    templateName: templateForm.templateName.trim(),
    content: templateForm.content,
    status: templateForm.status,
    remark: templateForm.remark,
  }
  try {
    if (templateForm.id) {
      await recruitApi.offers.updateTemplate(templateForm.id, payload)
      pushToast('Offer 模板已更新')
    } else {
      await recruitApi.offers.createTemplate(payload)
      pushToast('Offer 模板已新增')
    }
    templateDialog.value = false
    await loadTemplates()
    loadBasics()
  } catch (error) {
    toastError(error, 'Offer 模板保存失败')
  } finally {
    templateSaving.value = false
  }
}

function pickTemplateStatus(value: string | number | null | undefined) {
  templateForm.status = String(value ?? 'enabled')
}
</script>

<template>
  <ListPage
    title="Offer 管理"
    desc="Offer 从草稿走到入职：提交审批通过后发送给候选人，登记答复后办理入职；只有草稿可修改，审批中与审批后的状态不可编辑。"
    table-title="Offer 列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <Button variant="secondary" icon="file" @click="openTemplates">Offer 模板</Button>
      <PermissionButton perm="recruit:offer:add" variant="primary" icon="plus" @click="openCreate">新增 Offer</PermissionButton>
    </template>

    <template #search>
      <Field label="候选人" wide>
        <Select
          :model-value="list.query.candidateId"
          :options="[{ label: '全部候选人', value: '' }, ...candidateOptions]"
          filterable
          @update:model-value="pickCandidateFilter"
        />
      </Field>
      <Field label="职位">
        <Select
          :model-value="list.query.jobId"
          :options="[{ label: '全部职位', value: '' }, ...jobOptions]"
          filterable
          @update:model-value="pickJobFilter"
        />
      </Field>
      <Field label="Offer 状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...OFFER_STATUS_OPTIONS]" />
      </Field>
    </template>

    <template #cell-candidateName="{ row }">
      <span class="cell-title">{{ row.candidateName || '—' }}</span>
      <span class="cell-sub">{{ row.templateName || '未关联模板' }}</span>
    </template>
    <template #cell-jobTitle="{ row }">
      <span class="muted">{{ row.jobTitle || '—' }}</span>
    </template>
    <template #cell-salary="{ row }">
      <span class="tnum">{{ moneyText(row.salary) }}</span>
    </template>
    <template #cell-expectedOnboardDate="{ row }">
      <span class="tnum soft">{{ dateText(row.expectedOnboardDate) }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="OFFER_STATUS_TONE[row.status] || 'neutral'" dot>{{ row.statusLabel || OFFER_STATUS_TEXT[row.status] || row.status }}</Tag>
    </template>
    <template #cell-approvalState="{ row }">
      <Tag :tone="APPROVAL_STATE_TONE[row.approvalState || 'none'] || 'neutral'">
        {{ row.approvalStateLabel || APPROVAL_STATE_TEXT[row.approvalState || 'none'] }}
      </Tag>
    </template>
    <template #cell-sentTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.sentTime) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="recruit:offer:list" icon-only icon="eye" label="详情与审批进展" @click="openDetail(row)" />
        <PermissionButton
          perm="recruit:offer:edit"
          icon-only
          icon="edit"
          label="编辑"
          :disabled="row.status !== 'draft'"
          @click="openEdit(row)"
        />
        <PermissionButton
          v-if="row.status === 'draft' || row.status === 'rejected'"
          perm="recruit:offer:edit"
          icon-only
          icon="upload"
          label="提交审批"
          @click="openSubmitApproval(row)"
        />
        <PermissionButton
          v-if="row.status === 'pending_approval'"
          perm="recruit:offer:approve"
          size="sm"
          variant="primary"
          @click="openDecide(row)"
        >审批</PermissionButton>
        <PermissionButton
          v-if="row.status === 'approved'"
          perm="recruit:offer:edit"
          icon-only
          icon="mail"
          label="发送 Offer"
          @click="sendOffer(row)"
        />
        <PermissionButton
          v-if="row.status === 'sent'"
          perm="recruit:offer:edit"
          size="sm"
          @click="openRespond(row)"
        >登记答复</PermissionButton>
        <PermissionButton
          v-if="row.status === 'accepted'"
          perm="recruit:offer:onboard"
          size="sm"
          variant="primary"
          @click="openOnboard(row)"
        >办理入职</PermissionButton>
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑 Offer' : '新增 Offer'"
      sub="同一候选人只允许一份 Offer；创建后候选人会进入 Offer 阶段"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="候选人" required :error="errors.candidateId" span>
        <Select
          :model-value="form.candidateId"
          :options="offerableCandidates"
          placeholder="选择处于面试阶段的候选人"
          filterable
          :invalid="!!errors.candidateId"
          @update:model-value="pickFormCandidate"
        />
      </Field>
      <p v-if="!offerableCandidates.length" class="span-2 faint" style="width: 100%; font-size: 12px">
        目前没有处于「面试」阶段的候选人，请先在候选人库推进阶段。
      </p>
      <Field label="Offer 模板" hint="模板在「Offer 模板」中维护">
        <Select
          :model-value="form.templateId"
          :options="templateOptions"
          placeholder="不使用模板"
          clearable
          @update:model-value="pickFormTemplate"
        />
      </Field>
      <Field label="薪资" required :error="errors.salary">
        <TextInput v-model="form.salary" type="number" placeholder="如：35000" :invalid="!!errors.salary" />
      </Field>
      <Field label="期望入职日期" :error="errors.expectedOnboardDate" hint="格式：2026-01-31">
        <TextInput v-model="form.expectedOnboardDate" placeholder="2026-01-31" :invalid="!!errors.expectedOnboardDate" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="薪酬结构、特殊约定（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="approvalOpen"
      title="提交 Offer 审批"
      :sub="approvalTarget ? `${approvalTarget.candidateName || ''} · ${approvalTarget.jobTitle || ''}` : ''"
      :saving="approvalSaving"
      submit-text="提交审批"
      @close="approvalOpen = false"
      @submit="submitApproval"
    >
      <Field label="审批人" span hint="留空则默认由发起人审批">
        <UserSelect :model-value="approvalForm.approverId" placeholder="选择审批人" @update:model-value="pickApprover" />
      </Field>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        提交后 Offer 进入「审批中」且不可编辑；审批通过后可发送给候选人。
      </p>
    </FormDialog>

    <FormDialog
      :open="decideOpen"
      title="Offer 审批处理"
      :sub="decideTarget ? `${decideTarget.candidateName || ''} · ${decideTarget.jobTitle || ''}` : ''"
      :saving="decideSaving"
      submit-text="通过"
      @close="decideOpen = false"
      @submit="decide(true)"
    >
      <Field label="审批意见" span>
        <TextInput v-model="decideForm.opinion" multiline :rows="2" placeholder="填写通过或驳回的原因（可选）" :maxlength="200" />
      </Field>
      <div class="span-2" style="width: 100%">
        <Button variant="ghost" danger :loading="decideSaving" @click="decide(false)">驳回</Button>
      </div>
    </FormDialog>

    <FormDialog
      :open="respondOpen"
      title="登记候选人答复"
      :sub="respondTarget ? `${respondTarget.candidateName || ''} · ${respondTarget.jobTitle || ''}` : ''"
      :saving="respondSaving"
      submit-text="保存答复"
      @close="respondOpen = false"
      @submit="submitRespond"
    >
      <Field label="答复结果" group>
        <Select
          :model-value="respondForm.accepted ? 'accepted' : 'declined'"
          :options="[{ label: '接受 Offer', value: 'accepted' }, { label: '拒绝 Offer', value: 'declined' }]"
          @update:model-value="respondForm.accepted = String($event) === 'accepted'"
        />
      </Field>
      <Field v-if="!respondForm.accepted" label="拒绝原因" required span hint="会记录在 Offer 上，便于复盘">
        <TextInput v-model="respondForm.reason" multiline :rows="2" placeholder="如：接受了其他公司机会" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="onboardOpen"
      title="办理入职"
      :sub="onboardTarget ? `${onboardTarget.candidateName || ''} · ${onboardTarget.jobTitle || ''}` : ''"
      :saving="onboardSaving"
      submit-text="确认入职"
      @close="onboardOpen = false"
      @submit="submitOnboard"
    >
      <Field label="登录账号" hint="留空则按候选人编号自动生成">
        <TextInput v-model="onboardForm.username" placeholder="如：zhangsan" :maxlength="40" />
      </Field>
      <Field label="所属部门" hint="留空则取职位归属部门">
        <DeptSelect v-model="onboardForm.deptId" />
      </Field>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        入职会创建系统账号并记录候选人对应的用户；重复办理入职是幂等的，会返回同一个用户。
      </p>
    </FormDialog>

    <FormDialog
      :open="onboardResultOpen"
      title="入职完成"
      :saving="false"
      submit-text="知道了"
      @close="onboardResultOpen = false"
      @submit="onboardResultOpen = false"
    >
      <div v-if="onboardResult" class="span-2" style="width: 100%">
        <p style="font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
          {{ onboardResult.message || '入职已完成' }}<br />
          候选人：{{ onboardResult.candidateId }} · 生成用户 ID：<b class="tnum" style="color: var(--ink)">{{ onboardResult.userId }}</b>
          <template v-if="onboardResult.username"> · 登录账号：{{ onboardResult.username }}</template>
          <template v-if="onboardResult.duplicated"><br />（该候选人此前已入职，本次未重复创建用户）</template>
        </p>
      </div>
    </FormDialog>

    <Drawer :open="detailOpen" title="Offer 详情" :sub="detail ? `${detail.candidateName || ''} · ${detail.jobTitle || ''}` : ''" :width="780" @close="detailOpen = false">
      <div v-if="detail" class="stack">
        <SpecPlate
          title="Offer 信息"
          :rows="[
            { label: '候选人', value: detail.candidateName },
            { label: '应聘职位', value: detail.jobTitle },
            { label: 'Offer 模板', value: detail.templateName },
            { label: '薪资', value: moneyText(detail.salary) },
            { label: '期望入职日期', value: dateText(detail.expectedOnboardDate) },
            { label: 'Offer 状态', value: detail.statusLabel || OFFER_STATUS_TEXT[detail.status] },
            { label: '审批状态', value: detail.approvalStateLabel || APPROVAL_STATE_TEXT[detail.approvalState || 'none'] },
            { label: '发送时间', value: dateTimeText(detail.sentTime) },
            { label: '答复时间', value: dateTimeText(detail.acceptedTime) },
            { label: '拒绝原因', value: detail.declinedReason },
            { label: '备注', value: detail.remark },
          ]"
        />
        <div>
          <SectionLabel title="审批进展" />
          <p v-if="!detail.approvalHistory" class="faint" style="font-size: 13px">该 Offer 尚未提交审批。</p>
          <template v-else>
            <p class="faint" style="font-size: 12px; margin-bottom: 8px">
              审批状态：{{ APPROVAL_INSTANCE_STATUS_TEXT[detail.approvalHistory.instanceStatus || ''] || detail.approvalHistory.instanceStatus }}
              · 发起时间 {{ dateTimeText(detail.approvalHistory.startTime) }}
              · 结束时间 {{ dateTimeText(detail.approvalHistory.endTime) }}
            </p>
            <DataTable
              :columns="[
                { key: 'nodeName', title: '节点', width: 110 },
                { key: 'nodeStatus', title: '结果', width: 96 },
                { key: 'operatorName', title: '操作人', width: 100 },
                { key: 'operatedTime', title: '时间', width: 150 },
                { key: 'opinion', title: '意见', minWidth: 160 }
              ]"
              :data="approvalNodes"
              :loading="detailLoading"
              empty-text="还没有审批记录"
            >
              <template #cell-nodeName="{ row }">
                <span class="cell-title">{{ row.nodeName || row.nodeKey || '—' }}</span>
              </template>
              <template #cell-nodeStatus="{ row }">
                <Tag :tone="row.nodeStatus === 'rejected' ? 'danger' : row.nodeStatus === 'completed' ? 'ok' : 'neutral'" dot>
                  {{ NODE_STATUS_TEXT[row.nodeStatus || ''] || row.nodeStatus || '—' }}
                </Tag>
              </template>
              <template #cell-operatorName="{ row }">
                <span>{{ row.operatorName || '—' }}</span>
              </template>
              <template #cell-operatedTime="{ row }">
                <span class="tnum soft">{{ dateTimeText(row.operatedTime) }}</span>
              </template>
              <template #cell-opinion="{ row }">
                <span class="muted">{{ row.opinion || '—' }}</span>
              </template>
            </DataTable>
          </template>
        </div>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>

    <Drawer :open="templateOpen" title="Offer 模板" sub="模板用于统一 Offer 正文，发放前仍需人工确认" :width="820" @close="templateOpen = false">
      <div class="stack">
        <div style="display: flex; justify-content: flex-end">
          <PermissionButton perm="recruit:offer:add" variant="primary" icon="plus" @click="openTemplateCreate">新增模板</PermissionButton>
        </div>
        <DataTable
          :columns="[
            { key: 'templateName', title: '模板名称', minWidth: 160 },
            { key: 'templateCode', title: '模板编码', width: 160 },
            { key: 'content', title: '正文摘要', minWidth: 220 },
            { key: 'status', title: '状态', width: 90 },
            { key: 'actions', title: '操作', width: 90, className: 'col-actions' }
          ]"
          :data="templates"
          :loading="templateLoading"
          empty-text="还没有模板，新增一个再创建 Offer"
        >
          <template #cell-templateName="{ row }">
            <span class="cell-title">{{ row.templateName }}</span>
          </template>
          <template #cell-templateCode="{ row }">
            <span class="code-chip">{{ row.templateCode }}</span>
          </template>
          <template #cell-content="{ row }">
            <span class="muted ellipsis">{{ row.content || '—' }}</span>
          </template>
          <template #cell-status="{ row }">
            <StatusTag :status="row.status === 'enabled' ? 1 : 0" on="启用" off="停用" />
          </template>
          <template #cell-actions="{ row }">
            <div class="row-actions">
              <PermissionButton perm="recruit:offer:edit" icon-only icon="edit" label="编辑" @click="openTemplateEdit(row)" />
            </div>
          </template>
        </DataTable>
      </div>
    </Drawer>

    <FormDialog
      :open="templateDialog"
      :title="templateForm.id ? '编辑 Offer 模板' : '新增 Offer 模板'"
      :width="720"
      :saving="templateSaving"
      @close="templateDialog = false"
      @submit="submitTemplate"
    >
      <Field label="模板名称" required :error="templateErrors.templateName">
        <TextInput v-model="templateForm.templateName" placeholder="如：研发岗标准 Offer" :invalid="!!templateErrors.templateName" :maxlength="60" />
      </Field>
      <Field label="模板编码" required :error="templateErrors.templateCode" hint="唯一标识，建议用英文与短横线">
        <TextInput v-model="templateForm.templateCode" placeholder="如：rd-standard" :invalid="!!templateErrors.templateCode" :maxlength="60" />
      </Field>
      <Field label="状态">
        <Select
          :model-value="templateForm.status"
          :options="[{ label: '启用', value: 'enabled' }, { label: '停用', value: 'disabled' }]"
          @update:model-value="pickTemplateStatus"
        />
      </Field>
      <Field label="模板正文" required :error="templateErrors.content" span>
        <TextInput v-model="templateForm.content" multiline :rows="5" placeholder="Offer 正文，可写薪酬结构、福利、报到须知（含占位符说明）" :invalid="!!templateErrors.content" :maxlength="2000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="templateForm.remark" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
