<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Field, Select, Tag, TextInput, type Column } from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { personalApi, type ReviewRow, type ReviewType, type ReviewWriteRequest } from '@/api/personal'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   工作复盘（M10 · 2.3.4）
   ------------------------------------------------------------
   复盘默认私有保存在个人效率模块；主动公开归档到知识库属于后续阶段。
   列表按复盘类型筛选，编辑时读取完整正文。
   ============================================================ */

const REVIEW_TYPES: Array<{ label: string; value: ReviewType }> = [
  { label: '日复盘', value: 'day' },
  { label: '周复盘', value: 'week' },
  { label: '月复盘', value: 'month' },
  { label: '自定义', value: 'custom' },
]
const TYPE_LABEL: Record<string, string> = { day: '日复盘', week: '周复盘', month: '月复盘', custom: '自定义' }
const FILTER_OPTIONS = [{ label: '全部类型', value: '' }, ...REVIEW_TYPES]

const list = useListPage<ReviewRow, { reviewType: string }>({
  query: { reviewType: '' },
  fetch: params => personalApi.reviews.list(params),
})

const columns: Column[] = [
  { key: 'reviewTitle', title: '复盘标题', minWidth: 240 },
  { key: 'reviewType', title: '类型', width: 108 },
  { key: 'period', title: '复盘周期', width: 200 },
  { key: 'tags', title: '标签', minWidth: 160 },
  { key: 'createTime', title: '创建时间', width: 148 },
  { key: 'actions', title: '操作', width: 96, className: 'col-actions' },
]

interface ReviewForm {
  id?: number
  reviewType: ReviewType
  reviewTitle: string
  periodStart: string
  periodEnd: string
  content: string
  tags: string
}
const dialog = ref(false)
const saving = ref(false)
const form = reactive<ReviewForm>(emptyForm())
const errors = ref<Partial<Record<keyof ReviewForm, string>>>({})

function emptyForm(): ReviewForm {
  return { reviewType: 'week', reviewTitle: '', periodStart: '', periodEnd: '', content: '', tags: '' }
}

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

async function openEdit(row: ReviewRow) {
  Object.assign(form, {
    id: row.id,
    reviewType: row.reviewType,
    reviewTitle: row.reviewTitle,
    periodStart: row.periodStart ?? '',
    periodEnd: row.periodEnd ?? '',
    content: '',
    tags: row.tags ?? '',
  })
  errors.value = {}
  dialog.value = true
  try {
    // 列表不返回正文，编辑时再读详情
    const detail = await personalApi.reviews.detail(row.id)
    form.content = detail.content ?? ''
  } catch (error) {
    toastError(error, '复盘正文加载失败')
  }
}

async function submit() {
  errors.value = validate(form, {
    reviewTitle: [required('请填写复盘标题')],
  })
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: ReviewWriteRequest = {
    reviewType: form.reviewType,
    reviewTitle: form.reviewTitle.trim(),
    periodStart: form.periodStart || undefined,
    periodEnd: form.periodEnd || undefined,
    content: form.content.trim() || undefined,
    tags: form.tags.trim() || undefined,
  }
  try {
    if (form.id) {
      await personalApi.reviews.update(form.id, payload)
      pushToast('复盘已更新')
    } else {
      await personalApi.reviews.create(payload)
      pushToast('复盘已保存')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '复盘保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: ReviewRow) {
  const ok = await confirm({
    title: '删除复盘',
    message: `确认删除复盘「${row.reviewTitle}」吗？删除后无法在列表中找回。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await personalApi.reviews.remove(row.id)
    pushToast('复盘已删除')
    await list.load()
  } catch (error) {
    toastError(error, '复盘删除失败')
  }
}
</script>

<template>
  <ListPage
    title="工作复盘"
    desc="记录阶段性的经验与改进项，默认仅本人可见。复盘按日 / 周 / 月存档，后续可归档到知识库沉淀为团队经验。"
    table-title="复盘列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="personal:review:add" variant="primary" icon="plus" @click="openCreate">写复盘</PermissionButton>
    </template>

    <template #search>
      <Field label="复盘类型">
        <Select v-model="list.query.reviewType" :options="FILTER_OPTIONS" />
      </Field>
    </template>

    <template #cell-reviewTitle="{ row }"><span class="cell-title">{{ row.reviewTitle }}</span></template>
    <template #cell-reviewType="{ row }">
      <Tag tone="info">{{ TYPE_LABEL[row.reviewType] ?? row.reviewType }}</Tag>
    </template>
    <template #cell-period="{ row }">
      <span class="tnum soft">{{ row.periodStart || '—' }} 至 {{ row.periodEnd || '—' }}</span>
    </template>
    <template #cell-tags="{ row }">
      <span class="muted">{{ row.tags || '—' }}</span>
    </template>
    <template #cell-createTime="{ row }"><span class="tnum soft">{{ row.createTime || '—' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="personal:review:edit" icon-only icon="edit" label="编辑复盘" @click="openEdit(row)" />
        <PermissionButton perm="personal:review:remove" icon-only icon="trash" label="删除复盘" danger @click="remove(row)" />
      </div>
    </template>

    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑复盘' : '写复盘'"
      sub="复盘默认私有保存；正文用于沉淀经验，不对外公开。"
      :width="680"
      :saving="saving"
      @close="dialog = false"
      @submit="submit"
    >
      <Field label="复盘标题" required :error="errors.reviewTitle" wide>
        <TextInput v-model="form.reviewTitle" placeholder="如：本周交付复盘" :invalid="!!errors.reviewTitle" :maxlength="200" />
      </Field>
      <Field label="复盘类型">
        <Select v-model="form.reviewType" :options="REVIEW_TYPES" />
      </Field>
      <Field label="标签" hint="逗号分隔，便于以后检索">
        <TextInput v-model="form.tags" placeholder="如：交付,协作" :maxlength="200" />
      </Field>
      <Field label="周期开始" hint="yyyy-MM-dd">
        <TextInput v-model="form.periodStart" placeholder="如：2026-10-19" />
      </Field>
      <Field label="周期结束" hint="yyyy-MM-dd">
        <TextInput v-model="form.periodEnd" placeholder="如：2026-10-25" />
      </Field>
      <Field label="复盘正文" span>
        <TextInput
          v-model="form.content"
          multiline
          :rows="8"
          placeholder="做对了什么、遇到了什么问题、下一步怎么改进"
          :maxlength="4000"
        />
      </Field>
    </FormDialog>
  </ListPage>
</template>
