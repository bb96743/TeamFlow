<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button,
  EmptyState,
  Field,
  PageHeader,
  Panel,
  SectionLabel,
  SpecPlate,
  Tag,
  TextInput,
} from '@/components/ui'
import { PermissionButton } from '@/components/business'
import { aiApi, type AiCitation, type AiQaResponse } from '@/api/ai'
import { pathOf } from '@/composables/auth'
import { pushToast, toastError } from '@/composables/toast'
import { engineLabel, engineTone, highlightTerms, questionTerms } from './support'

/* ============================================================
   知识问答（/ai/qa）
   ------------------------------------------------------------
   · 检索走知识库全文索引（M07 ngram；单字自动回落 LIKE），返回 answer + citations；
   · 页面只展示后端返回的片段并做高亮，不重新检索、不改写片段；
   · 当前未调用大模型，回答由检索结果组织，页面如实标注。
   ============================================================ */

const router = useRouter()
const question = ref('')
const askedQuestion = ref('')
const loading = ref(false)
const result = ref<AiQaResponse | null>(null)

const terms = computed(() => questionTerms(askedQuestion.value))
const specRows = computed(() => [
  { label: '检索方式', value: '知识库全文索引（ngram；单字回落 LIKE）' },
  { label: '运行模式', value: '检索式回答（未启用大模型）' },
  { label: '引用口径', value: '页面 ID + 标题 + 片段 + 偏移' },
])

const SUGGESTIONS = [
  '联调窗口提前到哪一周？',
  '知识库里的接口规范',
  '性能测试的执行流程',
]

async function ask() {
  const text = question.value.trim()
  if (!text) {
    pushToast('请输入要检索的问题', 'danger')
    return
  }
  loading.value = true
  try {
    result.value = await aiApi.qa({ question: text })
    askedQuestion.value = text
  } catch (error) {
    toastError(error, '知识库问答失败')
  } finally {
    loading.value = false
  }
}

function askSuggestion(text: string) {
  question.value = text
  void ask()
}

function reset() {
  question.value = ''
  askedQuestion.value = ''
  result.value = null
}

function openCitation(citation: AiCitation) {
  const base = pathOf('knowledge/page/index') ?? '/knowledge/page'
  router.push({ path: base, query: { pageId: String(citation.pageId) } })
}

function partsOf(snippet?: string | null) {
  return highlightTerms(snippet, terms.value)
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="知识问答"
      desc="提问后会检索知识库中的可读页面，并给出答案与引用来源（页面 ID、标题、命中片段、偏移）。当前未调用大模型，回答由检索片段组织。"
    >
      <template #spec>
        <SpecPlate title="检索信息" :rows="specRows" />
      </template>
      <Button v-if="result" variant="secondary" icon="refresh" @click="reset">重新提问</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="提问" />
        <Tag tone="neutral">只检索你当前可读的知识页面</Tag>
      </div>
      <div style="display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap">
        <Field label="问题" wide>
          <TextInput
            v-model="question"
            multiline
            :rows="2"
            placeholder="例如：联调窗口提前到哪一周？/ 接口规范的变更流程是什么？"
            :maxlength="500"
          />
        </Field>
        <PermissionButton perm="ai:kb:ask" variant="primary" icon="search" :loading="loading" @click="ask">检索并回答</PermissionButton>
      </div>
      <p class="faint" style="font-size: 12px; line-height: 1.8; margin-top: 10px">
        中文全文索引最小分词长度为 2 个字：单字关键词会自动回退 LIKE 检索，这是检索设计而非异常。
      </p>
    </section>

    <div v-if="!result" class="panel m-surface reveal" style="padding: 18px 20px">
      <EmptyState title="还没有提问" desc="输入一个知识库相关的问题；页面会返回答案与可追溯的引用来源。" icon="search">
        <div style="display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 10px">
          <Button v-for="item in SUGGESTIONS" :key="item" variant="text" size="sm" @click="askSuggestion(item)">{{ item }}</Button>
        </div>
      </EmptyState>
    </div>

    <div v-else class="stack">
      <Panel title="回答" :meta="`${engineLabel(result.engine)} · 耗时 ${result.elapsedMs ?? '—'} ms`">
        <template #tools>
          <Tag :tone="engineTone(result.engine)" dot>{{ engineLabel(result.engine) }}</Tag>
          <Tag tone="neutral">命中 {{ result.total ?? result.citations.length }} 条</Tag>
          <Tag tone="info">检索式回答（未启用大模型）</Tag>
        </template>
        <p style="white-space: pre-wrap; line-height: 1.9; font-size: 13.5px; margin: 0">{{ result.answer }}</p>
        <p class="faint" style="font-size: 12px; line-height: 1.8; margin: 10px 0 0">
          回答只依据检索到的知识片段组织；若片段不足会明确提示“未找到依据”，不会编造来源。
        </p>
      </Panel>

      <Panel title="引用来源" :meta="`共 ${result.citations.length} 条 · 来自知识库`">
        <div v-if="!result.citations.length" style="padding: 12px 0">
          <EmptyState title="未找到引用来源" desc="可以换一个更具体的关键词，或请文档负责人补充知识页面。" icon="file" />
        </div>
        <div v-else style="display: flex; flex-direction: column; gap: 10px">
          <article
            v-for="citation in result.citations"
            :key="`${citation.pageId}-${citation.offset ?? 0}`"
            class="m-surface"
            style="padding: 12px 14px; border-radius: var(--r-md)"
          >
            <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 10px">
              <div style="min-width: 0">
                <div class="cell-title">{{ citation.pageTitle }}</div>
                <div class="cell-sub">
                  页面 ID {{ citation.pageId }}
                  <template v-if="citation.spaceName"> · {{ citation.spaceName }}</template>
                  · 片段偏移 {{ citation.offset ?? 0 }}
                </div>
              </div>
              <Button variant="secondary" size="sm" icon="arrowRight" @click="openCitation(citation)">打开知识页面</Button>
            </div>
            <p class="cell-sub" style="margin: 8px 0 0; line-height: 1.8; white-space: pre-wrap">
              <template v-for="(part, index) in partsOf(citation.snippet)" :key="index">
                <span
                  v-if="part.hit"
                  :style="{ background: 'var(--blue-soft)', color: 'var(--blue)', borderRadius: '3px', padding: '0 2px', fontWeight: 620 }"
                >{{ part.text }}</span>
                <template v-else>{{ part.text }}</template>
              </template>
            </p>
          </article>
        </div>
      </Panel>
    </div>
  </div>
</template>
