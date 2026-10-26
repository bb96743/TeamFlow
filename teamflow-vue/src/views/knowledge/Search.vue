<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Field, SectionLabel, Select, Tag, TextInput, type Column } from '@/components/ui'
import { ListPage, PermissionButton } from '@/components/business'
import { knowledgeApi, type KnowledgeSearchHit, type KnowledgeSearchResult } from '@/api/knowledge'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import { pathOf } from '@/composables/auth'
import {
  ENGINE_HINT,
  PAGE_TYPE_OPTIONS,
  dateTimeText,
  engineLabel,
  engineTone,
  highlightParts,
  pageTypeLabel,
} from './support'

/* ============================================================
   全文检索（M07 · 1.2.6）
   ------------------------------------------------------------
   · 只搜知识页面，并且只返回当前账号可读的页面（后端按权限链过滤）；
   · 结果区展示实际检索引擎与耗时：fulltext-ngram 为全文索引命中，
     单字关键词（<2 字）自动回退 LIKE 是后端分词设计，页面会明确说明；
   · 命中片段按关键词切分高亮（纯文本插值，不引入 Markdown/HTML 渲染库）；
   · 后端单次最多返回 50 条，页面在其上做本地分页展示。
   ============================================================ */

const router = useRouter()
const spaces = ref<Array<{ id: number; spaceName: string }>>([])
const engineInfo = ref<KnowledgeSearchResult | null>(null)

const list = useListPage<KnowledgeSearchHit, { keyword: string; spaceId: number | null; pageType: string }>({
  query: { keyword: '', spaceId: null, pageType: '' },
  pageSize: 10,
  immediate: false,
  fetch: async params => {
    const pageNum = Number(params.pageNum ?? 1)
    const pageSize = Number(params.pageSize ?? 10)
    const keyword = String(params.keyword ?? '').trim()
    if (!keyword) {
      engineInfo.value = null
      return { rows: [], total: 0, pageNum, pageSize }
    }
    const result = await knowledgeApi.search({
      keyword,
      spaceId: params.spaceId as number | null,
      pageType: String(params.pageType ?? ''),
      limit: 50,
    })
    engineInfo.value = result
    const from = (pageNum - 1) * pageSize
    return {
      rows: result.results.slice(from, from + pageSize),
      total: result.total,
      pageNum,
      pageSize,
    }
  },
})

const spaceOptions = computed(() => spaces.value.map(space => ({ label: space.spaceName, value: space.id })))

async function loadSpaces() {
  try {
    const result = await knowledgeApi.spaces.list({ pageNum: 1, pageSize: 100 })
    spaces.value = result.rows.map(space => ({ id: space.id, spaceName: space.spaceName }))
  } catch (error) {
    toastError(error, '知识空间加载失败')
  }
}

onMounted(loadSpaces)

function pickSpace(value: string | number | null | undefined) {
  list.query.spaceId = value === null || value === undefined || value === '' ? null : Number(value)
}

function search() {
  if (!list.query.keyword.trim()) {
    pushToast('请输入检索关键词', 'danger')
    return
  }
  list.search()
}

function resetSearch() {
  list.query.keyword = ''
  list.query.spaceId = null
  list.query.pageType = ''
  engineInfo.value = null
  list.rows.value = []
  list.total.value = 0
}

const columns: Column[] = [
  { key: 'pageTitle', title: '页面', minWidth: 230 },
  { key: 'snippet', title: '命中片段', minWidth: 330 },
  { key: 'pageType', title: '类型', width: 108 },
  { key: 'spaceName', title: '空间', width: 140 },
  { key: 'score', title: '相关度', width: 96, align: 'right' },
  { key: 'updateTime', title: '最近更新', width: 150 },
  { key: 'actions', title: '操作', width: 100, className: 'col-actions' },
]

function openPage(row: KnowledgeSearchHit) {
  const base = pathOf('knowledge/page/index') ?? '/knowledge/page'
  router.push({ path: base, query: { pageId: String(row.pageId) } })
}

function scoreText(score?: number | null): string {
  return score === null || score === undefined ? '—' : Number(score).toFixed(4)
}
</script>

<template>
  <ListPage
    title="全文检索"
    desc="在知识页面中检索标题、正文与标签；结果只包含当前账号按权限链可读的页面，并会标注实际使用的检索引擎与耗时。"
    table-title="检索结果"
    :list="list"
    :columns="columns"
    :min-width="1080"
    row-key="pageId"
  >
    <template #actions>
      <Button variant="secondary" icon="refresh" @click="resetSearch">重置条件</Button>
    </template>

    <template #before>
      <section class="band reveal">
        <div class="band-head">
          <SectionLabel title="检索条件" />
          <Tag tone="neutral">仅检索知识页面</Tag>
        </div>
        <div style="display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap">
          <Field label="关键词" wide>
            <TextInput
              v-model="list.query.keyword"
              icon="search"
              placeholder="输入中文或英文关键词；单字会自动回退 LIKE"
              clearable
              @enter="search"
            />
          </Field>
          <Field label="知识空间">
            <Select
              :model-value="list.query.spaceId"
              :options="spaceOptions"
              placeholder="全部可见空间"
              filterable
              clearable
              @update:model-value="pickSpace"
            />
          </Field>
          <Field label="页面类型">
            <Select v-model="list.query.pageType" :options="[{ label: '全部类型', value: '' }, ...PAGE_TYPE_OPTIONS]" />
          </Field>
          <Button variant="primary" icon="search" :loading="list.loading.value" @click="search">检索</Button>
        </div>
      </section>

      <section v-if="engineInfo" class="band reveal">
        <div class="band-head">
          <SectionLabel title="本次检索" />
          <div style="display: flex; gap: 8px; flex-wrap: wrap">
            <Tag :tone="engineTone(engineInfo.engine)" dot>{{ engineLabel(engineInfo.engine) }}</Tag>
            <Tag tone="neutral">耗时 {{ engineInfo.elapsedMs }} ms</Tag>
            <Tag tone="neutral">命中 {{ engineInfo.total }} 条</Tag>
            <Tag v-if="engineInfo.spaceId" tone="info">已限定空间</Tag>
          </div>
        </div>
        <p class="cell-sub" style="line-height: 1.8">{{ engineInfo.note }}</p>
        <p class="faint" style="font-size: 12px; line-height: 1.8">{{ ENGINE_HINT }}</p>
      </section>
    </template>

    <template #cell-pageTitle="{ row }">
      <div>
        <div class="cell-title">{{ row.pageTitle }}</div>
        <div class="cell-sub">
          {{ pageTypeLabel(row.pageType) }}<template v-if="row.tags"> · {{ row.tags }}</template>
        </div>
      </div>
    </template>
    <template #cell-snippet="{ row }">
      <span class="cell-sub" style="line-height: 1.7">
        <template v-for="(part, index) in highlightParts(row.snippet, engineInfo?.keyword)" :key="index">
          <span
            v-if="part.hit"
            :style="{ background: 'var(--blue-soft)', color: 'var(--blue)', borderRadius: '3px', padding: '0 2px', fontWeight: 620 }"
          >{{ part.text }}</span>
          <template v-else>{{ part.text }}</template>
        </template>
        <span v-if="!row.snippet" class="faint">命中标题或标签</span>
      </span>
    </template>
    <template #cell-pageType="{ row }"><Tag tone="neutral">{{ pageTypeLabel(row.pageType) }}</Tag></template>
    <template #cell-spaceName="{ row }"><span class="muted">{{ row.spaceName || '—' }}</span></template>
    <template #cell-score="{ row }"><span class="tnum soft">{{ scoreText(row.score) }}</span></template>
    <template #cell-updateTime="{ row }"><span class="tnum soft">{{ dateTimeText(row.updateTime) }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="knowledge:page:list" icon-only icon="eye" label="打开页面" @click="openPage(row)" />
      </div>
    </template>
  </ListPage>
</template>
