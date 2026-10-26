<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  Button,
  Checkbox,
  DataTable,
  Drawer,
  Field,
  Modal,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import {
  integrationApi,
  type IntegrationApiKeyCreated,
  type IntegrationApiKeyRow,
  type IntegrationEventLogRow,
} from '@/api/integration'
import { copyText } from '@/utils/clipboard'
import { required, validate } from '@/utils/validate'

/* ============================================================
   开放 API Key（M12 · 2.4.3）
   ------------------------------------------------------------
   密钥只存哈希与前缀，明文**只在创建响应出现一次**：
     · 创建成功立即弹出醒目的一次性保存弹窗（含复制与"我已保存"确认），关闭后不可再查看；
     · 列表永远只显示前缀；权限范围不超过绑定用户权限；撤销即时生效。
   ============================================================ */

const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '有效', value: 'active' },
  { label: '已撤销', value: 'revoked' },
]

const list = useListPage<IntegrationApiKeyRow, { keyword: string; status: string }>({
  query: { keyword: '', status: '' },
  fetch: params => integrationApi.keys.list(params),
})

const columns: Column[] = [
  { key: 'keyName', title: '密钥', minWidth: 260 },
  { key: 'username', title: '绑定用户', width: 130 },
  { key: 'scope', title: '权限范围', minWidth: 220 },
  { key: 'status', title: '状态 / 有效期', width: 170 },
  { key: 'usage', title: '调用情况', width: 170 },
  { key: 'actions', title: '操作', width: 96, className: 'col-actions' },
]

function scopeTags(scope?: string | null) {
  if (!scope || !scope.trim()) return []
  return scope.split(',').map(item => item.trim()).filter(Boolean)
}
function expiresHint(row: IntegrationApiKeyRow) {
  if (!row.expiresAt) return '长期有效'
  const expires = new Date(row.expiresAt.replace(' ', 'T'))
  return expires.getTime() < Date.now() ? `已过期（${row.expiresAt}）` : `有效期至 ${row.expiresAt}`
}

/* ── 签发密钥 ── */
interface KeyForm {
  keyName: string
  userId: number | null
  scope: string
  expiresAt: string
  remark: string
}
const emptyForm = (): KeyForm => ({ keyName: '', userId: null, scope: '', expiresAt: '', remark: '' })
const dialog = ref(false)
const saving = ref(false)
const form = reactive<KeyForm>(emptyForm())
const errors = ref<Partial<Record<keyof KeyForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm())
  errors.value = {}
  dialog.value = true
}

async function submitKey() {
  errors.value = validate(form, { keyName: [required('请填写密钥名称（写清用途）')] })
  if (Object.keys(errors.value).length) return
  saving.value = true
  try {
    const created = await integrationApi.keys.create({
      keyName: form.keyName.trim(),
      userId: form.userId,
      scope: form.scope.trim() || null,
      expiresAt: form.expiresAt.trim() || null,
      remark: form.remark.trim() || null,
    })
    dialog.value = false
    showPlainKey(created)
    await list.load()
  } catch (error) {
    toastError(error, '密钥签发失败')
  } finally {
    saving.value = false
  }
}

/* ── 一次性明文弹窗：关闭后不可再查看 ── */
const plainOpen = ref(false)
const created = ref<IntegrationApiKeyCreated | null>(null)
const savedConfirmed = ref(false)
const copied = ref(false)

function showPlainKey(value: IntegrationApiKeyCreated) {
  created.value = value
  savedConfirmed.value = false
  copied.value = false
  plainOpen.value = true
}

async function copyPlainKey() {
  if (!created.value) return
  const ok = await copyText(created.value.plainKey)
  if (ok) {
    copied.value = true
    pushToast('密钥已复制到剪贴板')
  } else {
    pushToast('复制失败，请手动选中密钥后复制', 'danger')
  }
}

async function closePlainKey() {
  if (!savedConfirmed.value) {
    const ok = await confirm({
      title: '确认已保存密钥吗？',
      message: '关闭后无法再次查看该密钥明文（库中只有哈希与前缀）。如果还没保存，请先复制。',
      confirmText: '我已保存，关闭',
    })
    if (!ok) return
  }
  plainOpen.value = false
  created.value = null
}

async function revokeKey(row: IntegrationApiKeyRow) {
  const ok = await confirm({
    title: '撤销 API Key',
    message: `确认撤销「${row.keyName}」（前缀 ${row.keyPrefix}）吗？撤销后调用方将立即无法访问开放接口，且不可恢复。`,
    confirmText: '撤销',
    danger: true,
  })
  if (!ok) return
  try {
    await integrationApi.keys.revoke(row.id)
    pushToast('密钥已撤销，立即失效')
    await list.load()
  } catch (error) {
    toastError(error, '密钥撤销失败')
  }
}

/* ── 调用记录（开放接口 + 智能体工具） ── */
const auditOpen = ref(false)
const auditLoading = ref(false)
const audits = ref<IntegrationEventLogRow[]>([])
const auditColumns: Column[] = [
  { key: 'createTime', title: '时间', width: 170 },
  { key: 'channel', title: '渠道', width: 100 },
  { key: 'path', title: '接口 / 工具', minWidth: 220 },
  { key: 'status', title: '结果', width: 90 },
  { key: 'costMs', title: '耗时', width: 90, align: 'right' },
  { key: 'clientIp', title: '调用方地址', width: 140 },
]

async function openAudits() {
  auditOpen.value = true
  auditLoading.value = true
  try {
    audits.value = await integrationApi.keys.audits({})
  } catch (error) {
    toastError(error, '调用记录加载失败')
    audits.value = []
  } finally {
    auditLoading.value = false
  }
}

function auditTone(status?: number | null) {
  if (status == null) return 'neutral'
  if (status >= 200 && status < 300) return 'ok'
  if (status === 403 || status === 401) return 'danger'
  return 'neutral'
}
function auditLabel(status?: number | null) {
  if (status == null) return '—'
  if (status >= 200 && status < 300) return `${status} 成功`
  if (status === 403) return '403 越权拒绝'
  if (status === 401) return '401 未认证'
  return String(status)
}

</script>

<template>
  <ListPage
    title="开放 API Key"
    desc="对外调用（/open/v1）与智能体工具使用 API Key 鉴权。密钥只保存哈希与前缀，明文仅签发时显示一次；权限范围不超过绑定用户，撤销后立即失效。"
    table-title="开放密钥"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="integration:key:list" variant="secondary" icon="log" @click="openAudits">
        调用记录
      </PermissionButton>
      <PermissionButton perm="integration:key:add" variant="primary" icon="plus" @click="openCreate">
        签发密钥
      </PermissionButton>
    </template>

    <template #before>
      <p class="faint" style="margin: 0 0 12px">
        密钥明文只在创建成功时显示一次，请立即保存；列表只能看到前缀。权限范围留空表示继承绑定用户权限，
        <b>该 Key 的权限不会超过绑定用户</b>。撤销即时生效，不可恢复。
      </p>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="密钥名称 / 前缀" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="STATUS_OPTIONS" />
      </Field>
    </template>

    <template #cell-keyName="{ row }">
      <div>
        <div class="cell-title">{{ row.keyName }}</div>
        <div class="cell-sub">
          <code class="key-prefix tnum">{{ row.keyPrefix }}…</code>
          <span v-if="row.appName" class="muted"> · {{ row.appName }}</span>
        </div>
      </div>
    </template>
    <template #cell-username="{ row }">
      <span class="muted">{{ row.username || `用户 #${row.userId}` }}</span>
    </template>
    <template #cell-scope="{ row }">
      <div v-if="scopeTags(row.scope).length" class="scope-tags">
        <Tag v-for="code in scopeTags(row.scope).slice(0, 4)" :key="code" tone="info">{{ code }}</Tag>
        <Tag v-if="scopeTags(row.scope).length > 4" tone="neutral">+{{ scopeTags(row.scope).length - 4 }}</Tag>
      </div>
      <span v-else class="cell-sub">继承绑定用户权限</span>
    </template>
    <template #cell-status="{ row }">
      <div>
        <Tag :tone="row.status === 'active' ? 'ok' : 'neutral'" dot>
          {{ row.status === 'active' ? '有效' : '已撤销' }}
        </Tag>
        <div class="cell-sub tnum">{{ expiresHint(row) }}</div>
      </div>
    </template>
    <template #cell-usage="{ row }">
      <div>
        <span class="tnum">{{ row.callCount ?? 0 }} 次</span>
        <div class="cell-sub">{{ row.lastUsedAt ? `最近 ${row.lastUsedAt}` : '尚未使用' }}</div>
      </div>
    </template>
    <template #cell-actions="{ row }">
      <PermissionButton
        v-if="row.status === 'active'"
        perm="integration:key:revoke"
        icon-only
        icon="ban"
        label="撤销密钥"
        danger
        @click="revokeKey(row)"
      />
      <span v-else class="cell-sub">已撤销</span>
    </template>

    <!-- 一次性明文：醒目弹窗，复制 + 确认后关闭 -->
    <Modal
      :open="plainOpen"
      title="密钥已签发 · 请立即保存"
      sub="这是唯一一次显示明文密钥；关闭后无法再次查看，服务端只保存哈希与前缀。"
      :width="680"
      @close="closePlainKey"
    >
      <div v-if="created" class="plain-box">
        <div class="plain-head">
          <span class="section-label">{{ created.keyName }}</span>
          <Tag tone="danger" dot>仅显示一次</Tag>
        </div>
        <code class="plain-key" data-testid="plain-key">{{ created.plainKey }}</code>
        <div class="plain-actions">
          <Button variant="secondary" icon="copy" @click="copyPlainKey">{{ copied ? '已复制，可再次复制' : '复制密钥' }}</Button>
          <span class="cell-sub">前缀 {{ created.keyPrefix }} · 绑定用户 #{{ created.userId }}<span v-if="created.expiresAt"> · 有效期至 {{ created.expiresAt }}</span></span>
        </div>
        <p class="plain-warning">{{ created.warning || '密钥明文只显示这一次，请立即保存；遗失只能撤销后重新签发。' }}</p>
        <label class="plain-confirm">
          <Checkbox v-model="savedConfirmed" label="我已安全保存该密钥" />
          <span>我已保存到安全位置，知晓关闭后不可再查看</span>
        </label>
      </div>
      <template #footer>
        <Button variant="primary" :disabled="!savedConfirmed" @click="closePlainKey">我已保存，关闭</Button>
      </template>
    </Modal>

    <!-- 签发表单 -->
    <FormDialog
      :open="dialog"
      title="签发 API Key"
      sub="密钥用于对外调用与智能体工具；绑定用户决定它能访问的数据范围，权限范围只能缩小、不能放大。"
      :width="700"
      :saving="saving"
      @close="dialog = false"
      @submit="submitKey"
    >
      <Field label="密钥名称" required :error="errors.keyName" hint="写清用途，便于审计与撤销">
        <TextInput v-model="form.keyName" placeholder="如：报表系统只读接入" :invalid="!!errors.keyName" :maxlength="100" />
      </Field>
      <Field label="绑定用户" hint="留空=当前登录用户；该 Key 的数据范围与权限都不超过绑定用户">
        <UserSelect v-model="form.userId" placeholder="选择绑定用户（可空）" />
      </Field>
      <Field label="权限范围" span hint="逗号分隔权限码；留空=继承绑定用户权限">
        <TextInput
          v-model="form.scope"
          multiline
          :rows="2"
          placeholder="如：task:task:list,okr:objective:list,project:project:list"
          :maxlength="1000"
        />
      </Field>
      <Field label="过期时间" hint="yyyy-MM-dd HH:mm；留空表示长期有效">
        <TextInput v-model="form.expiresAt" placeholder="如：2027-01-01 00:00" :maxlength="20" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" placeholder="给谁用、对接哪个系统" :maxlength="500" />
      </Field>
    </FormDialog>

    <!-- 调用记录 -->
    <Drawer
      :open="auditOpen"
      origin-from="tr"
      title="最近调用记录"
      sub="开放接口与智能体工具的调用留痕（不含请求体与任何密钥内容）"
      :width="920"
      @close="auditOpen = false"
    >
      <DataTable
        :columns="auditColumns"
        :data="audits"
        row-key="id"
        :loading="auditLoading"
        empty-text="暂无调用记录"
      >
        <template #cell-channel="{ row }">
          <Tag :tone="row.channel === 'mcp' ? 'info' : 'neutral'">{{ row.channel === 'mcp' ? '智能体工具' : '开放接口' }}</Tag>
        </template>
        <template #cell-path="{ row }">
          <code class="tnum">{{ row.path || '—' }}</code>
        </template>
        <template #cell-status="{ row }">
          <Tag :tone="auditTone(row.status)">{{ auditLabel(row.status) }}</Tag>
        </template>
        <template #cell-costMs="{ row }"><span class="tnum">{{ row.costMs ?? 0 }} ms</span></template>
        <template #cell-clientIp="{ row }"><span class="cell-sub">{{ row.clientIp || '—' }}</span></template>
      </DataTable>
    </Drawer>
  </ListPage>
</template>

<style scoped>
.key-prefix {
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--blue-soft);
  color: var(--blue);
  font-size: 12px;
}
.scope-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.plain-box { display: flex; flex-direction: column; gap: 12px; }
.plain-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.plain-key {
  display: block;
  padding: 14px 16px;
  border-radius: var(--r-sm);
  background: var(--blue-soft);
  color: var(--blue);
  font-size: 15px;
  line-height: 1.6;
  word-break: break-all;
  user-select: all;
  border: 1px solid var(--blue);
}
.plain-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.plain-warning { margin: 0; padding: 10px 12px; border-radius: var(--r-sm); background: var(--red-soft); color: var(--red-text); }
.plain-confirm { display: flex; align-items: center; gap: 10px; }
</style>
