<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { DataTable, Field, SectionLabel, Select, Segmented, Tag, TextInput, type Column } from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import { okrApi, type OkrAlignRow, type OkrObjectiveRow, type OkrPeriodRow } from '@/api/okr'
import { pathOf } from '@/composables/auth'
import { useListPage } from '@/composables/list'
import { useViewMode } from '@/composables/view'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  LEVEL_OPTIONS,
  LEVEL_TEXT,
  OBJECTIVE_STATUS_OPTIONS,
  OBJECTIVE_STATUS_TEXT,
  OBJECTIVE_STATUS_TONE,
  PERIOD_STATUS_TEXT,
  numberText,
  percentText,
} from './support'

/* ============================================================
   目标制定：周期下按「公司级 → 部门级 → 个人级」三层拆目标。
   规则由后端把关：部门级必须有部门、没有承接人只能存草稿、
   同一上级下的子目标权重合计必须为 100%；对齐关系（子→父）在此维护。
   ============================================================ */
const router = useRouter()

const list = useListPage<OkrObjectiveRow, { keyword: string; status: string; level: string; periodId: number | null; ownerId: number | null }>({
  query: { keyword: '', status: '', level: '', periodId: null, ownerId: null },
  fetch: params => okrApi.objectives.list(params),
})

const columns: Column[] = [
  { key: 'objectiveTitle', title: '目标', minWidth: 240 },
  { key: 'level', title: '层级', width: 96 },
  { key: 'ownerName', title: '承接人', width: 110 },
  { key: 'deptName', title: '所属部门', width: 120 },
  { key: 'weight', title: '权重', width: 80, align: 'right' },
  { key: 'progress', title: '进度', width: 90, align: 'right' },
  { key: 'krCount', title: '关键结果', width: 96, align: 'right' },
  { key: 'status', title: '状态', width: 96 },
  { key: 'actions', title: '操作', width: 190, className: 'col-actions' },
]

/* ── 周期选项（筛选与表单共用） ── */
const periods = ref<OkrPeriodRow[]>([])
const periodOptions = computed(() => periods.value.map(p => ({ label: `${p.periodName}（${PERIOD_STATUS_TEXT[p.status] || p.status}）`, value: p.id })))

onMounted(async () => {
  try {
    const page = await okrApi.periods.list({ pageNum: 1, pageSize: 200 })
    periods.value = page.rows
  } catch (error) {
    toastError(error, '目标周期加载失败')
  }
})

function pickPeriod(value: string | number | null | undefined) {
  list.query.periodId = value === null || value === undefined || value === '' ? null : Number(value)
}

function pickOwner(value: string | number | null) {
  list.query.ownerId = value === null || value === '' ? null : Number(value)
}

/* ── 目标树视图：同一份数据按上下级展开，便于核对对齐关系 ── */
const view = useViewMode('okr-objective', 'table', ['table', 'tree'] as const, 'table')
const VIEWS = [{ label: '列表', value: 'table' as const }, { label: '目标树', value: 'tree' as const }]
const treeRows = ref<OkrObjectiveRow[]>([])
const treeLoading = ref(false)
const treePeriodId = ref<number | null>(null)
const treeColumns: Column[] = [
  { key: 'objectiveTitle', title: '目标', minWidth: 260 },
  { key: 'level', title: '层级', width: 90 },
  { key: 'ownerName', title: '承接人', width: 100 },
  { key: 'weight', title: '权重', width: 80, align: 'right' },
  { key: 'progress', title: '进度', width: 90, align: 'right' },
  { key: 'status', title: '状态', width: 90 },
  { key: 'actions', title: '操作', width: 120, className: 'col-actions' },
]

async function loadTree() {
  treeLoading.value = true
  try {
    treeRows.value = await okrApi.objectives.tree(treePeriodId.value)
  } catch (error) {
    toastError(error, '目标树加载失败')
  } finally {
    treeLoading.value = false
  }
}
watch(view, value => { if (value === 'tree') loadTree() })
function pickTreePeriod(value: string | number | null | undefined) {
  treePeriodId.value = value === null || value === undefined || value === '' ? null : Number(value)
  loadTree()
}

/* ── 表单 ── */
interface ObjectiveForm {
  id?: number
  periodId: number | null
  parentId: number | null
  objectiveTitle: string
  objectiveDesc: string
  level: string
  deptId: number | null
  ownerId: number | null
  weight: number | null
  status: string
  sort: number | null
  remark: string
}
const emptyForm = (): ObjectiveForm => ({
  periodId: null,
  parentId: null,
  objectiveTitle: '',
  objectiveDesc: '',
  level: 'company',
  deptId: null,
  ownerId: null,
  weight: 100,
  status: 'draft',
  sort: 10,
  remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const form = reactive<ObjectiveForm>(emptyForm())
const errors = ref<Partial<Record<keyof ObjectiveForm, string>>>({})

/** 可选上级目标：同一周期内、排除自身 */
const parentOptions = ref<Array<{ label: string; value: number }>>([])
async function loadParentOptions(periodId: number | null, excludeId?: number) {
  parentOptions.value = []
  if (!periodId) return
  try {
    const page = await okrApi.objectives.list({ pageNum: 1, pageSize: 200, periodId })
    parentOptions.value = page.rows
      .filter(row => row.id !== excludeId)
      .map(row => ({ label: `${row.objectiveTitle}（${LEVEL_TEXT[row.level] || row.level}）`, value: row.id }))
  } catch {
    parentOptions.value = []
  }
}

function pickFormPeriod(value: string | number | null | undefined) {
  form.periodId = value === null || value === undefined || value === '' ? null : Number(value)
  form.parentId = null
  loadParentOptions(form.periodId, form.id)
}
function pickFormParent(value: string | number | null | undefined) {
  form.parentId = value === null || value === undefined || value === '' ? null : Number(value)
}
function pickFormOwner(value: string | number | null) {
  form.ownerId = value === null || value === '' ? null : Number(value)
}
function pickFormStatus(value: string | number | null | undefined) {
  form.status = String(value ?? 'draft')
}
function pickFormLevel(value: string | number | null | undefined) {
  form.level = String(value ?? 'company')
  if (form.level !== 'dept') form.deptId = null
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined, periodId: list.query.periodId ?? null })
  errors.value = {}
  loadParentOptions(form.periodId)
  dialog.value = true
}

function openEdit(row: OkrObjectiveRow) {
  Object.assign(form, {
    id: row.id,
    periodId: row.periodId,
    parentId: row.parentId && row.parentId > 0 ? row.parentId : null,
    objectiveTitle: row.objectiveTitle,
    objectiveDesc: row.objectiveDesc ?? '',
    level: row.level || 'company',
    deptId: row.deptId ?? null,
    ownerId: row.ownerId ?? null,
    weight: row.weight ?? null,
    status: row.status || 'draft',
    sort: row.sort ?? 0,
    remark: row.remark ?? '',
  })
  errors.value = {}
  loadParentOptions(form.periodId, row.id)
  dialog.value = true
}

async function submit() {
  errors.value = validate(form, {
    periodId: [required('请选择所属周期')],
    objectiveTitle: [required('请输入目标标题')],
    deptId: [required('部门级目标必须选择部门')],
  })
  if (form.level !== 'dept') delete errors.value.deptId
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    periodId: form.periodId,
    parentId: form.parentId,
    objectiveTitle: form.objectiveTitle.trim(),
    objectiveDesc: form.objectiveDesc,
    level: form.level,
    deptId: form.level === 'dept' ? form.deptId : null,
    ownerId: form.ownerId,
    weight: form.weight,
    status: form.status,
    sort: form.sort,
    remark: form.remark,
  }
  try {
    if (form.id) {
      await okrApi.objectives.update(form.id, payload)
      pushToast('目标已更新')
    } else {
      await okrApi.objectives.create(payload)
      pushToast('目标已新增')
    }
    dialog.value = false
    await list.load()
    if (view.value === 'tree') loadTree()
  } catch (error) {
    toastError(error, '目标保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: OkrObjectiveRow) {
  const ok = await confirm({
    title: '删除目标',
    message: `确认删除「${row.objectiveTitle}」吗？其下关键结果与对齐关系会一并失效。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await okrApi.objectives.remove(row.id)
    pushToast('目标已删除')
    await list.load()
    if (view.value === 'tree') loadTree()
  } catch (error) {
    toastError(error, '目标删除失败')
  }
}

/* ── 对齐关系（子目标 → 父目标，后端禁止环形引用） ── */
const alignOpen = ref(false)
const alignTarget = ref<OkrObjectiveRow | null>(null)
const aligns = ref<OkrAlignRow[]>([])
const alignLoading = ref(false)
const alignSaving = ref(false)
const alignForm = reactive<{ parentObjectiveId: number | null; remark: string }>({ parentObjectiveId: null, remark: '' })

async function openAlign(row: OkrObjectiveRow) {
  alignTarget.value = row
  alignForm.parentObjectiveId = null
  alignForm.remark = ''
  alignOpen.value = true
  alignLoading.value = true
  try {
    aligns.value = await okrApi.objectives.aligns(row.id)
    await loadParentOptions(row.periodId, row.id)
  } catch (error) {
    toastError(error, '对齐关系加载失败')
    aligns.value = []
  } finally {
    alignLoading.value = false
  }
}

function pickAlignParent(value: string | number | null | undefined) {
  alignForm.parentObjectiveId = value === null || value === undefined || value === '' ? null : Number(value)
}

async function addAlign() {
  if (!alignTarget.value) return
  if (!alignForm.parentObjectiveId) {
    pushToast('请选择要承接的上级目标', 'danger')
    return
  }
  alignSaving.value = true
  try {
    await okrApi.objectives.createAlign(alignTarget.value.id, {
      parentObjectiveId: alignForm.parentObjectiveId,
      alignType: 'parent',
      remark: alignForm.remark,
    })
    pushToast('对齐关系已建立')
    alignForm.parentObjectiveId = null
    alignForm.remark = ''
    aligns.value = await okrApi.objectives.aligns(alignTarget.value.id)
  } catch (error) {
    toastError(error, '对齐关系保存失败')
  } finally {
    alignSaving.value = false
  }
}

async function removeAlign(align: OkrAlignRow) {
  const ok = await confirm({ title: '解除对齐', message: '确认解除这条对齐关系吗？解除后该目标不再计入上级目标的承接。', confirmText: '解除', danger: true })
  if (!ok || !alignTarget.value) return
  try {
    await okrApi.objectives.removeAlign(align.id)
    pushToast('对齐关系已解除')
    aligns.value = await okrApi.objectives.aligns(alignTarget.value.id)
  } catch (error) {
    toastError(error, '解除对齐失败')
  }
}

/* ── 跨页跳转：进入该目标的关键结果（路径取自菜单，避免写死） ── */
const krPath = computed(() => pathOf('okr/key-result/index'))
function openKrs(row: OkrObjectiveRow) {
  if (!krPath.value) return
  router.push({ path: krPath.value, query: { objectiveId: String(row.id) } })
}
</script>

<template>
  <ListPage
    title="目标制定"
    desc="按公司级、部门级、个人级三层制定目标；部门级目标必须选部门，没有承接人时只能保存为草稿，同一上级下的子目标权重合计需为 100%。"
    table-title="目标列表"
    :list="list"
    :columns="columns"
    :alt-view="view === 'tree'"
  >
    <template #actions>
      <Segmented v-model="view" label="视图" :options="VIEWS" />
      <PermissionButton perm="okr:objective:add" variant="primary" icon="plus" @click="openCreate">新增目标</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="目标标题" clearable @enter="list.search()" />
      </Field>
      <Field label="所属周期">
        <Select
          :model-value="list.query.periodId"
          :options="[{ label: '全部周期', value: '' }, ...periodOptions]"
          filterable
          @update:model-value="pickPeriod"
        />
      </Field>
      <Field label="层级">
        <Select
          v-model="list.query.level"
          :options="[{ label: '全部层级', value: '' }, ...LEVEL_OPTIONS]"
        />
      </Field>
      <Field label="承接人">
        <UserSelect :model-value="list.query.ownerId" placeholder="全部承接人" @update:model-value="pickOwner" />
      </Field>
      <Field label="状态">
        <Select
          v-model="list.query.status"
          :options="[{ label: '全部状态', value: '' }, ...OBJECTIVE_STATUS_OPTIONS]"
        />
      </Field>
    </template>

    <template #alt>
      <div class="board-toolbar">
        <Field label="所属周期">
          <Select
            :model-value="treePeriodId"
            :options="[{ label: '全部周期', value: '' }, ...periodOptions]"
            filterable
            @update:model-value="pickTreePeriod"
          />
        </Field>
        <span class="spacer" />
        <span class="eyebrow">目标树按上下级展开，用于核对目标承接关系</span>
      </div>
      <DataTable
        tree
        :columns="treeColumns"
        :data="treeRows"
        :loading="treeLoading"
        :expand-depth="2"
        empty-text="该周期下还没有目标"
      >
        <template #cell-objectiveTitle="{ row }">
          <span class="cell-title">{{ row.objectiveTitle }}</span>
        </template>
        <template #cell-level="{ row }">
          <Tag tone="neutral">{{ LEVEL_TEXT[row.level] || row.level }}</Tag>
        </template>
        <template #cell-ownerName="{ row }">
          <span>{{ row.ownerName || '未指定' }}</span>
        </template>
        <template #cell-weight="{ row }">
          <span class="tnum">{{ numberText(row.weight) }}</span>
        </template>
        <template #cell-progress="{ row }">
          <span class="tnum">{{ percentText(row.progress) }}</span>
        </template>
        <template #cell-status="{ row }">
          <Tag :tone="OBJECTIVE_STATUS_TONE[row.status] || 'neutral'" dot>{{ OBJECTIVE_STATUS_TEXT[row.status] || row.status }}</Tag>
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions">
            <PermissionButton perm="okr:objective:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
          </div>
        </template>
      </DataTable>
    </template>

    <template #cell-objectiveTitle="{ row }">
      <span class="cell-title">{{ row.objectiveTitle }}</span>
      <span v-if="row.objectiveDesc" class="cell-sub">{{ row.objectiveDesc }}</span>
    </template>
    <template #cell-level="{ row }">
      <Tag tone="neutral">{{ LEVEL_TEXT[row.level] || row.level }}</Tag>
    </template>
    <template #cell-ownerName="{ row }">
      <span>{{ row.ownerName || '未指定（仅可存草稿）' }}</span>
    </template>
    <template #cell-deptName="{ row }">
      <span class="muted">{{ row.deptName || '—' }}</span>
    </template>
    <template #cell-weight="{ row }">
      <span class="tnum">{{ numberText(row.weight) }}</span>
    </template>
    <template #cell-progress="{ row }">
      <span class="tnum">{{ percentText(row.progress) }}</span>
    </template>
    <template #cell-krCount="{ row }">
      <span class="tnum">{{ row.krCount ?? 0 }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="OBJECTIVE_STATUS_TONE[row.status] || 'neutral'" dot>{{ OBJECTIVE_STATUS_TEXT[row.status] || row.status }}</Tag>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="okr:objective:edit" icon-only icon="edit" label="编辑" @click="openEdit(row)" />
        <PermissionButton perm="okr:objective:edit" icon-only icon="layers" label="对齐关系" @click="openAlign(row)" />
        <PermissionButton v-if="krPath" perm="okr:kr:list" icon-only icon="chart" label="关键结果" @click="openKrs(row)" />
        <PermissionButton perm="okr:objective:remove" icon-only icon="trash" label="删除" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑目标' : '新增目标'"
      sub="权重是同一上级下的相对占比，兄弟目标合计需为 100%"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="所属周期" required :error="errors.periodId">
        <Select
          :model-value="form.periodId"
          :options="periodOptions"
          placeholder="选择周期"
          filterable
          :invalid="!!errors.periodId"
          @update:model-value="pickFormPeriod"
        />
      </Field>
      <Field label="层级">
        <Select :model-value="form.level" :options="LEVEL_OPTIONS" @update:model-value="pickFormLevel" />
      </Field>
      <Field label="目标标题" required :error="errors.objectiveTitle" span>
        <TextInput v-model="form.objectiveTitle" placeholder="如：把交付周期缩短 20%" :invalid="!!errors.objectiveTitle" :maxlength="120" />
      </Field>
      <Field label="上级目标" hint="用于表达目标承接关系，可留空">
        <Select
          :model-value="form.parentId"
          :options="parentOptions"
          placeholder="无上级目标"
          filterable
          clearable
          @update:model-value="pickFormParent"
        />
      </Field>
      <Field v-if="form.level === 'dept'" label="所属部门" required :error="errors.deptId">
        <DeptSelect v-model="form.deptId" :invalid="!!errors.deptId" />
      </Field>
      <Field label="承接人" hint="没有承接人时只能保存为草稿">
        <UserSelect :model-value="form.ownerId" placeholder="选择承接人" @update:model-value="pickFormOwner" />
      </Field>
      <Field label="状态">
        <Select :model-value="form.status" :options="OBJECTIVE_STATUS_OPTIONS" @update:model-value="pickFormStatus" />
      </Field>
      <Field label="权重" hint="同一上级下所有子目标之和需为 100">
        <TextInput v-model="form.weight" type="number" />
      </Field>
      <Field label="排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="目标说明" span>
        <TextInput v-model="form.objectiveDesc" multiline :rows="2" placeholder="补充背景与判断标准（可选）" :maxlength="300" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="内部备注（可选）" :maxlength="200" />
      </Field>
    </FormDialog>

    <FormDialog
      :open="alignOpen"
      title="对齐关系"
      :sub="alignTarget ? `为「${alignTarget.objectiveTitle}」指定承接的上级目标` : ''"
      :width="720"
      :saving="alignSaving"
      submit-text="建立对齐"
      @close="alignOpen = false"
      @submit="addAlign"
    >
      <Field label="承接上级目标" required span>
        <Select
          :model-value="alignForm.parentObjectiveId"
          :options="parentOptions"
          placeholder="选择上级目标"
          filterable
          @update:model-value="pickAlignParent"
        />
      </Field>
      <Field label="说明" span>
        <TextInput v-model="alignForm.remark" placeholder="为什么承接这个上级目标（可选）" :maxlength="200" />
      </Field>

      <div class="span-2" style="width: 100%">
        <SectionLabel title="已建立的对齐关系" />
        <DataTable
          :columns="[
            { key: 'parentTitle', title: '上级目标', minWidth: 220 },
            { key: 'alignType', title: '类型', width: 90 },
            { key: 'remark', title: '说明' },
            { key: 'actions', title: '操作', width: 90, className: 'col-actions' }
          ]"
          :data="aligns"
          :loading="alignLoading"
          empty-text="尚未建立对齐关系"
        >
          <template #cell-alignType="{ row }">
            <Tag tone="info">{{ row.alignType === 'parent' ? '承接' : row.alignType }}</Tag>
          </template>
          <template #cell-remark="{ row }">
            <span class="muted">{{ row.remark || '—' }}</span>
          </template>
          <template #cell-actions="{ row }">
            <div class="row-actions">
              <PermissionButton perm="okr:objective:edit" icon-only icon="trash" label="解除对齐" danger @click="removeAlign(row)" />
            </div>
          </template>
        </DataTable>
      </div>
    </FormDialog>
  </ListPage>
</template>
