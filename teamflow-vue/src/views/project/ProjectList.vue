<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  DataTable,
  Drawer,
  Field,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import {
  projectApi,
  type ObjectiveOption,
  type ProjectDetail,
  type ProjectMemberRow,
  type ProjectMilestoneRow,
  type ProjectRow,
  type ProjectStatusMachine,
  type ProjectWriteRequest,
} from '@/api/project'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   项目列表（M14 · 2.5.1 项目立项）
   ------------------------------------------------------------
   项目可关联目标（只存目标引用，标题与进度由目标模块实时提供），
   有状态机 draft → active → closed → archived；归档后项目只读。
   甘特视图 / 风险登记 / 项目复盘属后续里程碑，本页不涉及。
   ============================================================ */

const PRIORITY_OPTIONS = [
  { label: '低', value: 'low' },
  { label: '普通', value: 'normal' },
  { label: '高', value: 'high' },
  { label: '紧急', value: 'urgent' },
]
const PRIORITY_LABEL: Record<string, string> = { low: '低', normal: '普通', high: '高', urgent: '紧急' }
const PRIORITY_TONE: Record<string, string> = { urgent: 'danger', high: 'info', normal: 'neutral', low: 'neutral' }

const list = useListPage<ProjectRow, { keyword: string; status: string }>({
  query: { keyword: '', status: '' },
  fetch: params => projectApi.projects.list(params),
})

const statusMachine = ref<ProjectStatusMachine | null>(null)
const objectives = ref<ObjectiveOption[]>([])

/* 状态机加载失败时的兜底文案（仅用于展示；允许迁移仍以后端 nextStatuses 为准） */
const PROJECT_LABEL_FALLBACK: Record<string, string> = {
  draft: '立项草稿', active: '进行中', closed: '已结项', archived: '已归档',
}
const MILESTONE_LABEL_FALLBACK: Record<string, string> = {
  planned: '计划中', in_progress: '进行中', completed: '已完成', cancelled: '已取消',
}

const statusOptions = computed(() => [
  { label: '全部状态', value: '' },
  ...(statusMachine.value?.project.states ?? []).map(state => ({ label: state.label, value: state.code })),
])
const projectLabelOf = (code: string | null | undefined) =>
  statusMachine.value?.project.states.find(state => state.code === code)?.label
  ?? (code ? PROJECT_LABEL_FALLBACK[code] : undefined)
  ?? code
  ?? '—'
const milestoneLabelOf = (code: string | null | undefined) =>
  statusMachine.value?.milestone.states.find(state => state.code === code)?.label
  ?? (code ? MILESTONE_LABEL_FALLBACK[code] : undefined)
  ?? code
  ?? '—'

const objectiveOptions = computed(() => objectives.value.map(item => ({
  label: item.objectiveTitle,
  value: item.id,
  hint: item.progress == null ? '目标进度未知' : `目标进度 ${item.progress}%`,
})))

onMounted(async () => {
  try {
    statusMachine.value = await projectApi.projects.statusMachine()
  } catch {
    statusMachine.value = null
  }
  try {
    const page = await projectApi.objectives()
    objectives.value = page.rows
  } catch {
    // 没有目标查看权限时立项仍可填「不关联原因」，此处不阻塞页面
    objectives.value = []
  }
})

const columns: Column[] = [
  { key: 'projectName', title: '项目', minWidth: 240 },
  { key: 'objectiveId', title: '关联目标', minWidth: 200 },
  { key: 'ownerName', title: '负责人', width: 130 },
  { key: 'status', title: '状态', width: 116 },
  { key: 'schedule', title: '计划周期', width: 200 },
  { key: 'milestoneCount', title: '里程碑', width: 120, align: 'right' },
  { key: 'memberCount', title: '成员', width: 90, align: 'right' },
  { key: 'priority', title: '优先级', width: 96 },
  { key: 'actions', title: '操作', width: 190, className: 'col-actions' },
]

/* ── 立项 / 编辑 ── */
interface ProjectForm {
  id?: number
  projectCode: string
  projectName: string
  projectDesc: string
  objectiveId: number | null
  noObjectiveReason: string
  ownerId: number | null
  deptId: number | null
  startDate: string
  endDate: string
  priority: string
  remark: string
}
const emptyForm = (): ProjectForm => ({
  projectCode: '',
  projectName: '',
  projectDesc: '',
  objectiveId: null,
  noObjectiveReason: '',
  ownerId: null,
  deptId: null,
  startDate: '',
  endDate: '',
  priority: 'normal',
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<ProjectForm>(emptyForm())
const errors = ref<Partial<Record<keyof ProjectForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: ProjectRow) {
  Object.assign(form, {
    id: row.id,
    projectCode: row.projectCode,
    projectName: row.projectName,
    projectDesc: row.projectDesc ?? '',
    objectiveId: row.objectiveId ?? null,
    noObjectiveReason: row.noObjectiveReason ?? '',
    ownerId: row.ownerId ?? null,
    deptId: row.deptId ?? null,
    startDate: row.startDate ?? '',
    endDate: row.endDate ?? '',
    priority: row.priority ?? 'normal',
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitProject() {
  const nextErrors = validate(form, {
    projectCode: [required('请填写项目编号')],
    projectName: [required('请填写项目名称')],
  })
  // R1 项目不得悬空：目标与不关联原因至少填一项
  if (!form.objectiveId && !form.noObjectiveReason.trim()) {
    nextErrors.noObjectiveReason = '请选择关联目标，或填写不关联原因（项目不得悬空）'
  }
  if (form.startDate && form.endDate && form.endDate < form.startDate) {
    nextErrors.endDate = '计划结束日期不能早于计划开始日期'
  }
  errors.value = nextErrors
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: ProjectWriteRequest = {
    projectCode: form.projectCode.trim(),
    projectName: form.projectName.trim(),
    projectDesc: form.projectDesc.trim() || null,
    objectiveId: form.objectiveId,
    noObjectiveReason: form.objectiveId ? null : form.noObjectiveReason.trim() || null,
    ownerId: form.ownerId,
    deptId: form.deptId,
    startDate: form.startDate || null,
    endDate: form.endDate || null,
    priority: form.priority,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await projectApi.projects.update(form.id, payload)
      pushToast('项目已更新')
    } else {
      await projectApi.projects.create(payload)
      pushToast('项目已立项，状态为立项草稿')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '项目保存失败')
  } finally {
    saving.value = false
  }
}

async function removeProject(row: ProjectRow) {
  const ok = await confirm({
    title: '删除项目',
    message: `确认删除项目「${row.projectName}」吗？仅「立项草稿」状态可删除，删除后项目的里程碑与成员一并归档。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await projectApi.projects.remove(row.id)
    pushToast('项目已删除')
    await list.load()
  } catch (error) {
    toastError(error, '项目删除失败')
  }
}

/* ── 状态流转 ── */
const statusDialog = ref(false)
const statusSaving = ref(false)
const statusRow = ref<ProjectRow | null>(null)
const statusTarget = ref('')
const statusError = ref('')

function openStatus(row: ProjectRow) {
  statusRow.value = row
  statusTarget.value = row.nextStatuses?.[0] ?? ''
  statusError.value = ''
  statusDialog.value = true
}

const statusChoices = computed(() => (statusRow.value?.nextStatuses ?? []).map(code => ({
  label: projectLabelOf(code),
  value: code,
})))

async function submitStatus() {
  if (!statusRow.value) return
  if (!statusTarget.value) {
    statusError.value = '请选择要流转到的状态'
    return
  }
  statusSaving.value = true
  try {
    await projectApi.projects.changeStatus(statusRow.value.id, statusTarget.value)
    pushToast(`项目状态已流转为「${projectLabelOf(statusTarget.value)}」`)
    statusDialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '项目状态流转失败')
  } finally {
    statusSaving.value = false
  }
}

const archivedWarning = computed(() => statusTarget.value === 'archived')

/* ── 详情抽屉（目标标题/进度直接展示接口实时值，不在前端缓存） ── */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectDetail | null>(null)
const detailMilestoneColumns: Column[] = [
  { key: 'milestoneName', title: '里程碑', minWidth: 200 },
  { key: 'status', title: '状态', width: 110 },
  { key: 'planEndDate', title: '计划完成', width: 120 },
  { key: 'actualEndDate', title: '实际完成', width: 120 },
  { key: 'completion', title: '完成度', width: 170 },
]
const detailMemberColumns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 150 },
  { key: 'memberRole', title: '项目内角色', width: 110 },
  { key: 'joinTime', title: '加入时间', width: 140 },
]

async function openDetail(row: ProjectRow) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await projectApi.projects.detail(row.id)
  } catch (error) {
    toastError(error, '项目详情加载失败')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

/* ── 成员抽屉 ── */
const memberOpen = ref(false)
const memberLoading = ref(false)
const memberProject = ref<ProjectRow | null>(null)
const members = ref<ProjectMemberRow[]>([])
const memberColumns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 170 },
  { key: 'memberRole', title: '项目内角色', width: 110 },
  { key: 'joinTime', title: '加入时间', width: 140 },
  { key: 'actions', title: '操作', width: 90, className: 'col-actions' },
]

async function openMembers(row: ProjectRow) {
  memberProject.value = row
  memberOpen.value = true
  await loadMembers()
}

async function loadMembers() {
  if (!memberProject.value) return
  memberLoading.value = true
  try {
    members.value = await projectApi.members.list(memberProject.value.id)
  } catch (error) {
    toastError(error, '项目成员加载失败')
  } finally {
    memberLoading.value = false
  }
}

const memberDialog = ref(false)
const memberSaving = ref(false)
const memberForm = reactive<{ userId: number | null; memberRole: 'member' | 'viewer'; remark: string }>({
  userId: null,
  memberRole: 'member',
  remark: '',
})
const memberError = ref('')

function openAddMember() {
  memberForm.userId = null
  memberForm.memberRole = 'member'
  memberForm.remark = ''
  memberError.value = ''
  memberDialog.value = true
}

async function submitMember() {
  if (!memberProject.value) return
  if (!memberForm.userId) {
    memberError.value = '请选择要加入项目的成员'
    return
  }
  memberSaving.value = true
  try {
    await projectApi.members.add(memberProject.value.id, {
      userId: memberForm.userId,
      memberRole: memberForm.memberRole,
      remark: memberForm.remark.trim() || undefined,
    })
    pushToast('项目成员已添加')
    memberDialog.value = false
    await loadMembers()
    await list.load()
  } catch (error) {
    toastError(error, '添加项目成员失败')
  } finally {
    memberSaving.value = false
  }
}

function isOwnerRow(row: ProjectMemberRow) {
  return row.memberRole === 'owner' || row.userId === memberProject.value?.ownerId
}

async function removeMember(row: ProjectMemberRow) {
  if (!memberProject.value) return
  const ok = await confirm({
    title: '移出项目成员',
    message: `确认将「${row.nickname || row.username || '该成员'}」移出项目吗？移出只记录移出时间，历史归属仍保留；负责人不能移出。`,
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  try {
    await projectApi.members.remove(memberProject.value.id, row.id)
    pushToast('成员已移出项目')
    await loadMembers()
    await list.load()
  } catch (error) {
    toastError(error, '移出项目成员失败')
  }
}

function completionText(row: ProjectMilestoneRow) {
  if (row.completion != null) return `${row.completion}%`
  return row.completionNote || '—'
}
</script>

<template>
  <ListPage
    title="项目列表"
    desc="项目立项与执行跟踪。项目可关联目标（只存目标引用，标题与进度由目标模块实时提供）；状态按「立项草稿 → 进行中 → 已结项 → 已归档」流转，结项与归档后项目只读。"
    table-title="项目列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="project:project:add" variant="primary" icon="plus" @click="openCreate">项目立项</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="项目名称 / 编号" clearable @enter="list.search()" />
      </Field>
      <Field label="项目状态">
        <Select v-model="list.query.status" :options="statusOptions" />
      </Field>
    </template>

    <template #cell-projectName="{ row }">
      <div>
        <div class="cell-title">{{ row.projectName }}</div>
        <div class="cell-sub">
          <span class="code-chip">{{ row.projectCode }}</span>
          <span class="muted"> · {{ row.deptName || '未设置部门' }}</span>
        </div>
      </div>
    </template>
    <template #cell-objectiveId="{ row }">
      <span v-if="row.objectiveId" class="cell-sub">关联目标 #{{ row.objectiveId }}（标题由目标模块实时提供）</span>
      <span v-else class="muted">{{ row.noObjectiveReason || '未关联目标' }}</span>
    </template>
    <template #cell-ownerName="{ row }"><span class="muted">{{ row.ownerName || '未指定' }}</span></template>
    <template #cell-status="{ row }">
      <div>
        <Tag :tone="row.status === 'active' ? 'ok' : row.delayed ? 'danger' : 'neutral'" dot>{{ row.statusLabel || projectLabelOf(row.status) }}</Tag>
        <div v-if="row.delayed" class="cell-sub">已超计划结束日期</div>
      </div>
    </template>
    <template #cell-schedule="{ row }">
      <span class="tnum soft">{{ row.startDate || '—' }} 至 {{ row.endDate || '—' }}</span>
    </template>
    <template #cell-milestoneCount="{ row }">
      <span class="tnum">{{ row.completedMilestoneCount ?? 0 }} / {{ row.milestoneCount ?? 0 }}</span>
    </template>
    <template #cell-memberCount="{ row }"><span class="tnum">{{ row.memberCount ?? 0 }}</span></template>
    <template #cell-priority="{ row }">
      <Tag :tone="PRIORITY_TONE[row.priority ?? 'normal'] ?? 'neutral'">{{ PRIORITY_LABEL[row.priority ?? 'normal'] }}</Tag>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="project:project:list" icon-only icon="eye" label="项目详情" @click="openDetail(row)" />
        <PermissionButton perm="project:member:list" icon-only icon="users" label="项目成员" @click="openMembers(row)" />
        <PermissionButton
          v-if="row.editable"
          perm="project:project:edit"
          icon-only
          icon="edit"
          label="编辑项目"
          @click="openEdit(row)"
        />
        <PermissionButton
          v-if="row.nextStatuses && row.nextStatuses.length"
          perm="project:project:status"
          icon-only
          icon="arrowRight"
          label="项目状态流转"
          @click="openStatus(row)"
        />
        <PermissionButton
          v-if="row.status === 'draft'"
          perm="project:project:remove"
          icon-only
          icon="trash"
          label="删除项目"
          danger
          @click="removeProject(row)"
        />
      </div>
    </template>

    <!-- 项目详情：目标信息为接口实时值 -->
    <Drawer
      :open="detailOpen"
      origin-from="tr"
      :title="detail ? `项目详情 · ${detail.projectName}` : '项目详情'"
      :sub="detail ? `编号 ${detail.projectCode} · 状态 ${detail.statusLabel || projectLabelOf(detail.status)}` : ''"
      :width="900"
      @close="detailOpen = false"
    >
      <div v-if="detail">
        <div class="kv-grid">
          <div class="kv">
            <div class="kv-label">关联目标（实时）</div>
            <div class="kv-value">
              <template v-if="detail.objective && !detail.objective.missing">
                {{ detail.objective.objectiveTitle }}
                <span class="soft"> · 进度 {{ detail.objective.progress ?? '—' }}%</span>
              </template>
              <template v-else-if="detail.objective">
                <Tag tone="danger" dot>目标引用已失效</Tag>
                <div class="cell-sub">{{ detail.objective.note }}</div>
              </template>
              <template v-else>
                <span class="muted">未关联目标：{{ detail.noObjectiveReason || '未填写原因' }}</span>
              </template>
            </div>
          </div>
          <div class="kv">
            <div class="kv-label">项目完成度</div>
            <div class="kv-value">
              <template v-if="detail.progress.projectProgress != null">
                <span class="tnum">{{ detail.progress.projectProgress }}%</span>
                <span class="cell-sub">由已绑定 KR 的里程碑完成度平均汇总</span>
              </template>
              <span v-else class="muted">{{ detail.progress.note || '暂无可汇总的完成度' }}</span>
            </div>
          </div>
          <div class="kv">
            <div class="kv-label">负责人 / 部门</div>
            <div class="kv-value">{{ detail.ownerName || '未指定' }}<span class="soft"> · {{ detail.deptName || '未设置部门' }}</span></div>
          </div>
          <div class="kv">
            <div class="kv-label">计划 / 优先级</div>
            <div class="kv-value tnum">{{ detail.startDate || '—' }} 至 {{ detail.endDate || '—' }}<span class="soft"> · {{ PRIORITY_LABEL[detail.priority ?? 'normal'] }}</span></div>
          </div>
          <div class="kv">
            <div class="kv-label">里程碑</div>
            <div class="kv-value">
              完成 {{ detail.progress.completedMilestoneCount }} / 共 {{ detail.progress.milestoneCount }}
              <span v-if="detail.progress.delayedMilestoneCount" class="soft"> · 延期 {{ detail.progress.delayedMilestoneCount }}</span>
            </div>
          </div>
          <div class="kv">
            <div class="kv-label">当前状态</div>
            <div class="kv-value">
              {{ detail.statusLabel || projectLabelOf(detail.status) }}
              <Tag v-if="detail.editable === false" tone="neutral">只读</Tag>
            </div>
          </div>
        </div>

        <p class="cell-sub" style="margin: 12px 0">
          目标标题与进度为目标模块实时读取结果，本项目不保存副本；里程碑完成度按关联 KR 的顶层任务统计，由任务模块实时汇总。
        </p>

        <div class="band-head"><span class="section-label">里程碑进度</span></div>
        <DataTable :columns="detailMilestoneColumns" :data="detail.milestones" row-key="id">
          <template #cell-milestoneName="{ row }"><span class="cell-title">{{ row.milestoneName }}</span></template>
          <template #cell-status="{ row }"><Tag tone="neutral">{{ row.statusLabel || milestoneLabelOf(row.status) }}</Tag></template>
          <template #cell-planEndDate="{ row }"><span class="tnum soft">{{ row.planEndDate || '—' }}</span></template>
          <template #cell-actualEndDate="{ row }"><span class="tnum soft">{{ row.actualEndDate || '—' }}</span></template>
          <template #cell-completion="{ row }">
            <div>
              <span v-if="row.completion != null" class="tnum">{{ row.completion }}%</span>
              <span v-else class="cell-sub">{{ completionText(row) }}</span>
              <div v-if="row.completion != null" class="cell-sub">
                已完成 {{ row.taskDone ?? 0 }} / 顶层任务 {{ row.taskTotal ?? 0 }}
              </div>
            </div>
          </template>
        </DataTable>

        <div class="band-head" style="margin-top: 18px"><span class="section-label">在册成员</span></div>
        <DataTable :columns="detailMemberColumns" :data="detail.members" row-key="id">
          <template #cell-nickname="{ row }"><span class="cell-title">{{ row.nickname || row.username || `用户 #${row.userId}` }}</span></template>
          <template #cell-memberRole="{ row }"><Tag :tone="row.memberRole === 'owner' ? 'info' : 'neutral'">{{ row.memberRoleLabel || row.memberRole }}</Tag></template>
          <template #cell-joinTime="{ row }"><span class="tnum soft">{{ row.joinTime || '—' }}</span></template>
        </DataTable>
      </div>
      <p v-else-if="detailLoading" class="faint">正在读取项目详情…</p>
    </Drawer>

    <!-- 项目成员 -->
    <Drawer
      :open="memberOpen"
      origin-from="tr"
      :title="memberProject ? `项目成员 · ${memberProject.projectName}` : '项目成员'"
      sub="项目内角色只用于项目内部分工，不是系统角色，不会改变任何系统权限。"
      :width="760"
      @close="memberOpen = false"
    >
      <div>
        <div class="band-head">
          <span class="section-label">在册成员</span>
          <PermissionButton
            v-if="memberProject?.editable"
            perm="project:member:add"
            variant="primary"
            icon="plus"
            @click="openAddMember"
          >添加参与人</PermissionButton>
        </div>
        <p class="cell-sub" style="margin: 0 0 14px">
          负责人由项目主数据维护、不可移出；参与人移出后只记录移出时间，历史归属仍保留（列表仅展示在册成员）。
        </p>
        <DataTable :columns="memberColumns" :data="members" row-key="id" :loading="memberLoading">
          <template #cell-nickname="{ row }">
            <div>
              <div class="cell-title">{{ row.nickname || row.username || `用户 #${row.userId}` }}</div>
              <div class="cell-sub">{{ row.username }}{{ row.deptName ? ` · ${row.deptName}` : '' }}</div>
            </div>
          </template>
          <template #cell-memberRole="{ row }">
            <Tag :tone="row.memberRole === 'owner' ? 'info' : 'neutral'">{{ row.memberRoleLabel || row.memberRole }}</Tag>
          </template>
          <template #cell-joinTime="{ row }"><span class="tnum soft">{{ row.joinTime || '—' }}</span></template>
          <template #cell-actions="{ row }">
            <PermissionButton
              v-if="memberProject?.editable && !isOwnerRow(row)"
              perm="project:member:remove"
              icon-only
              icon="ban"
              label="移出项目成员"
              danger
              @click="removeMember(row)"
            />
            <span v-else-if="isOwnerRow(row)" class="cell-sub">负责人不可移出</span>
          </template>
        </DataTable>
        <p v-if="memberProject && !memberProject.editable" class="faint" style="margin-top: 12px">
          项目已结项或已归档，成员列表只读。
        </p>
      </div>
    </Drawer>

    <!-- 项目表单 -->
    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑项目' : '项目立项'"
      sub="项目必须关联目标或填写不关联原因；关联目标只保存引用，目标标题与进度由目标模块实时提供。"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submitProject"
    >
      <Field label="项目编号" required :error="errors.projectCode">
        <TextInput v-model="form.projectCode" placeholder="如：PRJ-2026-001" :invalid="!!errors.projectCode" :maxlength="64" />
      </Field>
      <Field label="项目名称" required :error="errors.projectName">
        <TextInput v-model="form.projectName" placeholder="如：客户门户改版" :invalid="!!errors.projectName" :maxlength="100" />
      </Field>
      <Field label="关联目标" hint="可空；不关联时必须在右侧填写原因">
        <Select v-model="form.objectiveId" :options="objectiveOptions" placeholder="选择目标（可空）" filterable clearable />
      </Field>
      <Field label="不关联原因" :error="errors.noObjectiveReason" hint="与关联目标至少填一项（项目不得悬空）">
        <TextInput v-model="form.noObjectiveReason" placeholder="如：探索性项目，暂不挂目标" :invalid="!!errors.noObjectiveReason" :maxlength="200" />
      </Field>
      <Field label="负责人" hint="留空则默认为当前登录用户">
        <UserSelect v-model="form.ownerId" placeholder="请选择负责人" />
      </Field>
      <Field label="归属部门">
        <DeptSelect v-model="form.deptId" clearable />
      </Field>
      <Field label="计划开始" hint="yyyy-MM-dd">
        <TextInput v-model="form.startDate" placeholder="如：2026-11-01" />
      </Field>
      <Field label="计划结束" :error="errors.endDate" hint="yyyy-MM-dd">
        <TextInput v-model="form.endDate" placeholder="如：2027-01-31" :invalid="!!errors.endDate" />
      </Field>
      <Field label="优先级">
        <Select v-model="form.priority" :options="PRIORITY_OPTIONS" />
      </Field>
      <Field label="项目说明" span>
        <TextInput v-model="form.projectDesc" multiline :rows="2" placeholder="项目目标、范围与关键交付" :maxlength="1000" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>

    <!-- 状态流转 -->
    <FormDialog
      :open="statusDialog"
      title="项目状态流转"
      sub="状态按「立项草稿 → 进行中 → 已结项 → 已归档」推进；归档为终态，归档后项目全页只读。"
      :saving="statusSaving"
      submit-text="确认流转"
      @close="statusDialog = false"
      @submit="submitStatus"
    >
      <Field label="当前状态" wide>
        <TextInput :model-value="statusRow ? (statusRow.statusLabel || projectLabelOf(statusRow.status)) : ''" readonly />
      </Field>
      <Field label="目标状态" required :error="statusError" wide>
        <Select v-model="statusTarget" :options="statusChoices" placeholder="请选择目标状态" />
      </Field>
      <p v-if="archivedWarning" class="cell-sub" style="margin: 0">
        归档后项目将不可再编辑、不可回退；确认项目已结项并完成归档前检查。
      </p>
    </FormDialog>

    <!-- 添加参与人 -->
    <FormDialog
      :open="memberDialog"
      title="添加项目成员"
      sub="负责人由项目主数据维护；此处只添加参与人或观察者，项目内角色不影响系统权限。"
      :saving="memberSaving"
      @close="memberDialog = false"
      @submit="submitMember"
    >
      <Field label="选择用户" required :error="memberError" wide>
        <UserSelect v-model="memberForm.userId" placeholder="请选择要加入的用户" />
      </Field>
      <Field label="项目内角色" group hint="参与人 / 观察者，仅用于项目内分工">
        <Select
          v-model="memberForm.memberRole"
          :options="[{ label: '参与人', value: 'member' }, { label: '观察者', value: 'viewer' }]"
        />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="memberForm.remark" placeholder="如：负责前端交付" :maxlength="200" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
