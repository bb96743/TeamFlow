<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  Drawer,
  Field,
  SectionLabel,
  Select,
  SpecPlate,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { aiApi, type AiReportDetail, type AiReportRow } from '@/api/ai'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'
import { dateTimeText, reportTypeLabel } from './support'

/* ============================================================
   日报周报（/ai/report）
   ------------------------------------------------------------
   · 报告由任务、目标、会议数据按模板生成（当前未启用大模型），只产草稿；
   · 草稿可编辑，但系统没有发布动作——页面不提供发布按钮，并明确标注需人工确认；
   · 列表使用统一列表页（查询 / 分页 / 刷新），详情与编辑走既有抽屉与表单弹窗。
   ============================================================ */

const list = useListPage<AiReportRow, { reportType: string }>({
  query: { reportType: '' },
  fetch: params => aiApi.reports.list(params),
  pageSize: 10,
})

const columns: Column[] = [
  { key: 'reportTitle', title: '报告标题', minWidth: 260 },
  { key: 'reportType', title: '类型', width: 90 },
  { key: 'period', title: '统计日期 / 区间', width: 190 },
  { key: 'status', title: '状态', width: 120 },
  { key: 'updateTime', title: '最近更新', width: 150 },
  { key: 'actions', title: '操作', width: 110, className: 'col-actions' },
]

const REPORT_TYPE_OPTIONS = [
  { label: '全部类型', value: '' },
  { label: '日报', value: 'daily' },
  { label: '周报', value: 'weekly' },
]

/* ── 生成草稿 ── */
const generating = ref<'daily' | 'weekly' | null>(null)

async function generate(type: 'daily' | 'weekly') {
  if (generating.value) return
  generating.value = type
  try {
    const created = type === 'daily' ? await aiApi.reports.daily({}) : await aiApi.reports.weekly({})
    pushToast(`已生成${reportTypeLabel(type)}草稿，请人工确认后使用`)
    await list.search()
    await openDetail(created.id)
  } catch (error) {
    toastError(error, `${reportTypeLabel(type)}草稿生成失败`)
  } finally {
    generating.value = null
  }
}

/* ── 详情 ── */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<AiReportDetail | null>(null)

async function openDetail(id: number) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await aiApi.reports.detail(id)
  } catch (error) {
    toastError(error, '报告详情加载失败')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

/* ── 编辑草稿 ── */
const editOpen = ref(false)
const editSaving = ref(false)
const editForm = reactive({ id: 0, reportTitle: '', content: '' })
const editErrors = ref<Partial<Record<'reportTitle' | 'content', string>>>({})

async function openEdit(row: AiReportRow) {
  try {
    const loaded = await aiApi.reports.detail(row.id)
    editForm.id = loaded.id
    editForm.reportTitle = loaded.reportTitle
    editForm.content = loaded.content
    editErrors.value = {}
    editOpen.value = true
  } catch (error) {
    toastError(error, '报告详情加载失败')
  }
}

async function submitEdit() {
  editErrors.value = validate(editForm, {
    reportTitle: [required('请填写报告标题')],
    content: [required('请填写报告正文')],
  })
  if (Object.keys(editErrors.value).length) return
  editSaving.value = true
  try {
    await aiApi.reports.update(editForm.id, {
      reportTitle: editForm.reportTitle.trim(),
      content: editForm.content,
    })
    editOpen.value = false
    pushToast('草稿已保存；仍为草稿，需人工确认后使用')
    await list.load()
    if (detail.value?.id === editForm.id) await openDetail(editForm.id)
  } catch (error) {
    toastError(error, '草稿保存失败')
  } finally {
    editSaving.value = false
  }
}

function periodText(row: AiReportRow): string {
  if (row.reportType === 'daily') return row.reportDate || '—'
  return `${row.periodStart || '—'} ~ ${row.periodEnd || '—'}`
}
</script>

<template>
  <ListPage
    title="日报周报"
    desc="从任务、目标与会议数据生成日报/周报草稿（当前未启用大模型）。草稿可编辑，但系统不提供发布动作，请人工确认后复制使用。"
    table-title="报告草稿"
    :list="list"
    :columns="columns"
    :min-width="1020"
    row-key="id"
  >
    <template #spec>
      <SpecPlate
        title="草稿说明"
        :rows="[
          { label: '状态', value: '仅草稿（无发布动作）' },
          { label: '生成方式', value: '任务/目标/会议数据按模板生成' },
          { label: '运行模式', value: '未启用大模型' },
          { label: '报告数', value: list.total.value },
        ]"
      />
    </template>

    <template #actions>
      <PermissionButton perm="ai:report:generate" variant="primary" icon="file" :loading="generating === 'daily'" @click="generate('daily')">生成日报草稿</PermissionButton>
      <PermissionButton perm="ai:report:generate" variant="secondary" icon="file" :loading="generating === 'weekly'" @click="generate('weekly')">生成周报草稿</PermissionButton>
    </template>

    <template #search>
      <Field label="报告类型">
        <Select v-model="list.query.reportType" :options="REPORT_TYPE_OPTIONS" />
      </Field>
    </template>

    <template #before>
      <section class="band reveal">
        <div class="band-head">
          <SectionLabel title="使用说明" />
          <Tag tone="danger" dot>草稿，需人工确认后使用</Tag>
        </div>
        <p class="cell-sub" style="line-height: 1.8">
          生成结果只保存为草稿，系统没有“发布/发送”动作；请确认内容后自行复制到目标渠道。未取得目标或会议查看权限时，对应段落会明确标注“未纳入”，不会越权取数。
        </p>
      </section>
    </template>

    <template #cell-reportTitle="{ row }">
      <div>
        <div class="cell-title">{{ row.reportTitle }}</div>
        <div class="cell-sub">由任务 / 目标 / 会议数据生成，当前为规则模板</div>
      </div>
    </template>
    <template #cell-reportType="{ row }">
      <Tag tone="neutral">{{ reportTypeLabel(row.reportType) }}</Tag>
    </template>
    <template #cell-period="{ row }"><span class="tnum soft">{{ periodText(row) }}</span></template>
    <template #cell-status="{ row }">
      <div style="display: flex; flex-direction: column; gap: 2px">
        <Tag tone="danger" dot>{{ row.status === 'draft' ? '草稿' : row.status }}</Tag>
        <span v-if="row.status === 'draft'" class="faint" style="font-size: 10.5px">需人工确认</span>
      </div>
    </template>
    <template #cell-updateTime="{ row }"><span class="tnum soft">{{ dateTimeText(row.updateTime) }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="ai:report:generate" icon-only icon="eye" label="查看报告草稿" @click="openDetail(row.id)" />
        <PermissionButton perm="ai:report:generate" icon-only icon="edit" label="编辑报告草稿" @click="openEdit(row)" />
      </div>
    </template>
  </ListPage>

  <!-- 草稿详情：只读展示；没有发布按钮 -->
  <Drawer
    :open="detailOpen"
    :title="detail?.reportTitle || '报告草稿'"
    :sub="detail ? `${reportTypeLabel(detail.reportType)} · ${periodText(detail)} · 草稿` : ''"
    :width="760"
    @close="detailOpen = false"
  >
    <div v-if="detailLoading" class="faint">正在加载报告草稿…</div>
    <div v-else-if="detail" style="display: flex; flex-direction: column; gap: 14px">
      <div style="display: flex; gap: 8px; flex-wrap: wrap">
        <Tag tone="danger" dot>草稿</Tag>
        <Tag tone="neutral">{{ reportTypeLabel(detail.reportType) }}</Tag>
        <Tag tone="info">{{ periodText(detail) }}</Tag>
      </div>
      <section class="band">
        <div class="band-head">
          <SectionLabel title="草稿说明" />
        </div>
        <p class="cell-sub" style="line-height: 1.8">
          本内容为草稿，需人工确认后使用；系统没有发布动作。未取得目标或会议查看权限时，对应段落明确标注“未纳入”，不会越权取数。
        </p>
      </section>
      <div v-if="detail.sourceSummary">
        <SectionLabel title="取数摘要" />
        <p class="cell-sub" style="line-height: 1.8">{{ detail.sourceSummary }}</p>
      </div>
      <div>
        <SectionLabel title="报告正文（Markdown 草稿）" />
        <pre class="code-block" style="white-space: pre-wrap">{{ detail.content }}</pre>
      </div>
      <p class="faint" style="font-size: 12px; line-height: 1.8">
        最近更新 {{ dateTimeText(detail.updateTime) }} · 负责人 {{ detail.ownerName || '—' }}
      </p>
    </div>
    <template #footer>
      <div style="display: flex; align-items: center; justify-content: space-between; width: 100%; gap: 10px">
        <span class="faint" style="font-size: 12px">没有发布按钮：请人工确认后复制使用</span>
        <PermissionButton perm="ai:report:generate" variant="primary" icon="edit" @click="detail && openEdit(detail)">编辑草稿</PermissionButton>
      </div>
    </template>
  </Drawer>

  <!-- 编辑草稿 -->
  <FormDialog
    :open="editOpen"
    title="编辑报告草稿"
    sub="保存后仍为草稿；系统不提供发布动作，请人工确认后使用"
    :width="760"
    :saving="editSaving"
    submit-text="保存草稿"
    @close="editOpen = false"
    @submit="submitEdit"
  >
    <Field label="报告标题" required :error="editErrors.reportTitle" span>
      <TextInput v-model="editForm.reportTitle" :invalid="!!editErrors.reportTitle" :maxlength="200" />
    </Field>
    <Field label="正文（Markdown）" required :error="editErrors.content" span>
      <TextInput
        v-model="editForm.content"
        multiline
        :rows="16"
        :invalid="!!editErrors.content"
        placeholder="对草稿内容进行人工修订"
      />
    </Field>
    <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
      编辑只更新草稿内容，不会自动发布或发送给他人。
    </p>
  </FormDialog>
</template>
