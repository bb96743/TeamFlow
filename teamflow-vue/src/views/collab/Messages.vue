<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Avatar,
  Button,
  EmptyState,
  Field,
  IconButton,
  PageHeader,
  Pagination,
  SectionLabel,
  Segmented,
  SpecPlate,
  Tag,
  TextInput,
} from '@/components/ui'
import { FormDialog, PermissionButton, UserSelect } from '@/components/business'
import { systemApi } from '@/api'
import { collabApi, type CollabConversationRow, type CollabMessageRow } from '@/api/collab'
import { currentUser } from '@/composables/auth'
import { useListPage } from '@/composables/list'
import { pushToast, toastError } from '@/composables/toast'
import { required } from '@/utils/validate'
import { CONVERSATION_TYPE_TEXT, dateTimeText, shortTimeText } from './support'

/* ============================================================
   站内信：会话 + 消息的 IM 布局。
   · 未读数完全来自后端（会话行的 unreadCount 与 /unread-count），
     打开会话只做一件事：把「已读到的消息 ID」提交给后端推进游标；
   · 这里只做消息存储，不做待办——「需要我处理」的事项在个人效率的待办收件箱；
   · @提及由后端解析并投递通知，输入框提示 @账号 即可。
   ============================================================ */

const router = useRouter()

const conversations = ref<CollabConversationRow[]>([])
const conversationsLoading = ref(false)
const unread = ref({ unreadCount: 0, unreadNotifications: 0 })
const activeId = ref<number | null>(null)
const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value) ?? null)
const myId = computed(() => currentUser.value?.id ?? null)

const messages = useListPage<CollabMessageRow, Record<string, never>>({
  query: {},
  immediate: false,
  pageSize: 50,
  fetch: async params => {
    if (!activeId.value) return { pageNum: 1, pageSize: 50, total: 0, rows: [] }
    return collabApi.conversations.messages(activeId.value, params)
  },
})

/** 接口按消息 ID 倒序返回；聊天区按时间正序展示 */
const orderedMessages = computed(() => [...messages.rows.value].reverse())

async function loadConversations() {
  conversationsLoading.value = true
  try {
    conversations.value = await collabApi.conversations.list()
  } catch (error) {
    toastError(error, '会话列表加载失败')
  } finally {
    conversationsLoading.value = false
  }
}

async function loadUnread() {
  try {
    unread.value = await collabApi.conversations.unreadCount()
  } catch {
    // 未读读数失败不影响会话操作
  }
}

async function refreshOverview() {
  await Promise.all([loadConversations(), loadUnread()])
}

onMounted(async () => {
  await refreshOverview()
  // 进入页面只预览最近会话，不代替用户「打开」——未读徽标保留到用户实际点开会话
  const recent = conversations.value.find(item => (item.unreadCount ?? 0) > 0) ?? conversations.value[0]
  if (recent) await selectConversation(recent, false)
})

async function selectConversation(row: CollabConversationRow, markReadAfter: boolean) {
  activeId.value = row.id
  await messages.search()
  if (markReadAfter) await markRead(row)
}

async function openConversation(row: CollabConversationRow) {
  await selectConversation(row, true)
}

/** 已读：只把最新一条消息的 ID 交给后端推进游标，未读数以后端返回为准 */
async function markRead(row: CollabConversationRow) {
  if (!row.unreadCount) return
  const latestId = messages.rows.value.reduce((max, message) => Math.max(max, message.id), 0)
  if (!latestId) return
  try {
    await collabApi.conversations.read(row.id, latestId)
    await refreshOverview()
  } catch (error) {
    toastError(error, '标记已读失败')
  }
}

/* ── 发送消息 ── */
const draft = ref('')
const sending = ref(false)

async function send() {
  if (!activeId.value) {
    pushToast('请先选择会话', 'danger')
    return
  }
  const content = draft.value.trim()
  if (!content) {
    pushToast('请输入消息内容', 'danger')
    return
  }
  sending.value = true
  try {
    await collabApi.conversations.send(activeId.value, { content })
    draft.value = ''
    await messages.search()
    await refreshOverview()
  } catch (error) {
    toastError(error, '消息发送失败')
  } finally {
    sending.value = false
  }
}

/** 消息关联的业务链接：站内跳转走路由，外链新开窗口 */
function openAction(url?: string | null) {
  if (!url) return
  if (url.startsWith('/')) router.push(url)
  else window.open(url, '_blank', 'noopener')
}

/* ── 发起会话 ── */
const createOpen = ref(false)
const createSaving = ref(false)
const createForm = reactive<{ conversationType: 'direct' | 'group'; title: string; memberIds: number[] }>({
  conversationType: 'direct',
  title: '',
  memberIds: [],
})
const createErrors = ref<Record<string, string>>({})
const pickedUserId = ref<number | null>(null)
const users = ref<Array<{ id: number; nickname: string; username: string }>>([])

const TYPE_OPTIONS = [
  { label: '单聊', value: 'direct' as const },
  { label: '群组', value: 'group' as const },
]

async function loadUsers() {
  if (users.value.length) return
  try {
    const page = await systemApi.users.list({ pageNum: 1, pageSize: 200, status: 1 })
    users.value = page.rows.map(row => ({ id: row.id, nickname: row.nickname, username: row.username }))
  } catch {
    users.value = []
  }
}

function openCreate() {
  createForm.conversationType = 'direct'
  createForm.title = ''
  createForm.memberIds = []
  createErrors.value = {}
  pickedUserId.value = null
  createOpen.value = true
  loadUsers()
}

function userLabel(userId: number): string {
  const found = users.value.find(user => user.id === userId)
  return found ? found.nickname : `用户 #${userId}`
}

function pickMember(value: string | number | null | undefined) {
  pickedUserId.value = value === null || value === undefined || value === '' ? null : Number(value)
}

function addMember() {
  const userId = pickedUserId.value
  if (!userId) {
    pushToast('请选择参与人', 'danger')
    return
  }
  if (createForm.conversationType === 'direct') {
    createForm.memberIds = [userId]
    return
  }
  if (!createForm.memberIds.includes(userId)) createForm.memberIds.push(userId)
  pickedUserId.value = null
}

function removeMember(userId: number) {
  createForm.memberIds = createForm.memberIds.filter(id => id !== userId)
}

function pickConversationType(value: 'direct' | 'group') {
  createForm.conversationType = value
  if (value === 'direct' && createForm.memberIds.length > 1) {
    createForm.memberIds = createForm.memberIds.slice(0, 1)
  }
}

async function submitCreate() {
  const next: Record<string, string> = {}
  if (!createForm.memberIds.length) next.members = '请选择会话参与人'
  if (createForm.conversationType === 'direct' && createForm.memberIds.length !== 1) {
    next.members = '单聊只能选择一个参与人'
  }
  if (createForm.conversationType === 'group') {
    const titleError = required('请填写群组名称')(createForm.title)
    if (titleError) next.title = titleError
  }
  createErrors.value = next
  if (Object.keys(next).length) return
  createSaving.value = true
  try {
    const result = await collabApi.conversations.create({
      conversationType: createForm.conversationType,
      title: createForm.conversationType === 'group' ? createForm.title.trim() : null,
      memberIds: createForm.memberIds,
    })
    createOpen.value = false
    await refreshOverview()
    const created = conversations.value.find(item => item.id === result.id)
    if (created) await openConversation(created)
    pushToast('会话已创建')
  } catch (error) {
    toastError(error, '会话创建失败')
  } finally {
    createSaving.value = false
  }
}

/* ── 消息分页 ── */
function changePage(value: number) {
  messages.setPage(value)
}
function changePageSize(value: number) {
  messages.setPageSize(value)
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="站内信"
      desc="会话与消息都保存在这里；未读以服务端记录为准，打开会话即把已读推进到最新一条。需要我处理的事项请到「个人效率 · 待办收件箱」。"
    >
      <template #spec>
        <SpecPlate
          title="未读概览"
          :rows="[
            { label: '未读消息', value: unread.unreadCount, tone: unread.unreadCount > 0 ? 'lime' : undefined },
            { label: '未读通知', value: unread.unreadNotifications },
            { label: '会话数', value: conversations.length },
          ]"
        />
      </template>
      <PermissionButton perm="collab:msg:send" variant="primary" icon="plus" @click="openCreate">发起会话</PermissionButton>
    </PageHeader>

    <div class="split">
      <aside class="split-side m-surface reveal">
        <div class="split-side-head">
          <SectionLabel title="会话" />
        </div>
        <p v-if="conversationsLoading && !conversations.length" class="faint" style="font-size: 12px; padding: 8px">
          会话加载中…
        </p>
        <div v-else-if="!conversations.length" style="padding: 8px">
          <EmptyState title="还没有会话" desc="点右上角「发起会话」，选择单聊或群组开始沟通。" icon="mail" />
        </div>
        <div v-else role="listbox" aria-label="会话列表">
          <button
            v-for="item in conversations"
            :key="item.id"
            type="button"
            role="option"
            :aria-selected="item.id === activeId"
            :class="['nav-item', item.id === activeId && 'is-active']"
            style="width: 100%; justify-content: space-between; gap: 8px"
            @click="openConversation(item)"
          >
            <span style="display: flex; flex-direction: column; min-width: 0; text-align: left">
              <span class="nav-label" style="font-weight: 620">{{ item.title || '未命名会话' }}</span>
              <span class="faint ellipsis" style="font-size: 11px; max-width: 170px">{{ item.lastMessage || '还没有消息' }}</span>
            </span>
            <span style="display: flex; flex-direction: column; align-items: flex-end; gap: 4px">
              <span class="faint tnum" style="font-size: 10.5px">{{ shortTimeText(item.lastMessageTime) }}</span>
              <Tag v-if="item.unreadCount" tone="danger">{{ item.unreadCount }}</Tag>
            </span>
          </button>
        </div>
      </aside>

      <section class="stack">
        <div v-if="activeConversation" class="panel m-surface reveal" style="display: flex; flex-direction: column; min-height: 460px">
          <header class="panel-head" style="min-height: 56px">
            <div style="display: flex; align-items: baseline; gap: 10px; min-width: 0">
              <SectionLabel :title="activeConversation.title || '未命名会话'" />
              <Tag tone="neutral">{{ activeConversation.conversationTypeLabel || CONVERSATION_TYPE_TEXT[activeConversation.conversationType] || '会话' }}</Tag>
              <span class="faint" style="font-size: 11.5px">{{ activeConversation.memberCount ?? 0 }} 人</span>
            </div>
            <div class="panel-tools">
              <Button variant="ghost" size="sm" icon="refresh" :loading="messages.loading.value" @click="messages.search()">刷新</Button>
            </div>
          </header>

          <div style="flex: 1; min-height: 300px; max-height: calc(100dvh - 400px); overflow-y: auto; padding: 4px 20px 12px; display: flex; flex-direction: column; gap: 14px">
            <p v-if="messages.page.value > 1" class="faint" style="font-size: 11.5px; text-align: center">
              当前为更早的消息（第 {{ messages.page.value }} 页），可翻到第 1 页查看最新。
            </p>
            <div v-if="!orderedMessages.length" style="padding: 32px 0">
              <EmptyState title="还没有消息" desc="在下方输入内容发送第一条消息。" icon="mail" />
            </div>
            <div
              v-for="message in orderedMessages"
              :key="message.id"
              :style="{
                display: 'flex',
                gap: '10px',
                alignItems: 'flex-start',
                flexDirection: message.senderId === myId ? 'row-reverse' : 'row',
              }"
            >
              <Avatar :name="message.senderName || '用户'" :size="30" />
              <div style="min-width: 0; max-width: 78%">
                <div
                  class="faint"
                  :style="{ fontSize: '11px', marginBottom: '3px', textAlign: message.senderId === myId ? 'right' : 'left' }"
                >
                  {{ message.senderId === myId ? '我' : (message.senderName || '—') }} · {{ dateTimeText(message.createTime) }}
                </div>
                <div
                  :class="message.senderId === myId ? 'm-pill' : 'm-surface'"
                  :style="{
                    padding: '9px 12px',
                    borderRadius: 'var(--r-md)',
                    fontSize: '13px',
                    lineHeight: 1.75,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }"
                >{{ message.content }}</div>
                <div v-if="message.actionUrl" :style="{ marginTop: '6px', textAlign: message.senderId === myId ? 'right' : 'left' }">
                  <Button variant="text" size="sm" icon="arrowRight" @click="openAction(message.actionUrl)">查看关联</Button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="messages.total.value > messages.pageSize.value" style="padding: 0 20px">
            <Pagination
              :total="messages.total.value"
              :page="messages.page.value"
              :page-size="messages.pageSize.value"
              :sizes="[20, 50, 100]"
              @update:page="changePage"
              @update:page-size="changePageSize"
            />
          </div>

          <div style="border-top: 1px solid var(--line-strong); padding: 14px 20px 18px">
            <Field label="发送消息" hint="输入 @账号 可提及成员，被提及人会收到通知">
              <TextInput v-model="draft" multiline :rows="2" placeholder="输入消息内容…" :maxlength="2000" />
            </Field>
            <div style="display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 10px">
              <span class="faint" style="font-size: 11.5px">消息发出后不可修改，请确认内容</span>
              <PermissionButton perm="collab:msg:send" variant="primary" icon="arrowRight" :loading="sending" @click="send">发送</PermissionButton>
            </div>
          </div>
        </div>

        <div v-else class="panel m-surface reveal" style="padding: 40px 20px">
          <EmptyState title="请选择会话" desc="左侧选择一个会话查看消息；没有会话时可以先发起单聊或群组。" icon="mail" />
        </div>
      </section>
    </div>

    <FormDialog
      :open="createOpen"
      title="发起会话"
      sub="单聊只能选择一个参与人；群组会话需要名称，可添加多位参与人"
      :width="680"
      :saving="createSaving"
      submit-text="创建会话"
      @close="createOpen = false"
      @submit="submitCreate"
    >
      <Field label="会话类型" group span>
        <Segmented :model-value="createForm.conversationType" label="会话类型" :options="TYPE_OPTIONS" @update:model-value="pickConversationType" />
      </Field>
      <Field v-if="createForm.conversationType === 'group'" label="群组名称" required :error="createErrors.title" span>
        <TextInput
          v-model="createForm.title"
          placeholder="如：M3 协同交付小组"
          :invalid="!!createErrors.title"
          :maxlength="60"
        />
      </Field>
      <Field label="参与人" :error="createErrors.members" span>
        <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap">
          <div style="flex: 1; min-width: 200px">
            <UserSelect :model-value="pickedUserId" placeholder="选择参与人" @update:model-value="pickMember" />
          </div>
          <Button v-if="createForm.conversationType === 'group'" variant="secondary" icon="plus" @click="addMember">添加</Button>
          <Button v-else variant="secondary" icon="check" @click="addMember">选定</Button>
        </div>
      </Field>
      <div v-if="createForm.memberIds.length" class="span-2" style="width: 100%; display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
        <span class="faint" style="font-size: 12px">已选</span>
        <span
          v-for="userId in createForm.memberIds"
          :key="userId"
          style="display: inline-flex; align-items: center; gap: 4px"
        >
          <Tag tone="info">{{ userLabel(userId) }}</Tag>
          <IconButton name="x" label="移除" small @click="removeMember(userId)" />
        </span>
      </div>
      <p class="span-2 faint" style="width: 100%; font-size: 12px; line-height: 1.8">
        单聊重复创建会复用已有会话；群组成员在创建后不可再增删（本期只支持发起会话）。
      </p>
    </FormDialog>
  </div>
</template>
