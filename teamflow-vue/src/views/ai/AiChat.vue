<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Avatar,
  Button,
  EmptyState,
  Field,
  PageHeader,
  SectionLabel,
  SpecPlate,
  Tag,
  TextInput,
} from '@/components/ui'
import { PermissionButton } from '@/components/business'
import { aiApi, type AiChatResponse, type AiMessageRow, type AiSessionRow } from '@/api/ai'
import { pathOf } from '@/composables/auth'
import { pushToast, toastError } from '@/composables/toast'
import {
  dateTimeText,
  intentText,
  messageCitations,
  messageToolAllowed,
  messageToolParams,
  modeLabel,
  paramsText,
  shortTimeText,
  toolLabel,
} from './support'

/* ============================================================
   对话助手（/ai/chat）
   ------------------------------------------------------------
   · 后端默认规则意图识别（无大模型）：页面显式标注，不暗示生成式能力；
   · 每次回答展示「意图 / 工具 / 参数 / 是否允许执行」轨迹，拒绝原因原样呈现；
   · 工具以当前账号权限执行，回答里的越权提示由后端返回，页面不改写。
   ============================================================ */

const router = useRouter()

const sessions = ref<AiSessionRow[]>([])
const sessionsLoading = ref(false)
const activeId = ref<number | null>(null)
const messages = ref<AiMessageRow[]>([])
const messagesLoading = ref(false)
const draft = ref('')
const sending = ref(false)
const lastExecutor = ref<string | null>(null)
const threadEl = ref<HTMLElement | null>(null)

const activeSession = computed(() => sessions.value.find(item => item.id === activeId.value) ?? null)
const assistantMessages = computed(() => messages.value.filter(message => message.role === 'assistant'))
const latestAssistant = computed(() => assistantMessages.value[assistantMessages.value.length - 1] ?? null)
const modeText = computed(() => modeLabel(latestAssistant.value?.modelName ?? lastExecutor.value))
const specRows = computed(() => [
  { label: '运行模式', value: modeText.value },
  { label: '会话数', value: sessions.value.length },
  { label: '当前会话消息', value: activeId.value ? messages.value.length : '新对话' },
])

const SUGGESTIONS = [
  '帮我查我的任务',
  '查张三的绩效',
  '本周有哪些会议',
  '知识库里联调窗口提前到哪一周？',
  '生成日报草稿',
]

onMounted(async () => {
  await loadSessions()
  const first = sessions.value[0]
  if (first) await selectSession(first)
})

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const page = await aiApi.sessions({ pageNum: 1, pageSize: 50 })
    sessions.value = page.rows
  } catch (error) {
    toastError(error, '会话列表加载失败')
  } finally {
    sessionsLoading.value = false
  }
}

async function loadMessages() {
  if (activeId.value === null) {
    messages.value = []
    return
  }
  messagesLoading.value = true
  try {
    messages.value = await aiApi.messages(activeId.value)
    await scrollToBottom()
  } catch (error) {
    toastError(error, '会话消息加载失败')
  } finally {
    messagesLoading.value = false
  }
}

async function selectSession(row: AiSessionRow) {
  activeId.value = row.id
  await loadMessages()
}

function newChat() {
  activeId.value = null
  messages.value = []
  lastExecutor.value = null
  draft.value = ''
}

async function refreshCurrent() {
  await loadSessions()
  await loadMessages()
}

async function scrollToBottom() {
  await nextTick()
  if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
}

async function send() {
  const content = draft.value.trim()
  if (!content) {
    pushToast('请输入要问助手的问题', 'danger')
    return
  }
  if (sending.value) return
  const localUser: AiMessageRow = {
    id: -Date.now(),
    sessionId: activeId.value ?? 0,
    role: 'user',
    content,
    status: 'ok',
    createTime: new Date().toISOString(),
  }
  messages.value.push(localUser)
  draft.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const response = await aiApi.chat({ message: content, sessionId: activeId.value })
    activeId.value = response.sessionId
    lastExecutor.value = response.executor ?? null
    await loadSessions()
    await loadMessages()
    if (!messages.value.length) {
      messages.value.push(fallbackAssistant(response))
    }
  } catch (error) {
    messages.value = messages.value.filter(message => message.id !== localUser.id)
    toastError(error, '助手暂时无法回答，请稍后重试')
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

/** 极端情况下服务端消息回放失败时，至少把本次回答展示出来（不伪造工具轨迹）。 */
function fallbackAssistant(response: AiChatResponse): AiMessageRow {
  return {
    id: response.messageId,
    sessionId: response.sessionId,
    role: 'assistant',
    content: response.answer,
    intentCode: response.intent?.code ?? null,
    intentLabel: response.intent?.label ?? null,
    toolName: response.tool?.name ?? null,
    toolParams: response.tool?.params ? JSON.stringify(response.tool.params) : null,
    citations: response.citations?.length ? JSON.stringify(response.citations) : null,
    modelName: response.executor ?? 'rule',
    status: response.status,
    elapsedMs: response.elapsedMs ?? null,
    createTime: new Date().toISOString(),
  }
}

function askSuggestion(text: string) {
  draft.value = text
  void send()
}

function openCitation(pageId: number) {
  const base = pathOf('knowledge/page/index') ?? '/knowledge/page'
  router.push({ path: base, query: { pageId: String(pageId) } })
}

function messageMode(message: AiMessageRow): string {
  return modeLabel(message.modelName)
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="对话助手"
      desc="用自然语言查询任务、绩效、目标、会议或知识库。当前默认使用规则意图识别（未启用大模型）；每次回答都会展示调用了哪个工具、参数是什么、是否被允许执行。"
    >
      <template #spec>
        <SpecPlate title="运行信息" :rows="specRows" />
      </template>
      <Button variant="secondary" icon="plus" @click="newChat">新建对话</Button>
      <PermissionButton perm="ai:chat:use" variant="primary" icon="refresh" :loading="messagesLoading || sessionsLoading" @click="refreshCurrent">刷新</PermissionButton>
    </PageHeader>

    <div class="split">
      <aside class="split-side m-surface reveal">
        <div class="split-side-head">
          <SectionLabel title="我的会话" />
          <span class="cell-sub">按最近一次对话排序</span>
        </div>
        <p v-if="sessionsLoading && !sessions.length" class="faint" style="padding: 8px; font-size: 12px">会话加载中…</p>
        <div v-else-if="!sessions.length" style="padding: 8px">
          <EmptyState title="还没有会话" desc="在右侧输入问题开始第一次对话，系统会自动创建会话。" icon="activity" />
        </div>
        <div v-else role="listbox" aria-label="会话列表">
          <button
            v-for="item in sessions"
            :key="item.id"
            type="button"
            role="option"
            :aria-selected="item.id === activeId"
            :class="['nav-item', item.id === activeId && 'is-active']"
            style="width: 100%; justify-content: space-between; gap: 8px"
            @click="selectSession(item)"
          >
            <span style="display: flex; flex-direction: column; min-width: 0; text-align: left">
              <span class="nav-label ellipsis" style="font-weight: 620">{{ item.sessionTitle || '未命名对话' }}</span>
              <span class="faint" style="font-size: 11px">{{ item.messageCount ?? 0 }} 条消息</span>
            </span>
            <span class="faint tnum" style="font-size: 10.5px">{{ shortTimeText(item.lastMessageTime || item.createTime) }}</span>
          </button>
        </div>
      </aside>

      <section class="stack">
        <div class="panel m-surface reveal" style="display: flex; flex-direction: column; min-height: 560px">
          <header class="panel-head" style="min-height: 56px">
            <div style="display: flex; align-items: baseline; gap: 10px; min-width: 0">
              <SectionLabel :title="activeSession?.sessionTitle || '新对话'" />
              <Tag tone="info">{{ modeText }}</Tag>
            </div>
            <div class="panel-tools">
              <Button variant="ghost" size="sm" icon="refresh" :loading="messagesLoading" @click="loadMessages">刷新</Button>
            </div>
          </header>

          <div ref="threadEl" style="flex: 1; min-height: 360px; max-height: calc(100dvh - 470px); overflow-y: auto; padding: 10px 20px 16px; display: flex; flex-direction: column; gap: 16px">
            <div v-if="!messages.length && !messagesLoading" style="padding: 20px 0">
              <EmptyState title="开始一次对话" desc="可以问：我的任务、某人的绩效、本周会议、知识库规范，或让我生成日报草稿。" icon="activity" />
              <div style="display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 14px">
                <Button v-for="item in SUGGESTIONS" :key="item" variant="text" size="sm" @click="askSuggestion(item)">{{ item }}</Button>
              </div>
            </div>

            <div
              v-for="message in messages"
              :key="message.id"
              :style="{
                display: 'flex',
                gap: '10px',
                alignItems: 'flex-start',
                flexDirection: message.role === 'user' ? 'row-reverse' : 'row',
              }"
            >
              <Avatar :name="message.role === 'user' ? '我' : 'AI 助手'" :size="30" />
              <div style="min-width: 0; max-width: 82%">
                <div
                  class="faint"
                  :style="{ fontSize: '11px', marginBottom: '3px', textAlign: message.role === 'user' ? 'right' : 'left' }"
                >
                  {{ message.role === 'user' ? '我' : '助手' }} · {{ dateTimeText(message.createTime) }}
                  <template v-if="message.role === 'assistant'"> · {{ messageMode(message) }}</template>
                </div>

                <div
                  :class="message.role === 'user' ? 'm-pill' : 'm-surface'"
                  :style="{
                    padding: '10px 13px',
                    borderRadius: 'var(--r-md)',
                    fontSize: '13px',
                    lineHeight: 1.8,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }"
                >{{ message.content }}</div>

                <div v-if="message.role === 'assistant' && message.status === 'denied'" style="margin-top: 6px">
                  <Tag tone="danger" dot>已拒绝执行（权限提示由系统原样返回）</Tag>
                </div>

                <!-- 工具调用轨迹：意图 / 工具 / 参数 / 是否允许 -->
                <div
                  v-if="message.role === 'assistant' && message.toolName"
                  class="m-surface"
                  style="margin-top: 8px; padding: 10px 12px; border-radius: var(--r-sm); font-size: 12px"
                >
                  <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
                    <Tag :tone="messageToolAllowed(message) ? 'ok' : 'danger'" dot>
                      {{ messageToolAllowed(message) ? '已允许执行' : '已拒绝执行' }}
                    </Tag>
                    <span style="font-weight: 620">{{ toolLabel(message.toolName) }}</span>
                    <span class="faint">{{ message.toolName }}</span>
                  </div>
                  <p class="cell-sub" style="margin: 6px 0 0">
                    意图：{{ intentText(message.intentCode, message.intentLabel) }}
                    <template v-if="message.elapsedMs != null"> · 耗时 {{ message.elapsedMs }} ms</template>
                  </p>
                  <pre
                    v-if="paramsText(messageToolParams(message))"
                    class="code-block"
                    style="margin-top: 8px; white-space: pre-wrap; font-size: 11.5px"
                  >工具参数：{{ paramsText(messageToolParams(message)) }}</pre>
                </div>

                <!-- 引用（知识问答时会返回） -->
                <div v-if="message.role === 'assistant' && messageCitations(message).length" style="margin-top: 8px; display: flex; flex-direction: column; gap: 6px">
                  <SectionLabel title="引用来源" />
                  <div
                    v-for="citation in messageCitations(message)"
                    :key="citation.pageId"
                    class="m-surface"
                    style="padding: 8px 10px; border-radius: var(--r-sm); font-size: 12px"
                  >
                    <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
                      <span style="font-weight: 620">{{ citation.pageTitle }}</span>
                      <Button variant="text" size="sm" icon="arrowRight" @click="openCitation(citation.pageId)">打开页面</Button>
                    </div>
                    <p class="cell-sub" style="margin: 4px 0 0; line-height: 1.7">{{ citation.snippet }}</p>
                    <p class="faint" style="font-size: 11px; margin: 3px 0 0">页面 ID {{ citation.pageId }} · 偏移 {{ citation.offset ?? 0 }}</p>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="sending" class="faint" style="font-size: 12px; padding-left: 40px">助手正在查询…</div>
          </div>

          <div style="border-top: 1px solid var(--line-strong); padding: 14px 20px 18px">
            <Field label="向助手提问" hint="当前为规则意图识别；工具以你的账号权限执行，无权限时原样返回拒绝原因">
              <TextInput
                v-model="draft"
                multiline
                :rows="3"
                placeholder="例如：帮我查我的任务 / 本周有哪些会议 / 知识库里联调窗口提前到哪一周？"
                :maxlength="1000"
              />
            </Field>
            <div style="display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 10px">
              <span class="faint" style="font-size: 11.5px">回答与工具调用轨迹会保存在当前会话中</span>
              <PermissionButton perm="ai:chat:use" variant="primary" icon="arrowRight" :loading="sending" @click="send">发送</PermissionButton>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
