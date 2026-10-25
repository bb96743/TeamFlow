<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Button,
  DataTable,
  Drawer,
  Field,
  IconButton,
  Panel,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { ITEM_CATEGORY_OPTIONS } from './labels'
import {
  perfApi,
  type ItemCategory,
  type PerfMetricDefinition,
  type PerfTemplate,
  type PerfTemplateItemWrite,
} from '@/api/perf'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'

/* 指标模板：定义考核维度与权重（R6：启用/开周期前权重合计必须 100%），
   可归集指标只能引用「指标字典」中已登记四要素的指标（R2：未登记不得上线）。 */

const list = useListPage<PerfTemplate, { keyword: string; status: string }>({
  query: { keyword: '', status: '' },
  fetch: params => perfApi.templates.list(params),
})

const columns: Column[] = [
  { key: 'templateName', title: '模板', minWidth: 240 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'itemCount', title: '指标项', width: 92 },
  { key: 'totalWeight', title: '权重合计', width: 120 },
  { key: 'description', title: '说明', minWidth: 200 },
  { key: 'updateTime', title: '更新时间', width: 150 },
  { key: 'actions', title: '操作', width: 130, className: 'col-actions' },
]

const TEMPLATE_STATUS_OPTIONS = [
  { label: '草稿', value: 'draft' },
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' },
]

const TEMPLATE_STATUS_LABEL: Record<string, string> = { draft: '草稿', enabled: '启用', disabled: '停用' }
const TEMPLATE_STATUS_TONE: Record<string, string> = { draft: 'neutral', enabled: 'ok', disabled: 'danger' }

/* ── 指标字典（四要素） ── */
const metrics = ref<PerfMetricDefinition[]>([])
const metricsLoading = ref(false)
const dictOpen = ref(false)

async function loadMetrics() {
  if (metrics.value.length) return
  metricsLoading.value = true
  try {
    metrics.value = await perfApi.metrics.list()
  } catch (error) {
    toastError(error, '指标字典加载失败')
  } finally {
    metricsLoading.value = false
  }
}

function openDictionary() {
  dictOpen.value = true
  loadMetrics()
}

onMounted(loadMetrics)

const metricOptions = computed(() => metrics.value.map(metric => ({
  label: `${metric.metricName}（${metric.metricCode}）`,
  value: metric.metricCode,
  hint: metric.sourceModule,
})))

/* ── 模板表单（含指标项编辑） ── */
interface ItemDraft {
  /** 本地行标识：指标项编码在编辑过程中可能为空或重复，表格行 key 用它保证稳定 */
  key: number
  itemCode: string
  itemName: string
  itemCategory: ItemCategory
  metricCode: string | null
  weight: number | null
  maxScore: number | null
  scoreStandard: string
}

interface TemplateForm {
  id?: number
  templateCode: string
  templateName: string
  description: string
  status: string
  remark: string
}

const emptyForm = (): TemplateForm => ({ templateCode: '', templateName: '', description: '', status: 'draft', remark: '' })
let itemSeed = 0
const emptyItem = (): ItemDraft => ({
  key: ++itemSeed, itemCode: '', itemName: '', itemCategory: 'collectable', metricCode: null, weight: null, maxScore: 100, scoreStandard: '',
})

const dialog = ref(false)
const saving = ref(false)
const loadingDetail = ref(false)
const form = reactive<TemplateForm>(emptyForm())
const items = ref<ItemDraft[]>([])
const errors = ref<Partial<Record<keyof TemplateForm, string>>>({})
const itemsError = ref('')
const isEdit = computed(() => Boolean(form.id))

const itemColumns: Column[] = [
  { key: 'itemCode', title: '指标项编码', width: 160 },
  { key: 'itemName', title: '指标项名称', minWidth: 160 },
  { key: 'itemCategory', title: '类别', width: 190 },
  { key: 'metricCode', title: '引用指标（可归集）', minWidth: 220 },
  { key: 'weight', title: '权重 %', width: 110 },
  { key: 'maxScore', title: '分值上限', width: 110 },
  { key: 'actions', title: '操作', width: 76, className: 'col-actions' },
]

const weightSum = computed(() => items.value.reduce((sum, item) => sum + Number(item.weight ?? 0), 0))

const weightHint = computed(() => {
  const sum = Number(weightSum.value.toFixed(2))
  if (sum > 100) return `当前权重合计 ${sum}%，超过 100%，如保存会被拒绝。`
  if (form.status === 'enabled' && sum !== 100) return `启用模板要求权重合计恰好 100%，当前 ${sum}%。`
  if (form.status === 'draft' && sum < 100) return `草稿允许权重合计 ≤100%（当前 ${sum}%）；启用前必须调整为 100%。`
  return ''
})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  items.value = [emptyItem()]
  errors.value = {}
  itemsError.value = ''
  dialog.value = true
  loadMetrics()
}

async function openEdit(row: PerfTemplate) {
  loadingDetail.value = true
  try {
    const detail = await perfApi.templates.detail(row.id)
    Object.assign(form, {
      id: detail.id,
      templateCode: detail.templateCode,
      templateName: detail.templateName,
      description: detail.description ?? '',
      status: detail.status,
      remark: detail.remark ?? '',
    })
    items.value = (detail.items ?? []).map(item => ({
      key: ++itemSeed,
      itemCode: item.itemCode,
      itemName: item.itemName,
      itemCategory: item.itemCategory,
      metricCode: item.metricCode,
      weight: Number(item.weight),
      maxScore: Number(item.maxScore),
      scoreStandard: item.scoreStandard ?? '',
    }))
    errors.value = {}
    itemsError.value = ''
    dialog.value = true
    loadMetrics()
  } catch (error) {
    toastError(error, '模板详情加载失败')
  } finally {
    loadingDetail.value = false
  }
}

function addItem() {
  items.value.push(emptyItem())
}

function removeItem(index: number) {
  items.value.splice(index, 1)
}

function onCategoryChange(item: ItemDraft) {
  if (item.itemCategory === 'subjective') {
    item.metricCode = null
  }
}

async function submit() {
  errors.value = {}
  itemsError.value = ''
  if (!form.templateCode.trim() && !isEdit.value) errors.value.templateCode = '请填写模板编码'
  if (!form.templateName.trim()) errors.value.templateName = '请填写模板名称'
  if (!items.value.length) itemsError.value = '至少需要一个指标项'
  for (const [index, item] of items.value.entries()) {
    if (!item.itemCode.trim() || !item.itemName.trim()) {
      itemsError.value = `第 ${index + 1} 个指标项的编码与名称不能为空`
      break
    }
    if (!item.weight || Number(item.weight) <= 0) {
      itemsError.value = `第 ${index + 1} 个指标项的权重必须大于 0`
      break
    }
    if (item.itemCategory === 'collectable' && !item.metricCode) {
      itemsError.value = `第 ${index + 1} 个指标项为可归集类，必须引用指标字典中已登记的指标（R2）`
      break
    }
  }
  if (Object.keys(errors.value).length || itemsError.value) return

  const payloadItems: PerfTemplateItemWrite[] = items.value.map((item, index) => ({
    itemCode: item.itemCode.trim(),
    itemName: item.itemName.trim(),
    itemCategory: item.itemCategory,
    metricCode: item.itemCategory === 'collectable' ? item.metricCode : null,
    weight: Number(item.weight),
    maxScore: item.maxScore === null ? 100 : Number(item.maxScore),
    scoreStandard: item.scoreStandard.trim() || null,
    requireComment: item.itemCategory === 'subjective',
    sort: index + 1,
  }))

  saving.value = true
  try {
    if (form.id) {
      await perfApi.templates.update(form.id, {
        templateName: form.templateName.trim(),
        description: form.description.trim() || null,
        status: form.status,
        remark: form.remark.trim() || null,
        items: payloadItems,
      })
      pushToast('指标模板已更新')
    } else {
      await perfApi.templates.create({
        templateCode: form.templateCode.trim(),
        templateName: form.templateName.trim(),
        description: form.description.trim() || null,
        status: form.status,
        remark: form.remark.trim() || null,
        items: payloadItems,
      })
      pushToast('指标模板已创建')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '指标模板保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: PerfTemplate) {
  const ok = await confirm({
    title: '删除指标模板',
    message: `确认删除模板「${row.templateName}」吗？已被绩效周期引用的模板不能删除。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await perfApi.templates.remove(row.id)
    pushToast('指标模板已删除')
    await list.load()
  } catch (error) {
    toastError(error, '指标模板删除失败')
  }
}

function statusLabel(status?: string | null) {
  if (!status) return '—'
  return TEMPLATE_STATUS_LABEL[status] ?? status
}

function statusTone(status?: string | null) {
  if (!status) return 'neutral'
  return TEMPLATE_STATUS_TONE[status] ?? 'neutral'
}
</script>

<template>
  <ListPage
    title="指标模板"
    desc="定义考核维度、权重与评分标准；可归集指标只能引用指标字典中已登记口径的指标，启用前权重合计必须为 100%。"
    table-title="模板列表"
    :list="list"
    :columns="columns"
    :min-width="1120"
  >
    <template #actions>
      <PermissionButton perm="perf:metric:list" icon="dict" @click="openDictionary">指标字典</PermissionButton>
      <PermissionButton perm="perf:template:add" variant="primary" icon="plus" @click="openCreate">新增模板</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="模板编码 / 名称" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="[{ label: '全部状态', value: '' }, ...TEMPLATE_STATUS_OPTIONS]" />
      </Field>
    </template>

    <template #cell-templateName="{ row }">
      <div>
        <div class="cell-title">{{ row.templateName }}</div>
        <div class="cell-sub tnum">{{ row.templateCode }}</div>
      </div>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="statusTone(row.status)" dot>{{ statusLabel(row.status) }}</Tag>
    </template>
    <template #cell-itemCount="{ row }"><span class="tnum">{{ row.itemCount }} 项</span></template>
    <template #cell-totalWeight="{ row }">
      <Tag :tone="Number(row.totalWeight) === 100 ? 'ok' : 'danger'">{{ row.totalWeight }}%</Tag>
    </template>
    <template #cell-description="{ row }"><span class="muted">{{ row.description || '—' }}</span></template>
    <template #cell-updateTime="{ row }"><span class="tnum soft">{{ row.updateTime }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="perf:template:edit" icon-only icon="edit" label="编辑模板" :loading="loadingDetail" @click="openEdit(row)" />
        <PermissionButton perm="perf:template:edit" icon-only icon="trash" label="删除模板" danger @click="remove(row)" />
      </div>
    </template>

    <!-- 模板表单：基本信息 + 指标项编辑（指标项用表格承载，单元格即输入控件） -->
    <FormDialog
      :open="dialog"
      :title="isEdit ? '编辑指标模板' : '新增指标模板'"
      :sub="isEdit ? '已产生评分或归集数据的模板不能修改指标项结构' : '草稿可暂不满 100%；启用前权重合计必须恰好 100%'"
      :width="1040"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="模板编码" required :error="errors.templateCode" hint="唯一编码，如 PERF-COMMON">
        <TextInput v-model="form.templateCode" placeholder="PERF-COMMON" :disabled="isEdit" :invalid="!!errors.templateCode" />
      </Field>
      <Field label="模板名称" required :error="errors.templateName">
        <TextInput v-model="form.templateName" placeholder="如 通用季度绩效模板" :invalid="!!errors.templateName" />
      </Field>
      <Field label="模板状态" hint="启用前会校验权重合计 = 100%">
        <Select v-model="form.status" :options="TEMPLATE_STATUS_OPTIONS" />
      </Field>
      <Field label="说明">
        <TextInput v-model="form.description" placeholder="适用范围与评分说明" />
      </Field>

      <Field label="指标项" span group :error="itemsError">
        <div style="display: flex; flex-direction: column; gap: 10px">
          <div class="band-head" style="margin: 0">
            <span class="soft" style="font-size: 12.5px">
              权重合计
              <Tag :tone="weightSum === 100 ? 'ok' : 'danger'" style="margin-left: 6px">{{ weightSum }}%</Tag>
            </span>
            <Button variant="secondary" size="sm" icon="plus" @click="addItem">添加指标项</Button>
          </div>
          <p v-if="weightHint" class="faint" style="font-size: 12px; margin: 0">{{ weightHint }}</p>
          <p class="faint" style="font-size: 12px; margin: 0">
            可归集类只读（系统归集，禁止手工覆盖）；主观类必须填写评语。引用指标仅可选择指标字典中已登记的指标，未登记不得启用。
          </p>
          <DataTable :columns="itemColumns" :data="items" row-key="key" :min-width="1000">
            <template #cell-itemCode="{ row }"><TextInput v-model="row.itemCode" placeholder="如 kr_achievement" /></template>
            <template #cell-itemName="{ row }"><TextInput v-model="row.itemName" placeholder="如 KR 达成率" /></template>
            <template #cell-itemCategory="{ row }">
              <Select v-model="row.itemCategory" :options="ITEM_CATEGORY_OPTIONS" @update:model-value="onCategoryChange(row)" />
            </template>
            <template #cell-metricCode="{ row }">
              <Select
                v-model="row.metricCode"
                :options="metricOptions"
                :placeholder="row.itemCategory === 'subjective' ? '主观类无需引用' : '选择已登记指标'"
                :disabled="row.itemCategory !== 'collectable'"
                filterable
                clearable
              />
            </template>
            <template #cell-weight="{ row }"><TextInput v-model="row.weight" type="number" placeholder="权重" /></template>
            <template #cell-maxScore="{ row }"><TextInput v-model="row.maxScore" type="number" placeholder="100" /></template>
            <template #cell-actions="{ row }">
              <IconButton name="trash" label="删除该指标项" danger small @click="removeItem(items.indexOf(row))" />
            </template>
          </DataTable>
        </div>
      </Field>

      <Field label="备注" span>
        <TextInput v-model="form.remark" placeholder="选填" multiline :rows="2" />
      </Field>
    </FormDialog>

    <!-- 指标字典：R2 四要素登记内容，评审者可直接核对口径 -->
    <Drawer
      :open="dictOpen"
      title="指标字典"
      sub="R2：每个可归集指标都登记了数据来源模块、计算公式、取数时间范围与缺失值处理；未登记的指标不得上线。"
      :width="720"
      @close="dictOpen = false"
    >
      <div v-if="metricsLoading" class="soft">正在加载指标字典…</div>
      <div v-else style="display: flex; flex-direction: column; gap: 16px">
        <Panel
          v-for="metric in metrics"
          :key="metric.metricCode"
          :title="metric.metricName"
          :meta="metric.metricCode"
        >
          <template #tools>
            <Tag tone="info">{{ metric.drillDownType === 'kr' ? '下钻到 KR 清单' : '下钻到任务清单' }}</Tag>
          </template>
          <dl style="margin: 0; display: flex; flex-direction: column; gap: 12px">
            <div>
              <dt class="faint" style="font-size: 11.5px">数据来源模块</dt>
              <dd class="soft" style="margin: 4px 0 0; font-size: 12.5px; line-height: 1.7">{{ metric.sourceModule }}</dd>
            </div>
            <div>
              <dt class="faint" style="font-size: 11.5px">计算公式</dt>
              <dd class="soft" style="margin: 4px 0 0; font-size: 12.5px; line-height: 1.7">{{ metric.formula }}</dd>
            </div>
            <div>
              <dt class="faint" style="font-size: 11.5px">取数时间范围</dt>
              <dd class="soft" style="margin: 4px 0 0; font-size: 12.5px; line-height: 1.7">{{ metric.timeRange }}</dd>
            </div>
            <div>
              <dt class="faint" style="font-size: 11.5px">缺失值处理</dt>
              <dd class="soft" style="margin: 4px 0 0; font-size: 12.5px; line-height: 1.7">{{ metric.missingPolicy }}</dd>
            </div>
          </dl>
        </Panel>
      </div>
    </Drawer>
  </ListPage>
</template>
