<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  DataTable,
  Drawer,
  Field,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import {
  projectApi,
  type ProjectRiskLogRow,
  type ProjectRiskRow,
  type ProjectRiskStatusMachine,
  type ProjectRiskWriteRequest,
} from '@/api/project'
import { required, validate } from '@/utils/validate'
import { useProjectPicker } from './useProjectPicker'

/* ============================================================
   风险登记（M14 · 2.5.3）
   ------------------------------------------------------------
   风险归属项目；状态按「已识别 → 已评估 → 应对中 → 已关闭」
   （可取消）流转，可流转目标由后端每行 nextStatuses / 状态机给出，
   非法流转不展示、后端也会 400。每次流转由后端写留痕，页面可查。
   项目归档后风险只读（后端 400），页面隐藏写操作并提示。
   ============================================================ */

const picker = useProjectPicker()
const riskStatusMachine = ref<ProjectRiskStatusMachine | null>(null)

const RISK_LABEL_FALLBACK: Record<string, string> = {
  identified: '已识别', assessed: '已评估', responding: '应对中', closed: '已关闭', cancelled: '已取消',
}
const LEVEL_OPTIONS = [
  { label: '低', value: 'low' },
  { label: '中', value: 'medium' },
  { label: '高', value: 'high' },
  { label: '严重', value: 'critical' },
]
const TYPE_OPTIONS = [
  { label: '进度', value: 'schedule' },
  { label: '成本', value: 'cost' },
  { label: '质量', value: 'quality' },
  { label: '资源', value: 'resource' },
  { label: '其他', value: 'other' },
]
const LEVEL_TONE: Record<string, string> = { low: 'neutral', medium: 'info', high: 'danger', critical: 'danger' }

const riskLabelOf = (code: string | null | undefined) =>
  riskStatusMachine.value?.states.find(state => state.code === code)?.label
  ?? (code ? RISK_LABEL_FALLBACK[code] : undefined)
  ?? code
  ?? '—'
const statusOptions = computed(() => [
  { label: '全部状态', value: '' },
  ...(riskStatusMachine.value?.states ?? []).map(state => ({ label: state.label, value: state.code })),
])

const list = useListPage<ProjectRiskRow, { status: string }>({
  query: { status: '' },
  immediate: false,
  fetch: params => {
    const projectId = picker.selectedProjectId.value
    if (!projectId) {
      return Promise.resolve({ rows: [], total: 0, pageNum: 1, pageSize: Number(params.pageSize ?? 10) })
    }
    return projectApi.risks.list(projectId, params)
  },
})

const archived = computed(() => picker.selectedProject.value?.status === 'archived')

onMounted(async () => {
  try {
    riskStatusMachine.value = await projectApi.risks.statusMachine()
  } catch {
    riskStatusMachine.value = null
  }
  await picker.loadProjects()
})
watch(picker.selectedProjectId, () => {
  if (picker.selectedProjectId.value) list.search()
  else list.rows.value = []
})

const columns: Column[] = [
  { key: 'riskTitle', title: '风险', minWidth: 260 },
  { key: 'riskLevel', title: '等级', width: 90 },
  { key: 'riskType', title: '类别', width: 90 },
  { key: 'ownerName', title: '责任人', width: 120 },
  { key: 'status', title: '状态', width: 120 },
  { key: 'dueDate', title: '计划应对完成', width: 150 },
  { key: 'responsePlan', title: '应对措施', minWidth: 200 },
  { key: 'actions', title: '操作', width: 170, className: 'col-actions' },
]

/* ── 登记 / 编辑 ── */
interface RiskForm {
  id?: number
  riskTitle: string
  riskDesc: string
  riskLevel: string
  riskType: string
  ownerId: number | null
  responsePlan: string
  dueDate: string
  remark: string
}
const emptyForm = (): RiskForm => ({
  riskTitle: '',
  riskDesc: '',
  riskLevel: 'medium',
  riskType: 'schedule',
  ownerId: null,
  responsePlan: '',
  dueDate: '',
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<RiskForm>(emptyForm())
const errors = ref<Partial<Record<keyof RiskForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: ProjectRiskRow) {
  Object.assign(form, {
    id: row.id,
    riskTitle: row.riskTitle,
    riskDesc: row.riskDesc ?? '',
    riskLevel: row.riskLevel,
    riskType: row.riskType,
    ownerId: row.ownerId ?? null,
    responsePlan: row.responsePlan ?? '',
    dueDate: row.dueDate ?? '',
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitRisk() {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  errors.value = validate(form, { riskTitle: [required('请填写风险标题')] })
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: ProjectRiskWriteRequest = {
    riskTitle: form.riskTitle.trim(),
    riskDesc: form.riskDesc.trim() || null,
    riskLevel: form.riskLevel,
    riskType: form.riskType,
    ownerId: form.ownerId,
    responsePlan: form.responsePlan.trim() || null,
    dueDate: form.dueDate || null,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await projectApi.risks.update(projectId, form.id, payload)
      pushToast('风险已更新')
    } else {
      await projectApi.risks.create(projectId, payload)
      pushToast('风险已登记，状态为已识别')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '风险保存失败')
  } finally {
    saving.value = false
  }
}

async function removeRisk(row: ProjectRiskRow) {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  const ok = await confirm({
    title: '删除风险',
    message: `确认删除风险「${row.riskTitle}」吗？删除后不再出现在风险清单与汇总中。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await projectApi.risks.remove(projectId, row.id)
    pushToast('风险已删除')
    await list.load()
  } catch (error) {
    toastError(error, '风险删除失败')
  }
}

/* ── 状态流转（可流转目标由后端给出，非法流转不展示） ── */
const statusDialog = ref(false)
const statusSaving = ref(false)
const statusRow = ref<ProjectRiskRow | null>(null)
const statusTarget = ref('')
const statusNote = ref('')
const statusError = ref('')

const statusChoices = computed(() => (statusRow.value?.nextStatuses ?? []).map(code => ({
  label: riskLabelOf(code),
  value: code,
})))

function openStatus(row: ProjectRiskRow) {
  statusRow.value = row
  statusTarget.value = row.nextStatuses?.[0] ?? ''
  statusNote.value = ''
  statusError.value = ''
  statusDialog.value = true
}

async function submitStatus() {
  const projectId = picker.selectedProjectId.value
  if (!projectId || !statusRow.value) return
  if (!statusTarget.value) {
    statusError.value = '请选择要流转到的风险状态'
    return
  }
  statusSaving.value = true
  try {
    const updated = await projectApi.risks.changeStatus(projectId, statusRow.value.id, {
      status: statusTarget.value,
      note: statusNote.value.trim() || null,
    })
    pushToast(`风险状态已流转为「${updated.statusLabel || riskLabelOf(updated.status)}」`)
    statusDialog.value = false
    await list.load()
    if (detailOpen.value) await loadDetail(updated.id)
  } catch (error) {
    toastError(error, '风险状态流转失败')
  } finally {
    statusSaving.value = false
  }
}

/* ── 详情与留痕 ── */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectRiskRow | null>(null)
const detailLogs = ref<ProjectRiskLogRow[]>([])

async function openDetail(row: ProjectRiskRow) {
  detailOpen.value = true
  await loadDetail(row.id)
}

async function loadDetail(riskId: number) {
  const projectId = picker.selectedProjectId.value
  if (!projectId) return
  detailLoading.value = true
  try {
    const data = await projectApi.risks.detail(projectId, riskId)
    detail.value = data
    detailLogs.value = data.logs ?? []
  } catch (error) {
    toastError(error, '风险详情加载失败')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <ListPage
    title="风险登记"
    desc="按项目登记风险与应对措施。状态按「已识别 → 已评估 → 应对中 → 已关闭」推进（可取消），可流转目标由状态机给出，非法流转不展示且会被拒绝；每次流转留痕可查。"
    table-title="风险清单"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <Select
        :model-value="picker.selectedProjectId.value"
        :options="picker.options.value"
        :placeholder="picker.loading.value ? '项目加载中…' : '请选择项目'"
        filterable
        @update:model-value="picker.selectProject($event)"
      />
      <PermissionButton
        v-if="!archived"
        perm="project:risk:add"
        variant="primary"
        icon="plus"
        @click="openCreate"
      >登记风险</PermissionButton>
      <Tag v-else tone="neutral">项目已归档，风险只读</Tag>
    </template>

    <template #before>
      <p v-if="!picker.selectedProjectId.value && !picker.loading.value" class="faint">
        请先选择项目；风险按项目归属，归档项目的风险只读。
      </p>
    </template>

    <template #search>
      <Field label="风险状态">
        <Select v-model="list.query.status" :options="statusOptions" />
      </Field>
    </template>

    <template #cell-riskTitle="{ row }">
      <div>
        <button type="button" class="risk-title-btn" @click="openDetail(row)">
          {{ row.riskTitle }}
        </button>
        <div class="cell-sub">{{ row.riskDesc || '未填写风险描述' }}</div>
      </div>
    </template>
    <template #cell-riskLevel="{ row }">
      <Tag :tone="LEVEL_TONE[row.riskLevel] ?? 'neutral'">{{ row.riskLevelLabel || row.riskLevel }}</Tag>
    </template>
    <template #cell-riskType="{ row }">
      <span class="soft">{{ row.riskTypeLabel || row.riskType }}</span>
    </template>
    <template #cell-ownerName="{ row }">
      <span class="muted">{{ row.ownerName || '未指定' }}</span>
    </template>
    <template #cell-status="{ row }">
      <div>
        <Tag
          :tone="row.status === 'closed' ? 'ok' : row.status === 'cancelled' ? 'neutral' : row.delayed ? 'danger' : 'info'"
          dot
        >{{ row.statusLabel || riskLabelOf(row.status) }}</Tag>
        <div v-if="row.delayed" class="cell-sub">已过计划应对日期</div>
      </div>
    </template>
    <template #cell-dueDate="{ row }">
      <span class="tnum soft">{{ row.dueDate || '—' }}</span>
    </template>
    <template #cell-responsePlan="{ row }">
      <span class="cell-sub">{{ row.responsePlan || '未填写应对措施' }}</span>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="project:risk:list" icon-only icon="eye" label="风险详情与留痕" @click="openDetail(row)" />
        <PermissionButton
          v-if="row.nextStatuses && row.nextStatuses.length && !archived"
          perm="project:risk:status"
          icon-only
          icon="arrowRight"
          label="风险状态流转"
          @click="openStatus(row)"
        />
        <PermissionButton
          v-if="!archived"
          perm="project:risk:edit"
          icon-only
          icon="edit"
          label="编辑风险"
          @click="openEdit(row)"
        />
        <PermissionButton
          v-if="!archived"
          perm="project:risk:remove"
          icon-only
          icon="trash"
          label="删除风险"
          danger
          @click="removeRisk(row)"
        />
        <span
          v-if="archived"
          class="cell-sub"
        >只读</span>
        <span
          v-else-if="!(row.nextStatuses && row.nextStatuses.length)"
          class="cell-sub"
        >已终态</span>
      </div>
    </template>


    <!-- 风险表单 -->
    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑风险' : '登记风险'"
      sub="风险必须写明责任人、应对措施与计划关闭日期，便于事后追溯；状态流转由专门入口操作，不在此处直接改状态。"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submitRisk"
    >
      <Field label="风险标题" required :error="errors.riskTitle" span>
        <TextInput v-model="form.riskTitle" placeholder="如：核心接口联调依赖外部系统，可能延期两周" :invalid="!!errors.riskTitle" :maxlength="200" />
      </Field>
      <Field label="风险等级">
        <Select v-model="form.riskLevel" :options="LEVEL_OPTIONS" />
      </Field>
      <Field label="风险类别">
        <Select v-model="form.riskType" :options="TYPE_OPTIONS" />
      </Field>
      <Field label="责任人">
        <UserSelect v-model="form.ownerId" placeholder="请选择风险责任人" />
      </Field>
      <Field label="计划应对完成日期" hint="yyyy-MM-dd；过期未关闭会标记逾期">
        <TextInput v-model="form.dueDate" placeholder="如：2026-12-20" />
      </Field>
      <Field label="应对措施" span>
        <TextInput v-model="form.responsePlan" multiline :rows="3" placeholder="打算怎么应对、谁跟进、何时完成" :maxlength="2000" />
      </Field>
      <Field label="风险描述" span>
        <TextInput v-model="form.riskDesc" multiline :rows="3" placeholder="风险的影响范围与触发条件" :maxlength="2000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" placeholder="其他需要说明的信息" :maxlength="500" />
      </Field>
    </FormDialog>

    <!-- 状态流转 -->
    <FormDialog
      :open="statusDialog"
      title="风险状态流转"
      sub="可流转目标由风险状态机给出；每次流转都会写入留痕，可在详情中查看。"
      :saving="statusSaving"
      submit-text="确认流转"
      @close="statusDialog = false"
      @submit="submitStatus"
    >
      <Field label="当前状态" wide>
        <TextInput :model-value="statusRow ? (statusRow.statusLabel || riskLabelOf(statusRow.status)) : ''" readonly />
      </Field>
      <Field label="目标状态" required :error="statusError" wide>
        <Select v-model="statusTarget" :options="statusChoices" placeholder="请选择目标状态" />
      </Field>
      <Field label="处理意见" wide hint="关闭或取消时建议写明原因，便于复盘追溯">
        <TextInput v-model="statusNote" multiline :rows="2" placeholder="如：已引入替代方案，风险关闭" :maxlength="500" />
      </Field>
    </FormDialog>

    <!-- 详情与留痕 -->
    <Drawer
      :open="detailOpen"
      origin-from="tr"
      :title="detail ? `风险详情 · ${detail.riskTitle}` : '风险详情'"
      :sub="detail ? `${detail.riskLevelLabel || detail.riskLevel} · ${detail.statusLabel || riskLabelOf(detail.status)}` : ''"
      :width="720"
      @close="detailOpen = false"
    >
      <div v-if="detail">
        <div class="kv-grid">
          <div class="kv">
            <div class="kv-label">责任人</div>
            <div class="kv-value">{{ detail.ownerName || '未指定' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">类别 / 等级</div>
            <div class="kv-value">{{ detail.riskTypeLabel || detail.riskType }} · {{ detail.riskLevelLabel || detail.riskLevel }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">计划应对完成</div>
            <div class="kv-value tnum">
              {{ detail.dueDate || '—' }}
              <Tag v-if="detail.delayed" tone="danger" dot>逾期</Tag>
            </div>
          </div>
          <div class="kv">
            <div class="kv-label">当前状态</div>
            <div class="kv-value">{{ detail.statusLabel || riskLabelOf(detail.status) }}</div>
          </div>
        </div>
        <div class="band-head" style="margin-top: 16px"><span class="section-label">风险描述</span></div>
        <p class="cell-sub">{{ detail.riskDesc || '未填写风险描述' }}</p>
        <div class="band-head" style="margin-top: 16px"><span class="section-label">应对措施</span></div>
        <p class="cell-sub">{{ detail.responsePlan || '未填写应对措施' }}</p>
        <div class="band-head" style="margin-top: 16px"><span class="section-label">状态留痕</span></div>
        <DataTable
          :columns="[
            { key: 'createTime', title: '时间', width: 160 },
            { key: 'change', title: '状态变化', minWidth: 160 },
            { key: 'operatorName', title: '操作人', width: 110 },
            { key: 'actionNote', title: '处理意见', minWidth: 200 },
          ]"
          :data="detailLogs"
          row-key="id"
          :loading="detailLoading"
          empty-text="暂无状态变更记录"
        >
          <template #cell-change="{ row }">
            <span class="soft">
              {{ row.fromStatus ? (riskLabelOf(row.fromStatus) + ' → ') : '' }}{{ riskLabelOf(row.toStatus) }}
            </span>
          </template>
          <template #cell-operatorName="{ row }"><span class="soft">{{ row.operatorName || '—' }}</span></template>
          <template #cell-actionNote="{ row }"><span class="cell-sub">{{ row.actionNote || '—' }}</span></template>
        </DataTable>
      </div>
      <p v-else-if="detailLoading" class="faint">正在读取风险详情…</p>
    </Drawer>
  </ListPage>
</template>

<style scoped>
.risk-title-btn {
  padding: 0;
  border: none;
  background: none;
  color: var(--ink);
  font: inherit;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}
.risk-title-btn:hover { color: var(--blue); }
.risk-title-btn:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; border-radius: 4px; }
</style>
