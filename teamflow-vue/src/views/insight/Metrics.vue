<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Drawer, Field, Select, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { insightApi, type InsightMetricRow } from '@/api/insight'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import {
  METRIC_CATEGORY_OPTIONS,
  METRIC_STATUS_OPTIONS,
  REFRESH_FREQUENCY_OPTIONS,
  dateTimeText,
  isPerfAligned,
  metricCategoryLabel,
  metricElementRows,
  metricStatusLabel,
  refreshFrequencyLabel,
} from './support'

/* ============================================================
   指标口径（M13 · 7.1）
   ------------------------------------------------------------
   登记每个指标的定义、计算公式、数据来源模块 / 接口、取数时间范围与缺失值处理；
   未登记口径的指标不得上线（后端双闸门：要素齐全 + 命中 M13 指标实现登记）。
   只有「已上线」指标参与看板出数；草稿 / 已下线在列表显式标注「不可出数」，
   编辑时若该编码未在 M13 登记实现，「已上线」选项会被禁用。
   ============================================================ */

const list = useListPage<InsightMetricRow, { keyword: string; category: string; status: string }>({
  query: { keyword: '', category: '', status: '' },
  fetch: params => insightApi.metrics.list(params),
})

const CATEGORY_FILTER_OPTIONS = [
  { label: '全部类别', value: '' },
  { label: '任务统计', value: 'task' },
  { label: '团队效能', value: 'team' },
  { label: '跨域指标', value: 'cross' },
]
const STATUS_FILTER_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '草稿', value: 'draft' },
  { label: '已上线', value: 'online' },
  { label: '已下线', value: 'offline' },
]

const columns: Column[] = [
  { key: 'metricName', title: '指标', minWidth: 230 },
  { key: 'metricCategory', title: '类别', width: 110 },
  { key: 'sourceModule', title: '数据来源模块', minWidth: 200 },
  { key: 'formula', title: '计算公式', minWidth: 300 },
  { key: 'timeGrain', title: '取数时间范围', minWidth: 250 },
  { key: 'missingPolicy', title: '缺失值处理', minWidth: 250 },
  { key: 'status', title: '状态', width: 130 },
  { key: 'actions', title: '操作', width: 128, className: 'col-actions' },
]

/* ── 口径详情 ── */
const drawer = ref(false)
const detail = ref<InsightMetricRow | null>(null)

async function openDetail(row: InsightMetricRow) {
  try {
    detail.value = await insightApi.metrics.detail(row.id)
    drawer.value = true
  } catch (error) {
    toastError(error, '口径详情加载失败')
  }
}

/* ── 新增 / 编辑 ── */
interface MetricForm {
  id?: number
  metricCode: string
  metricName: string
  metricCategory: string
  metricDefinition: string
  formula: string
  sourceModule: string
  sourceDetail: string
  timeGrain: string
  missingPolicy: string
  refreshFrequency: string
  cacheTtlSeconds: number | null
  ownerName: string
  status: string
  remark: string
}

const emptyForm = (): MetricForm => ({
  metricCode: '',
  metricName: '',
  metricCategory: 'task',
  metricDefinition: '',
  formula: '',
  sourceModule: '',
  sourceDetail: '',
  timeGrain: '',
  missingPolicy: '',
  refreshFrequency: 'realtime',
  cacheTtlSeconds: 0,
  ownerName: '',
  status: 'draft',
  remark: '',
})

const dialog = ref(false)
const saving = ref(false)
const loadingDetail = ref(false)
const form = reactive<MetricForm>(emptyForm())
const errors = ref<Partial<Record<keyof MetricForm, string>>>({})
/** 编辑时由详情接口返回：该编码是否已在 M13 指标实现中登记 */
const implemented = ref<boolean | null>(null)

const isEdit = computed(() => Boolean(form.id))
const onlineBlocked = computed(() => isEdit.value && implemented.value === false)
const statusOptions = computed(() => METRIC_STATUS_OPTIONS.map(option => (
  option.value === 'online' && onlineBlocked.value
    ? { ...option, label: '已上线（该编码未在 M13 登记实现，不可选）', disabled: true }
    : option
)))

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  implemented.value = null
  errors.value = {}
  dialog.value = true
}

async function openEdit(row: InsightMetricRow) {
  loadingDetail.value = true
  try {
    const full = await insightApi.metrics.detail(row.id)
    implemented.value = full.implemented ?? null
    Object.assign(form, {
      id: full.id,
      metricCode: full.metricCode,
      metricName: full.metricName,
      metricCategory: full.metricCategory,
      metricDefinition: full.metricDefinition,
      formula: full.formula,
      sourceModule: full.sourceModule,
      sourceDetail: full.sourceDetail,
      timeGrain: full.timeGrain,
      missingPolicy: full.missingPolicy,
      refreshFrequency: full.refreshFrequency,
      cacheTtlSeconds: full.cacheTtlSeconds,
      ownerName: full.ownerName,
      status: full.status,
      remark: full.remark ?? '',
    })
    errors.value = {}
    dialog.value = true
  } catch (error) {
    toastError(error, '指标详情加载失败')
  } finally {
    loadingDetail.value = false
  }
}

async function submit() {
  errors.value = validate(form, {
    metricCode: isEdit.value ? [] : [required('请填写指标编码')],
    metricName: [required('请填写指标名称')],
    metricDefinition: [required('请填写指标定义（业务含义）')],
    formula: [required('请填写计算公式')],
    sourceModule: [required('请填写数据来源模块')],
    sourceDetail: [required('请填写数据来源接口')],
    timeGrain: [required('请填写取数时间范围')],
    missingPolicy: [required('请填写缺失值处理')],
  })
  if (onlineBlocked.value && form.status === 'online') {
    errors.value.status = '该编码未在 M13 指标实现中登记，不能上线'
  }
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload = {
    metricCode: form.metricCode.trim(),
    metricName: form.metricName.trim(),
    metricCategory: form.metricCategory,
    metricDefinition: form.metricDefinition.trim(),
    formula: form.formula.trim(),
    sourceModule: form.sourceModule.trim(),
    sourceDetail: form.sourceDetail.trim(),
    timeGrain: form.timeGrain.trim(),
    missingPolicy: form.missingPolicy.trim(),
    refreshFrequency: form.refreshFrequency,
    cacheTtlSeconds: form.cacheTtlSeconds ?? 0,
    ownerName: form.ownerName.trim() || null,
    status: form.status,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await insightApi.metrics.update(form.id, payload)
      pushToast('指标口径已更新')
    } else {
      await insightApi.metrics.create(payload)
      pushToast('指标口径已登记')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '指标口径保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: InsightMetricRow) {
  const ok = await confirm({
    title: '删除指标口径',
    message: `确认删除指标「${row.metricName}」吗？已上线指标需先下线才能删除，避免看板口径凭空消失。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await insightApi.metrics.remove(row.id)
    pushToast('指标口径已删除')
    await list.load()
  } catch (error) {
    toastError(error, '指标口径删除失败')
  }
}
</script>

<template>
  <ListPage
    title="指标口径"
    desc="登记每个统计指标的定义、计算公式、数据来源、取数时间范围与缺失值处理；只有已上线（online）且已在 M13 有计算实现的指标才参与看板出数，未登记口径的指标不得上线。"
    table-title="指标字典"
    :list="list"
    :columns="columns"
    :min-width="1560"
  >
    <template #actions>
      <PermissionButton perm="insight:metric:add" variant="primary" icon="plus" @click="openCreate">新增指标口径</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="指标编码 / 名称" clearable @enter="list.search()" />
      </Field>
      <Field label="类别">
        <Select v-model="list.query.category" :options="CATEGORY_FILTER_OPTIONS" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="STATUS_FILTER_OPTIONS" />
      </Field>
    </template>

    <template #cell-metricName="{ row }">
      <div>
        <div class="cell-title">
          {{ row.metricName }}
          <Tag v-if="isPerfAligned(row.metricCode)" tone="info" style="margin-left: 6px">与 M05 同码</Tag>
        </div>
        <div class="cell-sub mono">{{ row.metricCode }}</div>
      </div>
    </template>
    <template #cell-metricCategory="{ row }">
      <Tag tone="neutral">{{ metricCategoryLabel(row) }}</Tag>
    </template>
    <template #cell-sourceModule="{ row }">
      <div class="soft ellipsis" style="max-width: 210px">{{ row.sourceModule }}</div>
      <div class="cell-sub ellipsis" style="max-width: 240px" :title="row.sourceDetail">{{ row.sourceDetail }}</div>
    </template>
    <template #cell-formula="{ row }">
      <span class="soft ellipsis" style="display: block; max-width: 320px" :title="row.formula">{{ row.formula }}</span>
    </template>
    <template #cell-timeGrain="{ row }">
      <span class="soft ellipsis" style="display: block; max-width: 260px" :title="row.timeGrain">{{ row.timeGrain }}</span>
    </template>
    <template #cell-missingPolicy="{ row }">
      <span class="soft ellipsis" style="display: block; max-width: 260px" :title="row.missingPolicy">{{ row.missingPolicy }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'online' ? 'ok' : row.status === 'offline' ? 'danger' : 'neutral'" dot>
        {{ metricStatusLabel(row) }}
      </Tag>
      <div v-if="row.status !== 'online'" class="cell-sub">不可出数</div>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="insight:metric:list" icon-only icon="eye" label="查看口径详情" @click="openDetail(row)" />
        <PermissionButton perm="insight:metric:edit" icon-only icon="edit" label="编辑指标口径" :loading="loadingDetail" @click="openEdit(row)" />
        <PermissionButton
          perm="insight:metric:remove"
          icon-only
          icon="trash"
          :label="row.status === 'online' ? '已上线指标需先下线再删除' : '删除指标口径'"
          :disabled="row.status === 'online'"
          danger
          @click="remove(row)"
        />
      </div>
    </template>

    <!-- 口径详情：四要素全文，供评审逐项核对 -->
    <Drawer
      :open="drawer"
      :title="detail?.metricName || '口径详情'"
      :sub="detail ? `${detail.metricCode} · ${metricCategoryLabel(detail)}` : ''"
      :width="720"
      @close="drawer = false"
    >
      <div v-if="detail" style="display: flex; flex-direction: column; gap: 16px">
        <div style="display: flex; gap: 8px; flex-wrap: wrap">
          <Tag :tone="detail.status === 'online' ? 'ok' : detail.status === 'offline' ? 'danger' : 'neutral'" dot>
            {{ metricStatusLabel(detail) }}
          </Tag>
          <Tag v-if="detail.status !== 'online'" tone="danger">不参与看板出数</Tag>
          <Tag :tone="detail.implemented ? 'info' : 'neutral'">
            {{ detail.implemented ? '已在 M13 登记计算实现' : '未在 M13 登记计算实现（不可上线）' }}
          </Tag>
          <Tag v-if="isPerfAligned(detail.metricCode)" tone="info">与 M05 同码同口径</Tag>
        </div>

        <div>
          <div class="faint" style="font-size: 11.5px">指标定义</div>
          <div class="soft" style="margin-top: 4px; font-size: 12.5px; line-height: 1.7">{{ detail.metricDefinition }}</div>
        </div>
        <div v-for="item in metricElementRows(detail)" :key="item.label">
          <div class="faint" style="font-size: 11.5px">{{ item.label }}</div>
          <div class="soft" style="margin-top: 4px; font-size: 12.5px; line-height: 1.7">{{ item.value }}</div>
        </div>

        <div class="kv-grid">
          <div class="kv"><div class="kv-label">刷新频率</div><div class="kv-value">{{ refreshFrequencyLabel(detail) }}</div></div>
          <div class="kv"><div class="kv-label">缓存策略</div><div class="kv-value">{{ detail.cacheStrategy || (detail.cacheTtlSeconds === 0 ? '不缓存（实时聚合）' : `缓存 ${detail.cacheTtlSeconds} 秒`) }}</div></div>
          <div class="kv"><div class="kv-label">口径责任人</div><div class="kv-value">{{ detail.ownerName || '—' }}</div></div>
          <div class="kv"><div class="kv-label">最近更新</div><div class="kv-value tnum">{{ dateTimeText(detail.updateTime) }}</div></div>
        </div>

        <p v-if="detail.remark" class="faint" style="font-size: 12px; line-height: 1.8">备注：{{ detail.remark }}</p>
        <p class="faint" style="font-size: 12px; line-height: 1.8">
          口径变更有操作留痕；已上线指标必须先下线再修改状态或删除。看板每次打开都实时聚合，本页不保存任何看板明细。
        </p>
      </div>
    </Drawer>

    <!-- 新增 / 编辑口径 -->
    <FormDialog
      :open="dialog"
      :title="isEdit ? '编辑指标口径' : '登记指标口径'"
      :sub="isEdit ? '指标编码是不可变标识；已上线指标建议先下线再调整口径' : '未登记完整口径的指标不能上线；上线还需该编码已在 M13 指标实现中登记'"
      :width="920"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="指标编码" required :error="errors.metricCode" hint="唯一标识，如 task_completion；创建后不可修改">
        <TextInput v-model="form.metricCode" placeholder="如 task_completion" :disabled="isEdit" :invalid="!!errors.metricCode" />
      </Field>
      <Field label="指标名称" required :error="errors.metricName">
        <TextInput v-model="form.metricName" placeholder="如 任务完成率" :invalid="!!errors.metricName" />
      </Field>
      <Field label="指标类别">
        <Select v-model="form.metricCategory" :options="METRIC_CATEGORY_OPTIONS" />
      </Field>
      <Field label="指标状态" :error="errors.status" hint="只有「已上线」参与看板出数">
        <Select v-model="form.status" :options="statusOptions" />
      </Field>
      <Field label="指标定义" span required :error="errors.metricDefinition" hint="给使用者看的业务含义，不写实现细节">
        <TextInput v-model="form.metricDefinition" multiline :rows="2" placeholder="如：统计周期内顶层任务的完成比例，衡量执行落地程度" :invalid="!!errors.metricDefinition" />
      </Field>
      <Field label="计算公式" span required :error="errors.formula" hint="口径唯一来源；与业务模块同名指标必须一致">
        <TextInput v-model="form.formula" multiline :rows="2" placeholder="如：已完成顶层任务数 / 顶层任务总数 × 100" :invalid="!!errors.formula" />
      </Field>
      <Field label="数据来源模块" required :error="errors.sourceModule" hint="如 M03 任务与工作流">
        <TextInput v-model="form.sourceModule" placeholder="如 M03 任务与工作流" :invalid="!!errors.sourceModule" />
      </Field>
      <Field label="数据来源接口" required :error="errors.sourceDetail" hint="禁止直连业务表，写只读端口/Service">
        <TextInput v-model="form.sourceDetail" placeholder="如 M03 TaskPerfQueryService#topLevelTaskFacts" :invalid="!!errors.sourceDetail" />
      </Field>
      <Field label="取数时间范围" span required :error="errors.timeGrain">
        <TextInput v-model="form.timeGrain" multiline :rows="2" placeholder="如：统计区间内 due_date 落在区间内的顶层任务；due_date 为空按 create_time 判定" :invalid="!!errors.timeGrain" />
      </Field>
      <Field label="缺失值处理" span required :error="errors.missingPolicy">
        <TextInput v-model="form.missingPolicy" multiline :rows="2" placeholder="如：无任何顶层任务时取 0，并在看板标记 missing=true 显示「暂无数据」" :invalid="!!errors.missingPolicy" />
      </Field>
      <Field label="刷新频率">
        <Select v-model="form.refreshFrequency" :options="REFRESH_FREQUENCY_OPTIONS" />
      </Field>
      <Field label="缓存失效时长（秒）" hint="0 表示不缓存；只允许缓存聚合结果，可随时重建" :error="errors.cacheTtlSeconds">
        <TextInput v-model="form.cacheTtlSeconds" type="number" placeholder="0" />
      </Field>
      <Field label="口径责任人">
        <TextInput v-model="form.ownerName" placeholder="留空则记为当前账号" />
      </Field>
      <Field label="备注">
        <TextInput v-model="form.remark" placeholder="选填" />
      </Field>
    </FormDialog>

    <div class="band-label reveal">
      <span class="section-mark-title">口径与出数说明</span>
    </div>
    <section class="band reveal">
      <p class="soft" style="font-size: 13px; line-height: 1.9; margin: 0">
        指标字典只登记口径，不保存看板明细：任务总览与团队负载每次打开都实时聚合，前端不缓存，也不提供修改统计数据的入口。
      </p>
      <p class="faint" style="font-size: 12.5px; line-height: 1.9; margin: 12px 0 0">
        未登记口径的指标不得上线——上线要求定义、公式、数据来源模块、来源接口、取数时间范围、缺失值处理六项齐全，且指标编码必须已在 M13 指标实现中登记（后端双闸门）。
        状态为「草稿」「已下线」的指标不参与看板出数；任务完成率、时限达标率、KR 达成率与 M05 绩效归集同码同口径，可直接核对数值。
      </p>
    </section>
  </ListPage>
</template>
