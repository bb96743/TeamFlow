<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, DataTable, Drawer, Field, SectionLabel, Segmented, Select, SpecPlate, Tag, TextInput, type Column } from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import { ApiError } from '@/api'
import { recruitApi, type RecruitApprovalNode, type RecruitDashboardOverview, type RecruitJobRow } from '@/api/recruit'
import { useListPage } from '@/composables/list'
import { hasPermission } from '@/directives/permission'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { pattern, required, validate } from '@/utils/validate'
import {
  APPROVAL_INSTANCE_STATUS_TEXT,
  APPROVAL_STATE_TEXT,
  APPROVAL_STATE_TONE,
  JOB_STATUS_OPTIONS,
  JOB_STATUS_TEXT,
  JOB_STATUS_TONE,
  NODE_STATUS_TEXT,
  dateTimeText,
  rangeOfDays,
} from './support'

/* ============================================================
   职位管理：招聘流程的起点。
   · 顶部漏斗把「职位 → 投递 → 面试 → Offer → 入职」串起来（M2 里程碑的界面体现）；
   · 需求要走审批：草稿 → 提交需求审批 → 审批通过后进入招聘中；
   · 审批中与已关闭的职位禁止编辑（后端会拒绝，这里同步禁用按钮）。
   ============================================================ */

const list = useListPage<RecruitJobRow, { keyword: string; status: string; deptId: number | null }>({
  query: { keyword: '', status: '', deptId: null },
  fetch: params => recruitApi.jobs.list(params),
})

const columns: Column[] = [
  { key: 'jobTitle', title: '职位', minWidth: 220 },
  { key: 'deptName', title: '归属部门', width: 120 },
  { key: 'headcount', title: '招聘人数', width: 96, align: 'right' },
  { key: 'progress', title: '招聘进展', width: 140 },
  { key: 'status', title: '状态', width: 110 },
  { key: 'approvalState', title: '需求审批', width: 100 },
  { key: 'ownerName', title: '招聘负责人', width: 110 },
  { key: 'updateTime', title: '更新时间', width: 150 },
  { key: 'actions', title: '操作', width: 210, className: 'col-actions' },
]

/* ── 招聘漏斗（职位 → 投递 → 面试 → Offer → 入职） ── */
const canViewDashboard = computed(() => hasPermission('recruit:dashboard:view'))
const range = ref(90)
const RANGE_OPTIONS = [
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
  { label: '近 365 天', value: 365 },
]
const overview = ref<RecruitDashboardOverview | null>(null)
const overviewLoading = ref(false)
/** 看板要求指标口径先在 M13 指标字典登记（2.1.5）；未登记时给出登记入口而不是只报错 */
const needRegisterMetrics = ref(false)
const dashboardHint = ref('')
const registering = ref(false)

async function loadOverview() {
  if (!canViewDashboard.value) return
  const { startDate, endDate } = rangeOfDays(range.value)
  overviewLoading.value = true
  try {
    overview.value = await recruitApi.dashboard.overview(startDate, endDate)
    needRegisterMetrics.value = false
    dashboardHint.value = ''
  } catch (error) {
    overview.value = null
    // 后端在指标口径未登记时返回 400 并说明原因，这里把原因原样展示并提供登记入口
    if (error instanceof ApiError && (error.message.includes('登记') || error.message.includes('register-metrics'))) {
      needRegisterMetrics.value = true
      dashboardHint.value = error.message
    } else {
      toastError(error, '招聘漏斗加载失败')
    }
  } finally {
    overviewLoading.value = false
  }
}

async function registerMetrics() {
  registering.value = true
  try {
    await recruitApi.dashboard.registerMetrics()
    pushToast('招聘指标口径已登记，看板恢复出数')
    needRegisterMetrics.value = false
    await loadOverview()
  } catch (error) {
    toastError(error, '指标口径登记失败')
  } finally {
    registering.value = false
  }
}

interface FunnelBar { label: string; value: number; share: number; conversion: number | null }
const funnelBars = computed<FunnelBar[]>(() => {
  const data = overview.value
  if (!data) return []
  const summary = data.summary ?? {}
  const stages = data.funnel ?? []
  const candidateBase = stages.find(stage => stage.stage === 'candidates')?.value ?? summary.candidateCount ?? 0
  const rows: Array<{ label: string; value: number; conversion: number | null }> = [
    { label: '在招职位', value: summary.jobCount ?? 0, conversion: null },
    ...stages.map((stage, index) => ({
      label: stage.label,
      value: stage.value ?? 0,
      conversion: index === 0 ? null : (stage.conversionRate ?? null),
    })),
  ]
  const max = Math.max(1, ...rows.map(row => row.value))
  return rows.map(row => ({
    ...row,
    share: Math.round((row.value / max) * 1000) / 10,
  }))
})
const funnelPeriod = computed(() => {
  const period = overview.value?.period
  return period?.startDate && period?.endDate ? `${period.startDate} ~ ${period.endDate}` : ''
})

function pickRange(value: number) {
  range.value = Number(value)
  loadOverview()
}

function pickDeptFilter(value: string | number | null | undefined) {
  list.query.deptId = value === null || value === '' ? null : Number(value)
}

/* ── 表单 ── */
interface JobForm {
  id?: number
  jobCode: string
  jobTitle: string
  jobDesc: string
  deptId: number | null
  headcount: number | null
  jobLevel: string
  salaryRange: string
  city: string
  ownerId: number | null
  remark: string
}
const emptyForm = (): JobForm => ({
  jobCode: '',
  jobTitle: '',
  jobDesc: '',
  deptId: null,
  headcount: 1,
  jobLevel: '',
  salaryRange: '',
  city: '',
  ownerId: null,
  remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const form = reactive<JobForm>(emptyForm())
const errors = ref<Partial<Record<keyof JobForm, string>>>({})

function pickOwner(value: string | number | null | undefined) {
  form.ownerId = value === null || value === '' ? null : Number(value)
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: RecruitJobRow) {
  if (!editable(row)) {
    pushToast('该职位当前不可编辑', 'danger')
    return
  }
  Object.assign(form, {
    id: row.id,
    jobCode: row.jobCode ?? '',
    jobTitle: row.jobTitle,
    jobDesc: row.jobDesc ?? '',
    deptId: row.deptId ?? null,
    headcount: row.headcount ?? 1,
    jobLevel: row.jobLevel ?? '',
    salaryRange: row.salaryRange ?? '',
    city: row.city ?? '',
    ownerId: row.ownerId ?? null,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

/** 审批中与已关闭的职位后端会拒绝修改，这里同步禁用 */
function editable(row: RecruitJobRow): boolean {
  return row.status !== 'pending_approval' && row.status !== 'closed'
}

async function submit() {
  errors.value = validate(form, {
    jobTitle: [required('请输入职位名称')],
    headcount: [pattern(/^\d+$/, '招聘人数需为正整数')],
  })
  // 职位编码只在创建时必填（后端创建时校验，创建后不可修改）
  if (!form.id) {
    const codeError = required('请输入职位编码')(form.jobCode)
    if (codeError) errors.value.jobCode = codeError
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    jobCode: form.jobCode.trim() || undefined,
    jobTitle: form.jobTitle.trim(),
    jobDesc: form.jobDesc,
    deptId: form.deptId,
    headcount: form.headcount,
    jobLevel: form.jobLevel,
    salaryRange: form.salaryRange,
    city: form.city,
    ownerId: form.ownerId,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await recruitApi.jobs.update(form.id, payload)
      pushToast('职位已更新')
    } else {
      await recruitApi.jobs.create(payload)
      pushToast('职位已新增为草稿，可提交需求审批')
    }
    dialog.value = false
    await list.load()
    loadOverview()
  } catch (error) {
    toastError(error, '职位保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: RecruitJobRow) {
  const ok = await confirm({
    title: '删除职位',
    message: `确认删除「${row.jobTitle}」吗？只有草稿且没有候选人的职位可以删除，否则请改为关闭。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await recruitApi.jobs.remove(row.id)
    pushToast('职位已删除')
    await list.load()
    loadOverview()
  } catch (error) {
    toastError(error, '职位删除失败')
  }
}

/* ── 状态流转（目标状态来自后端 nextStatuses） ── */
const statusOpen = ref(false)
const statusTarget = ref<RecruitJobRow | null>(null)
const statusForm = reactive<{ status: string }>({ status: '' })
const statusSaving = ref(false)
const statusOptions = computed(() =>
  (statusTarget.value?.nextStatuses ?? [])
    .filter(status => status !== 'pending_approval')
    .map(status => ({ label: JOB_STATUS_TEXT[status] || status, value: status })),
)

function openStatus(row: RecruitJobRow) {
  statusTarget.value = row
  statusForm.status = statusOptions.value[0]?.value ?? ''
  statusOpen.value = true
}

async function submitStatus() {
  if (!statusTarget.value || !statusForm.status) return
  statusSaving.value = true
  try {
    await recruitApi.jobs.updateStatus(statusTarget.value.id, statusForm.status)
    pushToast(`职位已变更为「${JOB_STATUS_TEXT[statusForm.status] || statusForm.status}」`)
    statusOpen.value = false
    await list.load()
    loadOverview()
  } catch (error) {
    toastError(error, '状态变更失败')
  } finally {
    statusSaving.value = false
  }
}

/* ── 提交需求审批 / 审批处理 ── */
const submitApprovalOpen = ref(false)
const approvalTarget = ref<RecruitJobRow | null>(null)
const approvalForm = reactive<{ approverId: number | null }>({ approverId: null })
const approvalSaving = ref(false)

function openSubmitApproval(row: RecruitJobRow) {
  approvalTarget.value = row
  approvalForm.approverId = null
  submitApprovalOpen.value = true
}
function pickApprover(value: string | number | null | undefined) {
  approvalForm.approverId = value === null || value === '' ? null : Number(value)
}

async function submitApproval() {
  if (!approvalTarget.value) return
  approvalSaving.value = true
  try {
    await recruitApi.jobs.submitApproval(approvalTarget.value.id, approvalForm.approverId)
    pushToast('已提交需求审批，审批通过后职位进入招聘中')
    submitApprovalOpen.value = false
    await list.load()
  } catch (error) {
    toastError(error, '提交审批失败')
  } finally {
    approvalSaving.value = false
  }
}

const decideOpen = ref(false)
const decideTarget = ref<RecruitJobRow | null>(null)
const decideForm = reactive<{ opinion: string }>({ opinion: '' })
const decideSaving = ref(false)

function openDecide(row: RecruitJobRow) {
  decideTarget.value = row
  decideForm.opinion = ''
  decideOpen.value = true
}

async function decide(approved: boolean) {
  if (!decideTarget.value) return
  decideSaving.value = true
  try {
    await recruitApi.jobs.decideApproval(decideTarget.value.id, { approved, opinion: decideForm.opinion })
    pushToast(approved ? '审批已通过，职位进入招聘中' : '审批已驳回，职位回到草稿')
    decideOpen.value = false
    await list.load()
    loadOverview()
  } catch (error) {
    toastError(error, '审批处理失败')
  } finally {
    decideSaving.value = false
  }
}

/* ── 详情（JD + 审批进展） ── */
const detailOpen = ref(false)
const detail = ref<RecruitJobRow | null>(null)
const detailLoading = ref(false)

async function openDetail(row: RecruitJobRow) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await recruitApi.jobs.detail(row.id)
  } catch (error) {
    toastError(error, '职位详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

const approvalNodes = computed<RecruitApprovalNode[]>(() => detail.value?.approvalHistory?.nodes ?? [])

/** 行内「状态流转」可用目标：后端 nextStatuses 去掉需要走「提交需求审批」的那一步 */
function statusOptionsFor(row: RecruitJobRow): string[] {
  return (row.nextStatuses ?? []).filter(status => status !== 'pending_approval')
}

onMounted(() => {
  loadOverview()
})
</script>

<template>
  <ListPage
    title="职位管理"
    desc="职位是招聘流程的起点：新建职位为草稿，提交需求审批通过后进入招聘中，候选人与 Offer 都挂在职位下；审批中与已关闭的职位不可修改。"
    table-title="职位列表"
    :list="list"
    :columns="columns"
  >
    <template #before>
      <section v-if="canViewDashboard" class="band reveal">
        <div class="band-head">
          <SectionLabel title="招聘漏斗" />
          <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
            <span v-if="funnelPeriod" class="eyebrow">{{ funnelPeriod }}</span>
            <Segmented :model-value="range" label="统计区间" :options="RANGE_OPTIONS" @update:model-value="pickRange" />
          </div>
        </div>
        <ul v-if="funnelBars.length" class="share-list">
          <li v-for="(bar, index) in funnelBars" :key="bar.label" :class="`cat-${(index % 5) + 1}`">
            <span class="share-label">{{ bar.label }}</span>
            <span class="share-track"><i :style="{ width: `${bar.share}%` }" /></span>
            <span class="share-value tnum">
              {{ bar.value }}
              <small v-if="bar.conversion !== null"> · 转化 {{ bar.conversion }}%</small>
              <small v-else> {{ index === 0 ? '条需求' : '人' }}</small>
            </span>
          </li>
        </ul>
        <p v-else-if="needRegisterMetrics" class="faint" style="font-size: 12px; line-height: 1.8; padding: 4px 0">
          招聘指标口径还没有在指标字典中登记，看板按规则暂不出数（避免口径不明导致误读）。
          登记后即可看到「投递 → 面试 → Offer → 入职」各阶段人数与转化率。
        </p>        <div v-if="needRegisterMetrics" style="margin-top: 10px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
          <PermissionButton perm="recruit:dashboard:register" variant="secondary" icon="check" :loading="registering" @click="registerMetrics">
            登记指标口径
          </PermissionButton>
          <span v-if="dashboardHint" class="faint" style="font-size: 11.5px; max-width: 620px">{{ dashboardHint }}</span>
        </div>
        <p v-else class="faint" style="font-size: 12px; padding: 8px 0" v-show="!funnelBars.length">
          {{ overviewLoading ? '漏斗数据加载中…' : '所选区间内还没有招聘数据' }}
        </p>
        <p class="faint" style="margin-top: 10px; font-size: 12px; line-height: 1.8">
          漏斗按所选区间统计：投递 / 入库 → 进入面试 → 拿到 Offer → 入职，转化率是相对上一阶段的比例。
        </p>
      </section>
    </template>

    <template #actions>
      <PermissionButton perm="recruit:job:add" variant="primary" icon="plus" @click="openCreate">新增职位</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="职位名称 / 编码" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...JOB_STATUS_OPTIONS]" />
      </Field>
      <Field label="归属部门">
        <DeptSelect :model-value="list.query.deptId" placeholder="全部部门" @update:model-value="pickDeptFilter" />
      </Field>
    </template>

    <template #cell-jobTitle="{ row }">
      <span class="cell-title">{{ row.jobTitle }}</span>
      <span v-if="row.jobCode" class="cell-sub code-chip">{{ row.jobCode }}</span>
    </template>
    <template #cell-deptName="{ row }">
      <span class="muted">{{ row.deptName || '—' }}</span>
    </template>
    <template #cell-headcount="{ row }">
      <span class="tnum">{{ row.headcount ?? 0 }}</span>
    </template>
    <template #cell-progress="{ row }">
      <span class="tnum soft">候选人 {{ row.candidateCount ?? 0 }} · 已入职 {{ row.hiredCount ?? 0 }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="JOB_STATUS_TONE[row.status] || 'neutral'" dot>{{ row.statusLabel || JOB_STATUS_TEXT[row.status] || row.status }}</Tag>
    </template>
    <template #cell-approvalState="{ row }">
      <Tag :tone="APPROVAL_STATE_TONE[row.approvalState || 'none'] || 'neutral'">
        {{ row.approvalStateLabel || APPROVAL_STATE_TEXT[row.approvalState || 'none'] || '未提交' }}
      </Tag>
    </template>
    <template #cell-ownerName="{ row }">
      <span>{{ row.ownerName || '—' }}</span>
    </template>
    <template #cell-updateTime="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.updateTime) }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="recruit:job:list" icon-only icon="eye" label="详情与审批进展" @click="openDetail(row)" />
        <PermissionButton
          perm="recruit:job:edit"
          icon-only
          icon="edit"
          label="编辑"
          :disabled="!editable(row)"
          @click="openEdit(row)"
        />
        <PermissionButton
          v-if="row.status === 'draft'"
          perm="recruit:job:edit"
          icon-only
          icon="upload"
          label="提交需求审批"
          @click="openSubmitApproval(row)"
        />
        <PermissionButton
          v-if="row.approvalState === 'pending'"
          perm="recruit:job:approve"
          size="sm"
          variant="primary"
          @click="openDecide(row)"
        >审批</PermissionButton>
        <PermissionButton
          perm="recruit:job:edit"
          icon-only
          icon="arrowRight"
          label="状态流转"
          :disabled="!statusOptionsFor(row).length"
          @click="openStatus(row)"
        />
        <PermissionButton
          v-if="row.status === 'draft'"
          perm="recruit:job:remove"
          icon-only
          icon="trash"
          label="删除"
          danger
          @click="remove(row)"
        />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑职位' : '新增职位'"
      sub="新建职位为草稿；JD 建议写清职责、要求与工作地点"
      :width="760"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="职位名称" required :error="errors.jobTitle">
        <TextInput v-model="form.jobTitle" placeholder="如：高级前端工程师" :invalid="!!errors.jobTitle" :maxlength="60" />
      </Field>
      <Field label="职位编码" :required="!form.id" :error="errors.jobCode" :hint="form.id ? '编码创建后不可修改' : '唯一编码，便于与外部招聘渠道对账'">
        <TextInput v-model="form.jobCode" placeholder="如：RD-FE-001" :invalid="!!errors.jobCode" :readonly="!!form.id" :maxlength="40" />
      </Field>
      <Field label="归属部门">
        <DeptSelect v-model="form.deptId" />
      </Field>
      <Field label="招聘人数" :error="errors.headcount">
        <TextInput v-model="form.headcount" type="number" />
      </Field>
      <Field label="职级">
        <TextInput v-model="form.jobLevel" placeholder="如：P6 / 高级" :maxlength="20" />
      </Field>
      <Field label="薪资范围">
        <TextInput v-model="form.salaryRange" placeholder="如：25k-40k" :maxlength="40" />
      </Field>
      <Field label="工作城市">
        <TextInput v-model="form.city" placeholder="如：上海" :maxlength="40" />
      </Field>
      <Field label="招聘负责人" hint="默认当前账号">
        <UserSelect :model-value="form.ownerId" placeholder="选择负责人" @update:model-value="pickOwner" />
      </Field>
      <Field label="职位描述（JD）" span>
        <TextInput v-model="form.jobDesc" multiline :rows="4" placeholder="职责、任职要求、团队介绍（可选）" :maxlength="2000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="内部备注（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="submitApprovalOpen"
      title="提交需求审批"
      :sub="approvalTarget ? approvalTarget.jobTitle : ''"
      :saving="approvalSaving"
      submit-text="提交审批"
      @close="submitApprovalOpen = false"
      @submit="submitApproval"
    >
      <Field label="审批人" span hint="留空则默认由职位创建人审批">
        <UserSelect :model-value="approvalForm.approverId" placeholder="选择审批人" @update:model-value="pickApprover" />
      </Field>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        提交后职位进入「需求审批中」，审批通过自动转为「招聘中」；驳回则回到草稿可继续修订。
      </p>
    </FormDialog>

    <FormDialog
      :open="statusOpen"
      title="职位状态流转"
      :sub="statusTarget ? `${statusTarget.jobTitle} · 当前：${JOB_STATUS_TEXT[statusTarget.status] || statusTarget.status}` : ''"
      :saving="statusSaving"
      submit-text="确认变更"
      @close="statusOpen = false"
      @submit="submitStatus"
    >
      <Field label="变更为" required span hint="可选目标由后端状态机给出">
        <Select v-model="statusForm.status" :options="statusOptions" placeholder="选择目标状态" />
      </Field>
      <p v-if="!statusOptions.length" class="span-2 faint" style="width: 100%; font-size: 13px">
        当前状态没有可变更的目标状态（已关闭的职位为终态）。
      </p>
    </FormDialog>

    <FormDialog
      :open="decideOpen"
      title="需求审批处理"
      :sub="decideTarget ? decideTarget.jobTitle : ''"
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

    <Drawer :open="detailOpen" title="职位详情" :sub="detail?.jobTitle || ''" :width="780" @close="detailOpen = false">
      <div v-if="detail" class="stack">
        <SpecPlate
          title="职位信息"
          :rows="[
            { label: '职位编码', value: detail.jobCode },
            { label: '归属部门', value: detail.deptName },
            { label: '招聘负责人', value: detail.ownerName },
            { label: '招聘人数', value: detail.headcount },
            { label: '职级', value: detail.jobLevel },
            { label: '薪资范围', value: detail.salaryRange },
            { label: '工作城市', value: detail.city },
            { label: '职位状态', value: detail.statusLabel || JOB_STATUS_TEXT[detail.status] || detail.status },
            { label: '需求审批', value: detail.approvalStateLabel || APPROVAL_STATE_TEXT[detail.approvalState || 'none'] },
            { label: '候选人 / 已入职', value: `${detail.candidateCount ?? 0} / ${detail.hiredCount ?? 0}` },
            { label: '发布时间', value: dateTimeText(detail.publishTime) },
            { label: '关闭时间', value: dateTimeText(detail.closeTime) },
          ]"
        />
        <div>
          <SectionLabel title="职位描述" />
          <p style="white-space: pre-wrap; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
            {{ detail.jobDesc || '未填写职位描述' }}
          </p>
        </div>
        <div>
          <SectionLabel title="需求审批进展" />
          <p v-if="!detail.approvalHistory" class="faint" style="font-size: 13px">该职位尚未提交需求审批。</p>
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
        <p class="faint" style="font-size: 12px">
          候选人、面试与 Offer 分别在「候选人库」「面试安排」「Offer 管理」中处理；Offer 模板也在 Offer 管理页维护。
        </p>
      </div>
      <p v-else class="faint" style="font-size: 13px">{{ detailLoading ? '加载中…' : '暂无数据' }}</p>
    </Drawer>
  </ListPage>
</template>
